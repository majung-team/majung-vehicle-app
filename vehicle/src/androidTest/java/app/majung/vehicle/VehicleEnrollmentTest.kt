package app.majung.vehicle

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class VehicleEnrollmentTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (!check() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(30)
        assertTrue(check())
    }
    private class Store : VehicleCredentialStore {
        var value: VehicleCredential? = null
        var failSave = false
        override fun load() = value
        override fun save(credential: VehicleCredential) { if (failSave) error("test storage failure"); value = credential }
        override fun clear() { value = null }
    }
    @Test fun lostConfirmationResponseRecoversWithReadAndNeverRetriesMutation() {
        Fixture().use { fixture ->
            val pending = Store(); val registered = Store(); val api = PickupApiClient(fixture.origin)
            lateinit var registration: VehicleRegistrationController
            lateinit var enrollment: VehicleEnrollmentController
            instrumentation.runOnMainSync {
                registration = VehicleRegistrationController(api, registered); registration.refresh()
                enrollment = VehicleEnrollmentController(api, pending, registered, registration)
            }
            await { registration.needsRegistration }
            instrumentation.runOnMainSync { enrollment.start() }; await { !enrollment.busy }
            assertNotNull(pending.value); assertNull(registered.value)
            assertTrue(enrollment.qrLink!!.contains("#enroll/")); assertFalse(enrollment.qrLink!!.contains("D".repeat(43)))
            fixture.claimed.set(true)
            instrumentation.runOnMainSync { enrollment.refresh() }; await { !enrollment.busy }
            instrumentation.runOnMainSync { enrollment.confirm("wrong-id", "test-driver") }
            assertEquals(0, fixture.confirmations.get())
            fixture.loseConfirm.set(true)
            instrumentation.runOnMainSync { enrollment.confirm(fixture.id, "test-driver") }; await { !enrollment.busy }
            assertNull(registered.value); assertNotNull(pending.value)
            instrumentation.runOnMainSync { enrollment.refresh() }; await { !enrollment.busy && registration.canCreate }
            assertEquals(1, fixture.confirmations.get())
            assertNull(pending.value); assertNotNull(registered.value)
            assertTrue(fixture.correctAuthority.get())
        }
    }
    @Test fun failedSecureSaveKeepsPendingAuthorityForRecovery() {
        Fixture().use { fixture ->
            val pending = Store(); val registered = Store(); val api = PickupApiClient(fixture.origin)
            lateinit var registration: VehicleRegistrationController
            lateinit var enrollment: VehicleEnrollmentController
            instrumentation.runOnMainSync {
                registration = VehicleRegistrationController(api, registered); registration.refresh()
                enrollment = VehicleEnrollmentController(api, pending, registered, registration)
            }
            await { registration.needsRegistration }
            instrumentation.runOnMainSync { enrollment.start() }; await { !enrollment.busy }
            fixture.claimed.set(true)
            instrumentation.runOnMainSync { enrollment.refresh() }; await { !enrollment.busy }
            registered.failSave = true
            instrumentation.runOnMainSync { enrollment.confirm(fixture.id, "test-driver") }; await { !enrollment.busy }
            assertNotNull(pending.value); assertNull(registered.value); assertFalse(registration.canCreate)
            registered.failSave = false
            instrumentation.runOnMainSync { enrollment.refresh() }; await { !enrollment.busy && registration.canCreate }
            assertEquals(1, fixture.confirmations.get())
        }
    }
    @Test fun unregisteredPickupTabStaysSelectedAndShowsConnectionAction() {
        Fixture().use { fixture ->
            val app = instrumentation.targetContext.applicationContext as VehicleApplication
            val api = PickupApiClient(fixture.origin)
            val fields = listOf("registration", "pickup", "account", "enrollment").map { VehicleApplication::class.java.getDeclaredField(it).apply { isAccessible = true } }
            val previous = fields.map { it.get(app) }
            lateinit var registration: VehicleRegistrationController
            instrumentation.runOnMainSync {
                registration = VehicleRegistrationController(api, Store()); registration.refresh()
                fields[0].set(app, registration)
                fields[1].set(app, PickupController(api, MemoryPickupSessionStore(), registration))
                fields[2].set(app, VehicleAccountController(api, registration))
                fields[3].set(app, VehicleEnrollmentController(api, Store(), Store(), registration))
            }
            await { registration.needsRegistration }
            try {
                androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    fun buttons(view: android.view.View): List<android.widget.Button> =
                        (if (view is android.widget.Button) listOf(view) else emptyList()) +
                        (if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) } else emptyList())
                    await {
                        var allowed = false
                        scenario.onActivity { activity -> allowed = buttons(activity.window.decorView).first { it.text == "픽업" }.isEnabled }
                        allowed
                    }
                    scenario.onActivity { activity ->
                        val list = buttons(activity.window.decorView)
                        list.first { it.text == "설정" }.performClick()
                        val pickup = list.first { it.text == "픽업" }
                        pickup.performClick()
                        assertTrue(pickup.isSelected)
                        assertTrue(list.any { it.text.toString().contains("휴대전화로 시작하기") && it.isShown })
                    }
                }
            } finally { instrumentation.runOnMainSync { fields.forEachIndexed { i, field -> field.set(app, previous[i]) } } }
        }
    }
    private class Fixture : AutoCloseable {
        val id = UUID.randomUUID().toString()
        val claimed = AtomicBoolean(); val linked = AtomicBoolean(); val loseConfirm = AtomicBoolean()
        val confirmations = AtomicInteger(); val correctAuthority = AtomicBoolean(true)
        private val server = ServerSocket(0)
        val origin = "http://127.0.0.1:${server.localPort}"
        private val running = AtomicBoolean(true)
        private val thread = Thread {
            while (running.get()) try { server.accept().use { socket ->
                val input = socket.getInputStream()
                fun line(): String {
                    val bytes = java.io.ByteArrayOutputStream()
                    while (true) { val b = input.read(); if (b < 0 || b == 10) break; if (b != 13) bytes.write(b) }
                    return bytes.toString("UTF-8")
                }
                val request = line(); if (request.isBlank()) return@use
                val headers = mutableMapOf<String, String>()
                while (true) { val header = line(); if (header.isBlank()) break; val pair = header.split(":", limit = 2); headers[pair[0].lowercase()] = pair[1].trim() }
                val content = String(input.readNBytes(headers["content-length"]?.toIntOrNull() ?: 0), Charsets.UTF_8)
                val path = request.split(" ")[1]
                var status = 200
                val body = when {
                    path == "/api/v1/config" -> "{}"
                    path == "/api/v1/vehicles/me" -> JSONObject().put("vehicleId", id).toString()
                    path == "/api/v1/vehicles/enrollments" -> JSONObject().put("enrollmentId", id).put("deviceToken", "D".repeat(43)).put("code", "Q".repeat(43)).toString()
                    else -> {
                        if (headers["authorization"] != "Bearer " + "D".repeat(43)) correctAuthority.set(false)
                        if (path.endsWith("/confirm")) {
                            assertEquals("test-driver", JSONObject(content).getString("majungId"))
                            confirmations.incrementAndGet(); linked.set(true)
                            if (loseConfirm.get()) status = 500
                        }
                        if (status == 500) "{}" else JSONObject().put("enrollmentId", id)
                            .put("status", if (linked.get()) "LINKED" else if (claimed.get()) "AWAITING_VEHICLE_CONFIRMATION" else "AWAITING_ACCOUNT")
                            .put("nickname", "가상 운전자").put("majungId", "test-driver").toString()
                    }
                }
                val bytes = body.toByteArray()
                socket.getOutputStream().apply { write("HTTP/1.1 $status OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush() }
            } } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        override fun close() { running.set(false); server.close(); thread.join(1000) }
    }
}
