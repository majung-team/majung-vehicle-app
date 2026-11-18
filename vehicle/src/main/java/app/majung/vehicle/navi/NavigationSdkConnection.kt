package app.majung.vehicle.navi

import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import app.majung.vehicle.data.api.NavigationFailure

/** Owns the SDK lifetime. Queued callbacks from an old connection cannot update a new route. */
internal class NavigationSdkConnection(
    private val context: Context,
    private val handler: Handler,
    private val onFailure: (NavigationFailure) -> Unit,
) {
    var helper: NaviHelper? = null
        private set

    var connected = false
        private set

    var generation = 0
        private set

    private var listener: NaviHelperEventListener? = null
    private val connectionTimeout = Runnable {
        if (!connected && helper != null) onFailure(NavigationFailure.CONNECTION_TIMEOUT)
    }

    fun create(onCreationFailure: (NavigationFailure) -> Unit): NaviHelper? {
        generation++
        val instance =
            try {
                NaviHelper(context.applicationContext)
            } catch (_: SecurityException) {
                onCreationFailure(NavigationFailure.PERMISSION_DENIED)
                return null
            } catch (_: Exception) {
                onCreationFailure(NavigationFailure.CONNECTION_FAILED)
                return null
            }
        helper = instance
        handler.removeCallbacks(connectionTimeout)
        handler.postDelayed(connectionTimeout, 12000)
        return instance
    }

    fun dispatch(version: Int, action: () -> Unit) {
        handler.post { if (generation == version && connected) action() }
    }

    fun initialize(
        instance: NaviHelper,
        callbacks: NaviHelperEventListener,
        version: Int,
        beforeListener: () -> Unit,
        afterListener: () -> Unit,
    ) {
        listener = callbacks
        try {
            instance.initialize(
                onConnected = {
                    handler.post {
                        if (generation == version) {
                            handler.removeCallbacks(connectionTimeout)
                            connected = true
                            beforeListener()
                            instance.addListener(callbacks)
                            afterListener()
                        }
                    }
                },
                onFailed = {
                    handler.post {
                        if (generation == version) onFailure(NavigationFailure.CONNECTION_FAILED)
                    }
                },
            )
        } catch (_: SecurityException) {
            onFailure(NavigationFailure.PERMISSION_DENIED)
        } catch (_: Exception) {
            onFailure(NavigationFailure.CONNECTION_FAILED)
        }
    }

    fun invalidate() {
        generation++
        handler.removeCallbacks(connectionTimeout)
        connected = false
    }

    fun release() {
        val instance = helper
        helper = null
        try {
            listener?.let { instance?.removeListener(it) }
            instance?.release()
        } catch (_: Exception) {
            /* Never log SDK payloads. */
        }
        listener = null
    }
}
