package io.github.jqssun.gpssetter.xposed

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedModule
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.WeakHashMap
import java.util.concurrent.Executor

// Dispatch through the selected process's registered Android listener transports.
// These fixes never enter the provider caches or the shared Play Services process.
internal class SingleAppLocationUpdates(
    private val module: XposedModule,
    private val isActive: () -> Boolean,
    private val createFix: (String) -> Location,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val subscriptions = WeakHashMap<Any, Subscription>()

    private class Subscription(
        val manager: LocationManager,
        val provider: String,
        val intervalMillis: Long,
        val minDistanceMeters: Float,
        var lastLocation: Location? = null,
        var lastDelivery: Long = 0L,
    )

    fun install() {
        val managerClass = LocationManager::class.java
        val listenersField = try {
            managerClass.getDeclaredField("sLocationListeners")
        } catch (_: NoSuchFieldException) {
            HiddenApiBypass.getStaticFields(managerClass).filterIsInstance<Field>()
                .first { it.name == "sLocationListeners" }
        }
        val platformListeners = listenersField.apply { isAccessible = true }.get(null) as Map<*, *>
        val transportClass = Class.forName("android.location.LocationManager\$LocationListenerTransport")
        val isRegistered = try {
            transportClass.getDeclaredMethod("isRegistered")
        } catch (_: NoSuchMethodException) {
            HiddenApiBypass.getDeclaredMethod(transportClass, "isRegistered")
        }.apply { isAccessible = true }
        val remoteCallback = Class.forName("android.os.IRemoteCallback")
        val deliver = try {
            transportClass.getDeclaredMethod("onLocationChanged", List::class.java, remoteCallback)
        } catch (_: NoSuchMethodException) {
            HiddenApiBypass.getDeclaredMethod(transportClass, "onLocationChanged", List::class.java, remoteCallback)
        }.apply { isAccessible = true }
        val requestUpdates = managerClass.getMethod(
            "requestLocationUpdates", String::class.java, LocationRequest::class.java,
            Executor::class.java, LocationListener::class.java
        )

        val ticker = object : Runnable {
            override fun run() {
                val active = isActive()
                val iterator = subscriptions.iterator()
                while (iterator.hasNext()) {
                    val (transport, subscription) = iterator.next()
                    try {
                        if (isRegistered.invoke(transport) != true) {
                            iterator.remove()
                        } else if (active) {
                            val now = SystemClock.elapsedRealtime()
                            if (now - subscription.lastDelivery < subscription.intervalMillis ||
                                !subscription.manager.isProviderEnabled(subscription.provider)) continue
                            val fix = createFix(subscription.provider)
                            val previous = subscription.lastLocation
                            if (previous != null && previous.distanceTo(fix) < subscription.minDistanceMeters) continue
                            deliver.invoke(transport, listOf(fix), null)
                            subscription.lastLocation = fix
                            subscription.lastDelivery = now
                        } else {
                            subscription.lastLocation = null
                            subscription.lastDelivery = 0L
                        }
                    } catch (e: Exception) {
                        iterator.remove()
                        module.log(Log.WARN, "GPS Setter Next", "Failed to dispatch a single-app location update", e)
                    }
                }
                if (subscriptions.isNotEmpty()) handler.postDelayed(this, 500L)
            }
        }

        module.hook(requestUpdates).intercept { chain ->
            val result = chain.proceed()
            val manager = chain.thisObject as LocationManager
            val provider = chain.getArg(0) as String
            val request = chain.getArg(1) as LocationRequest
            val listener = chain.getArg(3) as LocationListener
            val transport = synchronized(platformListeners) {
                (platformListeners[listener] as? WeakReference<*>)?.get()
            }
            if (transport != null) handler.post {
                // Finite and passive requests remain under Android's normal delivery rules.
                if (request.intervalMillis == Long.MAX_VALUE || request.durationMillis != Long.MAX_VALUE ||
                    request.maxUpdates != Int.MAX_VALUE) {
                    subscriptions.remove(transport)
                } else {
                    val wasEmpty = subscriptions.isEmpty()
                    subscriptions[transport] = Subscription(
                        manager, provider, request.intervalMillis.coerceAtLeast(500L), request.minUpdateDistanceMeters
                    )
                    if (wasEmpty) {
                        handler.removeCallbacks(ticker)
                        handler.post(ticker)
                    }
                }
            }
            result
        }
    }
}
