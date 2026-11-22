package io.github.jqssun.gpssetter.xposed

import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.UserHandle
import android.util.Log
import android.util.SparseArray
import android.provider.Settings
import android.telephony.TelephonyManager
import android.net.wifi.WifiManager
import io.github.jqssun.gpssetter.BuildConfig
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedModule
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object LocationHook {

    private const val TAG = "GPS Setter Next"
    private const val FRAMEWORK_FIX = "io.github.jqssun.gpssetter.FRAMEWORK_FIX"
    @Volatile var newlat: Double = 45.0000
    @Volatile var newlng: Double = 0.0000
    @Volatile private var accuracy: Float = 10.0f
    // motion for route playback; defaults mean static (no speed, bearing from the real fix)
    @Volatile private var newAlt: Double = 0.0
    @Volatile private var newSpeed: Float = 0f
    @Volatile private var newBearing: Float = -1f
    private val rand: Random = Random()
    private val driftSim = GeoOffset.DriftSimulator(maxRadiusMeters = 25.0)
    private lateinit var module: XposedModule
    private lateinit var settings: Xshare
    @Volatile private var mLastUpdated: Long = 0
    @Volatile private var started = false
    private var appPackage: String? = null // the scoped app this process belongs to; null in system_server
    private val ignorePkg = arrayListOf("com.android.location.fused", BuildConfig.APPLICATION_ID)

    // Cached provider managers (e.g. "gps", "fused", "network") for generating fake fixes in system_server
    private val providerManagers = ConcurrentHashMap<String, Any>()
    @Volatile private var generatorStarted = false
    private val lastProviderReportTimes = ConcurrentHashMap<String, Long>()
    private var locationResultWrap: Method? = null
    @Volatile private var emergencyField: Field? = null // LocationProviderManager.mEmergencyHelper, Android 14+
    @Volatile private var locationToggledAt = Long.MIN_VALUE / 2 // elapsedRealtime of our last off/on switch
    private const val GMS_LOCATION_OFF_WARNING = "com.google.android.gms.location.settings.LocationOffWarningActivity"

    // system = true also re-reads the settings from a remote file, because the framework may not
    // push preference changes into system_server (see Xshare)
    private fun init(xposed: XposedModule, system: Boolean = false) {
        module = xposed
        val open = if (system) { -> ParcelFileDescriptor.AutoCloseInputStream(xposed.openRemoteFile(Xshare.FILE)) } else null
        settings = Xshare(xposed.getRemotePreferences(Xshare.GROUP), open)
    }

    // re-reads settings (and re-rolls the random offset) at most once per interval, so start/stop and new locations apply without a reboot
    private fun isActive(interval: Int): Boolean {
        if (SystemClock.elapsedRealtime() - mLastUpdated > interval) {
            updateLocation()
        }
        return started
    }

    @Synchronized
    private fun updateLocation() {
        try {
            mLastUpdated = SystemClock.elapsedRealtime()
            settings.refresh()
            // an app switched off on the Targets tab reads its real location (live; pushed to app processes)
            val now = settings.isStarted && appliesToProcess(
                settings.singleTarget, appPackage, if (appPackage == null) emptySet() else settings.disabledApps
            )
            if (now != started) module.log(Log.INFO, TAG, "Spoofing ${if (now) "on" else "off"}")
            // own thread: this can run inside a provider's locked onReportLocation, and cleanStop takes those locks
            if (started && !now && appPackage == null) Thread(::cleanStop, "GpsSetter-CleanStop").start()
            started = now
            if (!started) return
            // Smooth realistic drift (Ornstein-Uhlenbeck) instead of erratic white noise jumping
            val lat = settings.getLat
            val (dLat, dLng) = driftSim.tick(lat, settings.jitterMeters.toDouble(), rand)
            newlat = lat + dLat
            newlng = settings.getLng + dLng
            accuracy = settings.accuracy?.toFloatOrNull()?.takeIf { it > 0f } ?: 10.0f
            newAlt = settings.altitude
            newSpeed = settings.speed
            newBearing = settings.bearing

        } catch (e: Exception) {
            module.log(Log.ERROR, TAG, "Failed to read settings", e)
        }
    }

    // After a stop, the last fake fix lingers as the "last known location" in Android and in Play services, whose
    // fused engine keeps re-reporting it and ignores a lone real fix far away. So clear Android's cache, then switch
    // Location off and on, which clears Play services' cache (as Google documents); the next app to ask gets a real fix.
    private fun cleanStop() {
        if (providerManagers.isEmpty()) return // only system_server on Android 12+ feeds fakes into the cache
        try {
            Thread.sleep(500) // let a fake the generator is already delivering land first (it ticks every 250 ms)
            // spoofing restarted during the delay: cancel. Force a fresh read, since the usual one runs at most once
            // a second and the stop was just read, so it would still say "off"
            settings.refresh(force = true)
            updateLocation()
            if (started) return
            // never touch location while an emergency call/SMS is using it
            if (providerManagers.values.any { inEmergency(emergencyField?.get(it)) }) return
            // the switch below clears these too, except the bypass copies kept for emergency services
            var cleared = 0
            for (manager in providerManagers.values) if (clearLastLocations(manager)) cleared++
            module.log(Log.INFO, TAG, "Stopped: cleared the last location of $cleared providers")
            val thread = Class.forName("android.app.ActivityThread")
            val context = thread.getMethod("getSystemContext").invoke(thread.getMethod("currentActivityThread").invoke(null)) as Context
            toggleLocation(context.getSystemService(LocationManager::class.java))
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "Clean stop failed", t)
        }
    }

    // only if it's on; always switched back on, even if the wait is interrupted
    private fun toggleLocation(lm: LocationManager) {
        val userId = Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null) as Int
        val user = UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType).invoke(null, userId)
        val isOn = LocationManager::class.java.getMethod("isLocationEnabledForUser", UserHandle::class.java)
        if (isOn.invoke(lm, user) != true) return
        val set = LocationManager::class.java.getMethod("setLocationEnabledForUser", Boolean::class.javaPrimitiveType, UserHandle::class.java)
        locationToggledAt = SystemClock.elapsedRealtime()
        set.invoke(lm, false, user)
        try {
            Thread.sleep(2000) // shorter risks Play services missing the off state; 2 s was tested
        } finally {
            set.invoke(lm, true, user)
        }
        module.log(Log.INFO, TAG, "Stopped: switched Location off and on")
    }

    // LocationProviderManager.mLastLocations: per user, a LastLocation holding fine/coarse (+ bypass) fixes
    private fun clearLastLocations(manager: Any): Boolean {
        val lastLocations = field(manager.javaClass, "mLastLocations") ?: return false
        // mMultiplexerLock from Android 13, mLock on 12
        val lock = providerLock(manager) ?: return false
        synchronized(lock) {
            val perUser = lastLocations.get(manager) as? SparseArray<*> ?: return false
            for (i in 0 until perUser.size()) {
                val last = perUser.valueAt(i) ?: continue
                for (f in last.javaClass.declaredFields) if (f.type == Location::class.java) {
                    f.isAccessible = true
                    f.set(last, null)
                }
            }
        }
        return true
    }

    private fun field(cls: Class<*>, name: String): Field? {
        var c: Class<*>? = cls
        while (c != null) {
            try { return c.getDeclaredField(name).apply { isAccessible = true } } catch (_: NoSuchFieldException) {}
            c = c.superclass
        }
        return null
    }

    private fun providerLock(manager: Any): Any? =
        (field(manager.javaClass, "mMultiplexerLock") ?: field(manager.javaClass, "mLock"))?.get(manager)

    // Copies bearing where needed. Framework reports represent a newly sampled simulation position.
    private fun fakeLocation(
        origin: Location? = null,
        provider: String = LocationManager.GPS_PROVIDER,
        fresh: Boolean = false,
    ): Location {
        val location = Location(origin?.provider ?: provider)
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val nowMs = System.currentTimeMillis()
        if (origin != null && !fresh) {
            location.time = if (origin.time > 0) origin.time else nowMs
            location.elapsedRealtimeNanos = if (origin.elapsedRealtimeNanos > 0) origin.elapsedRealtimeNanos else nowNanos
        } else {
            location.time = nowMs
            location.elapsedRealtimeNanos = nowNanos
        }
        if (fresh) location.extras = Bundle().apply { putBoolean(FRAMEWORK_FIX, true) }
        location.latitude = if (newlat == 0.0 && newlng == 0.0) 0.000001 else newlat
        location.longitude = if (newlat == 0.0 && newlng == 0.0) 0.000001 else newlng
        // Realistic GPS accuracy for navigation apps (3.0 - 15.0 m)
        location.accuracy = accuracy.coerceIn(1.0f, 15.0f)
        location.altitude = if (newAlt != 0.0) newAlt else 35.0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            location.verticalAccuracyMeters = 1.5f
        }
        if (newSpeed > 0f) {
            location.speed = newSpeed
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                location.speedAccuracyMetersPerSecond = 0.5f
            }
        } else {
            location.speed = 0f
        }
        if (newBearing >= 0f) {
            location.bearing = newBearing
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                location.bearingAccuracyDegrees = 1.0f
            }
        } else if (origin != null && origin.hasBearing()) {
            location.bearing = origin.bearing
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && origin.hasBearingAccuracy()) {
                location.bearingAccuracyDegrees = origin.bearingAccuracyDegrees
            }
        }
        try {
            HiddenApiBypass.invoke(location.javaClass, location, "setIsFromMockProvider", false)
        } catch (e: Exception) {
            module.log(Log.WARN, TAG, "unable to set mock $e")
        }
        return location
    }

    private fun wrapLocationResult(locationResultClass: Class<*>, location: Location): Any? {
        if (locationResultWrap == null) {
            locationResultWrap = try {
                locationResultClass.getMethod("wrap", List::class.java)
            } catch (e: NoSuchMethodException) {
                try {
                    locationResultClass.getMethod("create", List::class.java)
                } catch (e2: NoSuchMethodException) {
                    locationResultClass.getMethod("wrap", Array<Location>::class.java)
                }
            }
        }
        val wrap = locationResultWrap ?: return null
        return if (wrap.parameterTypes[0] == List::class.java) {
            wrap.invoke(null, listOf(location))
        } else {
            wrap.invoke(null, arrayOf(location))
        }
    }

    private fun registerProviderManager(manager: Any, lpmClass: Class<*>) {
        if (!lpmClass.isInstance(manager)) return
        try {
            val getName = lpmClass.getMethod("getName")
            val name = getName.invoke(manager) as? String ?: return
            if (providerManagers.putIfAbsent(name, manager) == null) {
                module.log(Log.INFO, TAG, "Discovered location provider: $name")
            }
        } catch (_: Throwable) {}
    }

    private fun scanLms(instance: Any, lmsClass: Class<*>, lpmClass: Class<*>) {
        try {
            try {
                val getLpm = lmsClass.getDeclaredMethod("getLocationProviderManager", String::class.java).apply {
                    isAccessible = true
                }
                for (name in listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                    val mgr = getLpm.invoke(instance, name)
                    if (mgr != null) registerProviderManager(mgr, lpmClass)
                }
            } catch (_: Throwable) {}

            for (field in lmsClass.declaredFields) {
                if (Iterable::class.java.isAssignableFrom(field.type)) {
                    field.isAccessible = true
                    val list = field.get(instance) as? Iterable<*> ?: continue
                    for (item in list) {
                        if (item != null && lpmClass.isInstance(item)) {
                            registerProviderManager(item, lpmClass)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "Failed scanning LMS fields", t)
        }
    }

    private fun tryResolveProviders(classLoader: ClassLoader, lmsClass: Class<*>, lpmClass: Class<*>) {
        try {
            val smClass = classLoader.loadClass("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "location") ?: return
            if (lmsClass.isInstance(binder)) {
                scanLms(binder, lmsClass, lpmClass)
            }
        } catch (_: Throwable) {}
    }

    // Keep each provider updating even when its underlying source has stopped reporting.
    private fun startFixGenerator(classLoader: ClassLoader, lmsClass: Class<*>, lpmClass: Class<*>, emergencyField: Field?) {
        if (generatorStarted) return
        generatorStarted = true

        val resultClass = Class.forName("android.location.LocationResult")
        val onReportLocation = lpmClass.getDeclaredMethod("onReportLocation", resultClass).apply {
            isAccessible = true
        }

        Thread({
            module.log(Log.INFO, TAG, "Started fake fix generator loop")
            while (true) {
                try {
                    Thread.sleep(250)
                    if (!isActive(200)) continue

                    val cadence = if (newBearing >= 0f) 500 else 1_000

                    // Never generate fake fixes during an active emergency call/SMS
                    val inEmerg = providerManagers.values.any { inEmergency(emergencyField?.get(it)) }
                    if (inEmerg) continue

                    if (!providerManagers.containsKey(LocationManager.GPS_PROVIDER)) {
                        tryResolveProviders(classLoader, lmsClass, lpmClass)
                    }

                    val targets = listOf(
                        LocationManager.GPS_PROVIDER,
                        LocationManager.FUSED_PROVIDER,
                        LocationManager.NETWORK_PROVIDER
                    )

                    for (name in targets) {
                        val manager = providerManagers[name] ?: continue
                        try {
                            val lock = providerLock(manager) ?: continue
                            synchronized(lock) {
                                val now = SystemClock.elapsedRealtime()
                                val lastReport = lastProviderReportTimes[name] ?: 0L
                                if (now - lastReport >= cadence && isActive(200) && !inEmergency(emergencyField?.get(manager))) {
                                    val fix = fakeLocation(provider = name, fresh = true)
                                    wrapLocationResult(resultClass, fix)?.let {
                                        onReportLocation.invoke(manager, it)
                                        lastProviderReportTimes[name] = SystemClock.elapsedRealtime()
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            module.log(Log.WARN, TAG, "Failed to deliver fake fix to $name", t)
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    module.log(Log.WARN, TAG, "Fix generator tick error", t)
                }
            }
        }, "GpsSetter-FixGenerator").apply {
            isDaemon = true
            start()
        }
    }

    // for methods taking a single Location: hand the original our fake instead
    private fun proceedWithFake(chain: Chain): Any? =
        chain.proceed(arrayOf<Any?>(fakeLocation(chain.getArg(0) as Location?)))

    private fun isFrameworkFix(location: Any?): Boolean =
        (location as? Location)?.extras?.getBoolean(FRAMEWORK_FIX) == true

    // one missing method (ROM / version differences) shouldn't take the other hooks down with it
    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
            module.log(Log.INFO, TAG, "Hooked $what")
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "Skipping hook $what", t)
        }
    }

    fun initSystemHooks(xposed: XposedModule, classLoader: ClassLoader) {
        init(xposed, system = true)
        module.log(Log.INFO, TAG, "Hooking system server (SDK ${Build.VERSION.SDK_INT})")
        if (!settings.isHookedSystem) {
            module.log(Log.INFO, TAG, "System hook disabled in settings")
            return
        }
        val interval = 200

        // LocationManagerService lived in com.android.server up to Android 10, com.android.server.location from 11
        if (Build.VERSION.SDK_INT < 30) {
            val lms = classLoader.loadClass("com.android.server.LocationManagerService")

            safely("getLastLocation") {
                module.hook(lms.getDeclaredMethod("getLastLocation", LocationRequest::class.java, String::class.java))
                    .intercept { chain -> if (isActive(interval)) fakeLocation() else chain.proceed() }
            }

            for (method in lms.declaredMethods) {
                if (method.returnType == Boolean::class.java &&
                    method.name in listOf("addGnssBatchingCallback", "addGnssMeasurementsListener", "addGnssNavigationMessageListener")
                ) {
                    safely(method.name) {
                        module.hook(method).intercept { chain -> if (isActive(interval)) false else chain.proceed() }
                    }
                }
            }

            safely("callLocationChangedLocked") {
                val receiver = classLoader.loadClass("com.android.server.LocationManagerService\$Receiver")
                module.hook(receiver.getDeclaredMethod("callLocationChangedLocked", Location::class.java))
                    .intercept { chain -> if (isActive(interval)) proceedWithFake(chain) else chain.proceed() }
            }
        } else {
            val lms = classLoader.loadClass("com.android.server.location.LocationManagerService")

            for (method in lms.declaredMethods) {
                if (method.name == "getLastLocation" && method.returnType == Location::class.java) {
                    // params: String::class.java, LastLocationRequest::class.java, String::class.java, String::class.java
                    safely(method.name) {
                        module.hook(method).intercept { chain -> if (isActive(interval)) fakeLocation() else chain.proceed() }
                    }
                } else if (method.name in listOf("addGnssBatchingCallback", "startGnssBatch", "addGnssAntennaInfoListener", "addGnssMeasurementsListener", "addGnssNavigationMessageListener")) {
                    // refuse raw GNSS data; these return boolean on Android 11 and void from 12
                    val refused: Any? = if (method.returnType == Boolean::class.java) false else null
                    safely(method.name) {
                        module.hook(method).intercept { chain -> if (isActive(interval)) refused else chain.proceed() }
                    }
                }
            }

            safely("injectLocation") {
                module.hook(lms.getDeclaredMethod("injectLocation", Location::class.java))
                    .intercept { chain -> if (isActive(interval)) proceedWithFake(chain) else chain.proceed() }
            }

            // Android 12+: every provider's fix (gps, network, fused, passive) passes through here on
            // its way to the last-location cache and to every app listening for updates
            if (Build.VERSION.SDK_INT >= 31) {
                val lpm = classLoader.loadClass("com.android.server.location.provider.LocationProviderManager")
                val result = Class.forName("android.location.LocationResult") // @SystemApi, not in the public SDK
                val getLast = result.getMethod("getLastLocation")
                val getName = lpm.getMethod("getName")
                // Android 14+ only; on 12/13 there is no emergency state here to check
                val emergency = try { lpm.getDeclaredField("mEmergencyHelper").apply { isAccessible = true } } catch (e: NoSuchFieldException) { null }
                emergencyField = emergency

                safely("onReportLocation") {
                    module.hook(lpm.getDeclaredMethod("onReportLocation", result)).intercept { chain ->
                        val manager = chain.thisObject
                        registerProviderManager(manager, lpm)
                        val name = try { getName.invoke(manager) as? String } catch (_: Throwable) { null }
                        if (name == null || name == LocationManager.PASSIVE_PROVIDER ||
                            !isActive(interval) || inEmergency(emergency?.get(manager))) return@intercept chain.proceed()

                        // A report represents the current simulated position. An old batch's timestamps
                        // can put that position behind a fix already sent by the generator.
                        val original = getLast.invoke(chain.getArg(0)) as? Location ?: return@intercept chain.proceed()
                        if (isFrameworkFix(original)) return@intercept chain.proceed()
                        val fix = fakeLocation(original, fresh = true)
                        val wrapped = wrapLocationResult(result, fix) ?: return@intercept chain.proceed()
                        chain.proceed(arrayOf<Any?>(wrapped)).also {
                            lastProviderReportTimes[name] = fix.elapsedRealtimeNanos / 1_000_000
                        }
                    }
                }

                for (method in lms.declaredMethods) {
                    if (method.name == "addLocationProviderManager" && method.parameterTypes.isNotEmpty()) {
                        safely("addLocationProviderManager") {
                            module.hook(method).intercept { chain ->
                                val mgr = chain.getArg(0)
                                if (mgr != null) registerProviderManager(mgr, lpm)
                                chain.proceed()
                            }
                        }
                    } else if (method.name in listOf("onSystemReady", "onSystemThirdPartyAppsCanStart")) {
                        safely(method.name) {
                            module.hook(method).intercept { chain ->
                                scanLms(chain.thisObject, lms, lpm)
                                chain.proceed()
                            }
                        }
                    }
                }

                startFixGenerator(classLoader, lms, lpm, emergency)

                // Play services opens a "No location access" warning whenever Location goes off, including
                // cleanStop's 2 s off/on switch; drop just that one start for a few seconds after our switch
                safely("startActivity") {
                    val atms = classLoader.loadClass("com.android.server.wm.ActivityTaskManagerService")
                    for (m in atms.declaredMethods) {
                        val i = m.parameterTypes.indexOf(Intent::class.java)
                        if (m.name != "startActivity" || m.returnType != Int::class.javaPrimitiveType || i < 0) continue
                        module.hook(m).intercept { chain ->
                            // every activity start passes here: a failed check must never block one
                            val ours = runCatching {
                                SystemClock.elapsedRealtime() - locationToggledAt < 10_000 &&
                                    (chain.getArg(i) as? Intent)?.component?.className == GMS_LOCATION_OFF_WARNING
                            }.getOrDefault(false)
                            if (ours) 0 /* ActivityManager.START_SUCCESS */ else chain.proceed()
                        }
                    }
                }
            }
        }
    }


    // never hand emergency services a fake fix: during an emergency call/SMS real locations go through untouched
    private fun inEmergency(helper: Any?): Boolean = helper != null && try {
        helper.javaClass.getMethod("isInEmergency", Long::class.java).invoke(helper, 0L) as Boolean
    } catch (t: Throwable) {
        false
    }

    fun initAppHooks(xposed: XposedModule, packageName: String) {
        if (packageName in ignorePkg) return
        init(xposed)
        appPackage = packageName
        val interval = 80 // 200

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) safely("single-app location updates") {
            SingleAppLocationUpdates(
                module,
                isActive = { settings.singleTarget == packageName && isActive(interval) },
                createFix = { provider -> fakeLocation(provider = provider, fresh = true) },
            ).install()
        }

        for (method in Location::class.java.declaredMethods) {
            val spoofed: (() -> Any)? = when (method.name) {
                "getLatitude" -> { -> newlat }
                "getLongitude" -> { -> newlng }
                "getAccuracy" -> { -> accuracy.coerceIn(1.0f, 15.0f) }
                "getAltitude" -> { -> if (newAlt != 0.0) newAlt else 35.0 }
                "getSpeed" -> { -> if (newSpeed > 0f) newSpeed else 0f }
                "getBearing" -> { -> if (newBearing >= 0f) newBearing else 0f }
                "hasAltitude" -> { -> true }
                "hasSpeed" -> { -> newSpeed > 0f }
                "hasBearing" -> { -> newBearing >= 0f }
                "getSpeedAccuracyMetersPerSecond" -> { -> 1.0f }
                "getBearingAccuracyDegrees" -> { -> 1.0f }
                "getVerticalAccuracyMeters" -> { -> 2.0f }
                "isFromMockProvider" -> { -> false }
                "isMock" -> { -> false }
                else -> null
            }
            if (spoofed != null) safely(method.name) {
                module.hook(method).intercept { chain ->
                    // Keep framework samples stable when a scoped app reads the same fix again.
                    if (!isFrameworkFix(chain.thisObject) && isActive(interval)) spoofed() else chain.proceed()
                }
            }
        }

        safely("Location.set") {
            module.hook(Location::class.java.getDeclaredMethod("set", Location::class.java))
                .intercept { chain ->
                    if (!isFrameworkFix(chain.getArg(0)) && isActive(interval)) proceedWithFake(chain) else chain.proceed()
                }
        }

        safely("getLastKnownLocation") {
            module.hook(LocationManager::class.java.getDeclaredMethod("getLastKnownLocation", String::class.java))
                .intercept { chain ->
                    if (isActive(interval)) fakeLocation(provider = chain.getArg(0) as String) else chain.proceed()
                }
        }

        // Stealth Cloaking 1: Hide mock location flags from Settings.Secure
        safely("Settings.Secure.getInt(mock_location)") {
            val getIntMethod = Settings.Secure::class.java.getMethod("getInt", android.content.ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType)
            module.hook(getIntMethod).intercept { chain ->
                val key = chain.getArg(1) as? String
                if (isActive(interval) && (key == "mock_location" || key == "allow_mock_location")) 0 else chain.proceed()
            }
        }

        safely("Settings.Secure.getString(mock_location)") {
            val getStringMethod = Settings.Secure::class.java.getMethod("getString", android.content.ContentResolver::class.java, String::class.java)
            module.hook(getStringMethod).intercept { chain ->
                val key = chain.getArg(1) as? String
                if (isActive(interval) && (key == "mock_location" || key == "allow_mock_location")) "0" else chain.proceed()
            }
        }

        // Stealth Cloaking 2: Mask Cell Tower triangulation leaks
        safely("TelephonyManager.getAllCellInfo") {
            for (m in TelephonyManager::class.java.declaredMethods) {
                if (m.name == "getAllCellInfo" && m.parameterTypes.isEmpty()) {
                    module.hook(m).intercept { chain ->
                        if (isActive(interval)) emptyList<Any>() else chain.proceed()
                    }
                }
            }
        }

        safely("TelephonyManager.getCellLocation") {
            for (m in TelephonyManager::class.java.declaredMethods) {
                if (m.name == "getCellLocation" && m.parameterTypes.isEmpty()) {
                    module.hook(m).intercept { chain ->
                        if (isActive(interval)) null else chain.proceed()
                    }
                }
            }
        }

        // Stealth Cloaking 3: Mask Wi-Fi AP scan leaks (prevents Google Geolocation API BSSID lookup)
        safely("WifiManager.getScanResults") {
            for (m in WifiManager::class.java.declaredMethods) {
                if (m.name == "getScanResults" && m.parameterTypes.isEmpty()) {
                    module.hook(m).intercept { chain ->
                        if (isActive(interval)) emptyList<Any>() else chain.proceed()
                    }
                }
            }
        }
    }
}
