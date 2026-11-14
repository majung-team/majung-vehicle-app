package app.majung.vehicle


import app.majung.vehicle.*
import app.majung.vehicle.ui.*
import app.majung.vehicle.ui.sections.*
import app.majung.vehicle.navi.*
import app.majung.vehicle.domain.*
import app.majung.vehicle.data.api.*
import app.majung.vehicle.data.storage.*
import app.majung.vehicle.util.*
import app.majung.vehicle.service.*

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Isolated HTTP fixture. Run on a separate test installation with location denied. */
class PickupSharingPermissionTest {
    @Test fun approvedPickupDoesNotTransmitWithoutLocationPermission() {
        val main = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<VehicleApplication>()
        assumeTrue("Run separately with location permission revoked", !PickupSharingService.hasLocationPermission(app))
        val field = VehicleApplication::class.java.getDeclaredField("pickup").apply { isAccessible = true }
        val original = app.pickup
        val accepted = app.sharingNotice.accepted
        val preferences = app.getSharedPreferences("location-permission", android.content.Context.MODE_PRIVATE)
        val hadRequested = preferences.contains("requested")
        val requested = preferences.getBoolean("requested", false)
        PermissionFixture().use { fixture ->
            val saved = SavedPickupSession(java.util.UUID.randomUUID().toString(), "A".repeat(43), null, 0)
            val store = MemoryPickupSessionStore().apply { save(saved) }
            val pickup = PickupController(PickupApiClient(fixture.origin), store)
            try {
                main.runOnMainSync {
                    field.set(app, pickup)
                    app.sharingNotice.accept()
                    pickup.restoreSession()
                }
                await { pickup.online && pickup.status == "ACTIVE" }
                main.runOnMainSync { pickup.confirmRecoveredSharing() }
                // Model an already-denied permission without reopening the OS prompt.
                preferences.edit().putBoolean("requested", true).commit()
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    main.waitForIdleSync()
                    scenario.onActivity { activity ->
                        activity.startService(android.content.Intent(activity, PickupSharingService::class.java))
                    }
                    SystemClock.sleep(6000)
                    main.runOnMainSync {
                        assertTrue("Denied permission must not start sharing", !app.sharingRunning)
                        assertTrue(pickup.status == "ACTIVE" && pickup.online)
                    }
                    assertTrue("No telemetry upload or invalidation without permission", fixture.telemetryWrites.get() == 0)
                    assertTrue("Denied start must preserve the sequence", store.load()?.vehicleSequence == 0L)
                }
            } finally {
                main.runOnMainSync {
                    app.stopService(android.content.Intent(app, PickupSharingService::class.java))
                    field.set(app, original)
                    if (accepted) app.sharingNotice.accept() else app.sharingNotice.withdraw()
                }
                preferences.edit().apply {
                    if (hadRequested) putBoolean("requested", requested) else remove("requested")
                }.commit()
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Permission test did not reach expected state")
    }

    private class PermissionFixture : AutoCloseable {
        private val server = java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))
        val origin = "http://127.0.0.1:${server.localPort}"
        val telemetryWrites = java.util.concurrent.atomic.AtomicInteger()
        private val worker = Thread {
            while (!server.isClosed) try {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val request = reader.readLine().orEmpty()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    if (request.startsWith("PUT ") || request.startsWith("DELETE ")) telemetryWrites.incrementAndGet()
                    val bytes = """{"status":"ACTIVE","place":{"label":"권한 확인 시험","latitude":37,"longitude":127}}""".toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        override fun close() { server.close(); worker.join(1000) }
    }
}
