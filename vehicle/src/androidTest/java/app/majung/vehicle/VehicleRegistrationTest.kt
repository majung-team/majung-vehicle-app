package app.majung.vehicle

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class VehicleRegistrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<VehicleApplication>()
    private val main get() = InstrumentationRegistry.getInstrumentation()
    @Test fun encryptedVehicleCredentialIsIndependentAndRejectsTampering() {
        val store = EncryptedVehicleCredentialStore(context)
        val credential = VehicleCredential(UUID.randomUUID().toString(), "C".repeat(43))
        val previous = runCatching { store.load() }.getOrNull()
        try {
            store.save(credential)
            val file = File(context.noBackupFilesDir, "vehicle-credential.bin")
            val bytes = file.readBytes()
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains(credential.token))
            assertEquals(credential, EncryptedVehicleCredentialStore(context).load())
            EncryptedPickupSessionStore(context).clear()
            assertEquals(credential, store.load())
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            file.writeBytes(bytes)
            assertTrue(runCatching { store.load() }.isFailure)
        } finally { store.clear(); previous?.let(store::save) }
    }

    @Test fun qrContainsTheDriverSharePage() {
        val link = "https://example.com/#share/${UUID.randomUUID()}/${"A".repeat(43)}"
        val bitmap = InvitationQr.create(link)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = com.google.zxing.RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val decoded = com.google.zxing.MultiFormatReader().decode(com.google.zxing.BinaryBitmap(
            com.google.zxing.common.HybridBinarizer(source)))
        assertEquals(link, decoded.text)
    }

    @Test fun registersRestoresSendsVehicleAuthorityAndClearsRevokedCredential() {
        RegistrationServer().use { server ->
            val store = object : VehicleCredentialStore {
                var value: VehicleCredential? = null
                override fun load() = value
                override fun save(credential: VehicleCredential) { value = credential }
                override fun clear() { value = null }
            }
            lateinit var registration: VehicleRegistrationController
            lateinit var pickup: PickupController
            main.runOnMainSync {
                registration = VehicleRegistrationController(PickupApiClient(server.origin), store)
                registration.refresh()
            }
            await { registration.needsRegistration }
            assertFalse(registration.canCreate)
            main.runOnMainSync { registration.register("R".repeat(43)) }
            await { registration.canCreate }
            assertEquals(server.token, store.value?.token)
            main.runOnMainSync {
                registration = VehicleRegistrationController(PickupApiClient(server.origin), store)
                assertFalse(registration.canCreate)
                registration.refresh()
            }
            await { registration.canCreate }
            main.runOnMainSync {
                pickup = PickupController(PickupApiClient(server.origin), MemoryPickupSessionStore(), registration)
                pickup.create(PickupPlace("예시 장소", 37.0, 127.0))
            }
            await { pickup.pickupId != null }
            assertTrue(server.creationAuthenticated.get())
            server.revoked.set(true)
            main.runOnMainSync { registration.refresh() }
            await { registration.needsRegistration }
            assertFalse(registration.canCreate)
            assertNull(store.value)
            assertTrue(registration.message.contains("철회"))
        }
    }

    @Test fun corruptCredentialDeletionFailureBlocksRegistration() {
        val broken = object : VehicleCredentialStore {
            override fun load(): VehicleCredential? = throw IllegalStateException()
            override fun save(credential: VehicleCredential) { throw IllegalStateException() }
            override fun clear() { throw IllegalStateException() }
        }
        lateinit var controller: VehicleRegistrationController
        main.runOnMainSync { controller = VehicleRegistrationController(PickupApiClient(), broken); controller.refresh() }
        await { !controller.busy }
        assertFalse(controller.canCreate)
        assertFalse(controller.needsRegistration)
        assertTrue(controller.message.contains("지우지 못했어요"))
    }
    @Test fun unavailableServerDoesNotOfferRegistrationOrDeleteSavedAuthority() {
        RegistrationServer().use { server ->
            val saved = VehicleCredential(UUID.randomUUID().toString(), server.token)
            val store = object : VehicleCredentialStore {
                var clears = 0
                override fun load() = saved
                override fun save(credential: VehicleCredential) { fail("Must not replace saved authority") }
                override fun clear() { clears++ }
            }
            server.unavailable.set(true)
            lateinit var controller: VehicleRegistrationController
            main.runOnMainSync {
                controller = VehicleRegistrationController(PickupApiClient(server.origin), store)
                controller.refresh()
            }
            await { !controller.busy }
            assertFalse(controller.canCreate)
            assertFalse(controller.needsRegistration)
            assertEquals(0, store.clears)
            main.runOnMainSync { controller.register("R".repeat(43)) }
            assertEquals(0, server.registrationAttempts.get())
            assertEquals(saved, store.load())
        }
    }

    @Test fun expiredCodeIsNotRetriedAndStorageFailureDoesNotEnableCreation() {
        RegistrationServer().use { server ->
            val store = object : VehicleCredentialStore {
                override fun load(): VehicleCredential? = null
                override fun save(credential: VehicleCredential) { throw IllegalStateException("fixture storage failure") }
                override fun clear() { }
            }
            lateinit var controller: VehicleRegistrationController
            main.runOnMainSync {
                controller = VehicleRegistrationController(PickupApiClient(server.origin), store)
                controller.refresh()
            }
            await { controller.needsRegistration }
            server.expiredCode.set(true)
            main.runOnMainSync { controller.register("R".repeat(43)) }
            await { !controller.busy }
            assertFalse(controller.canCreate)
            assertTrue(controller.message.contains("만료"))
            assertEquals(1, server.registrationAttempts.get())
            server.expiredCode.set(false)
            main.runOnMainSync { controller.register("S".repeat(43)) }
            await { !controller.busy }
            assertFalse(controller.canCreate)
            assertTrue(controller.message.contains("자동 재시도하지 않아요"))
            assertEquals(2, server.registrationAttempts.get())
        }
    }

    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < until) {
            var done = false
            main.runOnMainSync { done = condition() }
            if (done) return
            SystemClock.sleep(30)
        }
        throw AssertionError("Vehicle registration did not reach expected state")
    }
    private class RegistrationServer : AutoCloseable {
        private val socket = ServerSocket(0)
        val origin = "http://127.0.0.1:${socket.localPort}"
        val token = "C".repeat(43)
        private val id = UUID.randomUUID().toString()
        val revoked = AtomicBoolean()
        val unavailable = AtomicBoolean()
        val expiredCode = AtomicBoolean()
        val registrationAttempts = AtomicInteger()
        val creationAuthenticated = AtomicBoolean()
        private val thread = Thread {
            while (!socket.isClosed) try {
                socket.accept().use { client ->
                    val input = client.getInputStream()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) { val value = input.read(); if (value < 0) break; head.append(value.toChar()) }
                    val headers = head.toString()
                    val length = Regex("(?i)Content-Length: (\\d+)").find(headers)?.groupValues?.get(1)?.toInt() ?: 0
                    var remaining = length
                    while (remaining > 0) { val bytes = ByteArray(remaining); val read = input.read(bytes); if (read < 0) break; remaining -= read }
                    val path = headers.lineSequence().first().split(' ')[1]
                    var status = 200
                    val body = if (unavailable.get()) { status = 503; """{"code":"FIXTURE_UNAVAILABLE"}""" } else when (path) {
                        "/api/v1/config" -> """{"developmentToolsEnabled":false}"""
                        "/api/v1/vehicles/registrations" -> {
                            registrationAttempts.incrementAndGet()
                            if (expiredCode.get()) { status = 410; """{"code":"REGISTRATION_CODE_UNAVAILABLE"}""" }
                            else { status = 201; """{"vehicleId":"$id","vehicleToken":"$token"}""" }
                        }
                        "/api/v1/vehicles/me" -> if (revoked.get()) { status = 403; """{"code":"VEHICLE_REVOKED"}""" } else """{"vehicleId":"$id"}"""
                        "/api/v1/pickups" -> {
                            creationAuthenticated.set(headers.contains("Authorization: Bearer $token"))
                            status = if (creationAuthenticated.get()) 201 else 403
                            """{"pickupId":"${UUID.randomUUID()}","driverToken":"${"D".repeat(43)}","inviteToken":"${"I".repeat(43)}"}"""
                        }
                        else -> "{}"
                    }
                    val bytes = body.toByteArray()
                    client.getOutputStream().apply {
                        write("HTTP/1.1 $status Response\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        override fun close() { socket.close(); thread.join(1000) }
    }
}
