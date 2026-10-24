package app.majung.vehicle

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.net.ServerSocket
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** HTTP fixtures exercise the real client/controller; no SDK or clock-setting claim. */
class VehicleSharingFeedbackIntegrationTest {
    private val main get() = InstrumentationRegistry.getInstrumentation()

    @Test fun staleFutureFreshAndInvalidatedRepliesReachController() {
        Server().use { server ->
            val store = MemoryPickupSessionStore().apply {
                save(SavedPickupSession(UUID.randomUUID().toString(), "A".repeat(43), null, 0))
            }
            lateinit var controller: PickupController
            val reader = DestinationReader(ApplicationProvider.getApplicationContext<VehicleApplication>())
            main.runOnMainSync {
                controller = PickupController(PickupApiClient(server.origin), store)
                controller.restoreSession()
            }
            await { controller.needsSharingConfirmation }
            main.runOnMainSync { controller.confirmRecoveredSharing() }
            fun send(status: Int, body: String, expected: String, ageSeconds: Long = 0) {
                server.reply.set(status to body)
                main.runOnMainSync {
                    val now = SystemClock.elapsedRealtime()
                    val observed = Instant.now().minusSeconds(ageSeconds)
                    reader.telemetry.location(37.0, 127.0, now, observed)
                    reader.telemetry.destination(controller.place!!, 120, now, observed)
                }
                val deadline = SystemClock.elapsedRealtime() + 8000
                while (SystemClock.elapsedRealtime() < deadline) {
                    main.runOnMainSync { controller.syncVehicle(reader) }
                    var done = false
                    main.runOnMainSync { done = controller.vehicleMessage.contains(expected) }
                    if (done) return
                    SystemClock.sleep(50)
                }
                throw AssertionError("Expected feedback: $expected")
            }
            send(200, """{"freshness":"STALE","validForMillis":0}""", "공유하지 않아요", 112)
            assertTrue(java.time.Duration.between(Instant.parse(server.sourceTime.get()), Instant.now()).seconds in 112..114)
            send(400, """{"code":"FUTURE_SOURCE_TIME"}""", "자동 날짜·시간", -112)
            send(200, """{"freshness":"FRESH","validForMillis":30000}""", "공유하고 있어요")
            server.reply.set(200 to """{"freshness":"WAITING","validForMillis":0}""")
            main.runOnMainSync { reader.telemetry.invalidate(); controller.syncVehicle(reader) }
            await { controller.vehicleMessage.contains("지웠어요") }
            assertTrue(server.deleted)
        }
    }

    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < end) {
            var done = false
            main.runOnMainSync { done = condition() }
            if (done) return
            SystemClock.sleep(30)
        }
        throw AssertionError("Controller feedback timed out")
    }

    private class Server : AutoCloseable {
        private val socket = ServerSocket(0)
        val origin = "http://127.0.0.1:${socket.localPort}"
        val reply = AtomicReference(200 to "{}")
        val sourceTime = AtomicReference("")
        @Volatile var deleted = false
        private val thread = Thread {
            while (!socket.isClosed) try {
                socket.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    val request = reader.readLine()
                    var length = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    val chars = CharArray(length)
                    var read = 0
                    while (read < length) { val n = reader.read(chars, read, length - read); if (n < 0) break; read += n }
                    if (request.startsWith("PUT")) sourceTime.set(org.json.JSONObject(String(chars)).getString("sourceTime"))
                    if (request.startsWith("DELETE")) deleted = true
                    val (code, body) = if (request.startsWith("GET")) 200 to
                        """{"status":"ACTIVE","place":{"label":"예시 장소","latitude":37,"longitude":127}}""" else reply.get()
                    val bytes = body.toByteArray()
                    client.getOutputStream().apply {
                        write("HTTP/1.1 $code Response\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        override fun close() { socket.close(); thread.join(1000) }
    }
}
