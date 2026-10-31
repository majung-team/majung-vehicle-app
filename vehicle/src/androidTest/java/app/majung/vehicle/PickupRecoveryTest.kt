package app.majung.vehicle

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Test

class PickupRecoveryTest {
    private val context get() = ApplicationProvider.getApplicationContext<VehicleApplication>()
    private val main get() = InstrumentationRegistry.getInstrumentation()
    private fun saved() = SavedPickupSession(UUID.randomUUID().toString(), "A".repeat(43), "B".repeat(43), 9, "majung:" + UUID.randomUUID())
    private fun store() = EncryptedPickupSessionStore(context)
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < end) {
            var done = false
            main.runOnMainSync { done = condition() }
            if (done) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Recovery did not reach expected state")
    }

    @Test fun encryptedSessionSurvivesNewStoreAndRejectsTampering() {
        val value = saved()
        try {
            store().save(value)
            val file = File(context.noBackupFilesDir, "pickup-session.bin")
            val bytes = file.readBytes()
            assertTrue(!String(bytes, Charsets.ISO_8859_1).contains(value.driverToken))
            assertTrue(!String(bytes, Charsets.ISO_8859_1).contains(value.navigationReference!!))
            assertTrue(store().load() == value)
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            file.writeBytes(bytes)
            assertTrue(runCatching { store().load() }.isFailure)
        } finally { store().clear() }
    }

    @Test fun restoresOnlyAfterServerCheckAndDeletesEndedSession() {
        val value = saved()
        try {
            store().save(value)
            ReplyServer(200, """{"status":"ACTIVE","place":{"label":"예시 장소","latitude":37,"longitude":127},"participant":null}""").use { server ->
                val controller = controller(server)
                main.runOnMainSync { controller.restoreSession(); assertTrue(!controller.canCreate && !controller.canArrive) }
                await { controller.online && controller.status == "ACTIVE" }
                assertTrue(controller.place != null)
                assertTrue(controller.navigationReference == value.navigationReference)
                main.runOnMainSync {
                    assertTrue(controller.needsSharingConfirmation)
                    val reader = DestinationReader(context)
                    val now = SystemClock.elapsedRealtime()
                    reader.telemetry.location(37.0, 127.0, now, java.time.Instant.now())
                    reader.telemetry.destination(controller.place!!, 120, now, java.time.Instant.now())
                    controller.syncVehicle(reader)
                    assertTrue(store().load()?.vehicleSequence == 9L)
                    controller.confirmRecoveredSharing()
                    assertTrue(!controller.needsSharingConfirmation)
                }
                assertTrue(store().load()?.vehicleSequence == 9L)
            }
            ReplyServer(200, """{"status":"COMPLETED","place":null,"participant":null}""").use { server ->
                val controller = controller(server)
                main.runOnMainSync { controller.restoreSession() }
                await { controller.status == "COMPLETED" && !controller.busy }
                assertTrue(controller.place == null && controller.navigationReference == null && store().load() == null)
            }
        } finally { store().clear() }
    }

    @Test fun temporaryFailureKeepsSessionAndRetryRestoresIt() {
        val value = saved()
        try {
            store().save(value)
            ReplyServer(503, "{}").use { server ->
                val controller = controller(server)
                main.runOnMainSync { controller.restoreSession() }
                await { controller.status == "RESTORING" && !controller.online && controller.message.contains("다시") }
                assertTrue(store().load() == value && !controller.canCreate && !controller.canArrive)
                server.reply.set(200 to """{"status":"ACTIVE","place":{"label":"예시 장소","latitude":37,"longitude":127}}""")
                main.runOnMainSync { controller.refresh() }
                await { controller.online && controller.status == "ACTIVE" }
                main.runOnMainSync {
                    assertTrue(controller.needsSharingConfirmation)
                    val reader = DestinationReader(context)
                    val now = SystemClock.elapsedRealtime()
                    reader.telemetry.location(37.0, 127.0, now, java.time.Instant.now())
                    reader.telemetry.destination(controller.place!!, 180, now, java.time.Instant.now())
                    controller.syncVehicle(reader)
                    // A successful retry must not silently resume uploading.
                    assertTrue(store().load()?.vehicleSequence == value.vehicleSequence)
                }
            }
        } finally { store().clear() }
    }

    @Test fun rejectedCredentialsAndCorruptCiphertextAreRemoved() {
        try {
            for (status in listOf(401, 403, 404, 410)) {
                store().save(saved())
                ReplyServer(status, "{}").use { server ->
                    val controller = controller(server)
                    main.runOnMainSync { controller.restoreSession() }
                    await { controller.status == "EXPIRED" && !controller.busy }
                    assertTrue(store().load() == null && controller.place == null)
                }
            }
            File(context.noBackupFilesDir, "pickup-session.bin").writeBytes(byteArrayOf(1, 2))
            ReplyServer(200, "{}").use { server ->
                val controller = controller(server)
                main.runOnMainSync { controller.restoreSession() }
                await { controller.canCreate }
                assertTrue(store().load() == null)
            }
        } finally { store().clear() }
    }

    private fun controller(server: ReplyServer): PickupController {
        lateinit var result: PickupController
        main.runOnMainSync { result = PickupController(PickupApiClient(server.origin), store()) }
        return result
    }

    private class ReplyServer(status: Int, body: String) : AutoCloseable {
        private val socket = ServerSocket(0)
        val origin = "http://127.0.0.1:${socket.localPort}"
        val reply = AtomicReference(status to body)
        private val thread = Thread {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { client ->
                        val reader = client.getInputStream().bufferedReader()
                        while (reader.readLine()?.takeIf { it.isNotEmpty() } != null) { }
                        val (code, value) = reply.get()
                        val bytes = value.toByteArray(Charsets.UTF_8)
                        client.getOutputStream().apply {
                            write("HTTP/1.1 $code Response\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes); flush()
                        }
                    }
                } catch (_: Exception) { }
            }
        }.apply { isDaemon = true; start() }
        override fun close() { socket.close(); thread.join(1000) }
    }
}
