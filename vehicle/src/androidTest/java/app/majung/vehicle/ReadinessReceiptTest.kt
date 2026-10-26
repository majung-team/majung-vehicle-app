package app.majung.vehicle

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real controller and HTTP client; no navigation SDK or driver-read claim. */
class ReadinessReceiptTest {
    private val main get() = InstrumentationRegistry.getInstrumentation()

    @Test fun receivesAppliedSnapshotsRetriesFailureAndSkipsAlreadyConfirmedSequences() {
        Server().use { server ->
            val store = MemoryPickupSessionStore().apply {
                save(SavedPickupSession(UUID.randomUUID().toString(), "A".repeat(43), null, 0))
            }
            lateinit var controller: PickupController
            main.runOnMainSync {
                controller = PickupController(PickupApiClient(server.origin), store)
                controller.restoreSession()
            }
            await { controller.online && server.attempts.get() == 1 }
            assertEquals("READY", controller.readiness)
            assertEquals(0, server.received.get()) // First acknowledgement failed.
            main.runOnMainSync { controller.refresh() }
            await { server.received.get() == 1 }
            // The next GET reports the persisted confirmation, so no duplicate write is needed.
            main.runOnMainSync { controller.refresh() }
            await { server.reads.get() >= 3 && controller.online }
            SystemClock.sleep(150)
            assertEquals(2, server.attempts.get())
            server.sequence.set(2)
            main.runOnMainSync { controller.refresh() }
            await { server.received.get() == 2 }
            assertEquals("PREPARING", controller.readiness)
            assertTrue(server.correctPassenger)
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
        throw AssertionError("Readiness receipt timed out")
    }

    private class Server : AutoCloseable {
        private val socket = ServerSocket(0)
        val origin = "http://127.0.0.1:${socket.localPort}"
        private val participant = UUID.randomUUID().toString()
        val sequence = AtomicInteger(1)
        val received = AtomicInteger(0)
        val attempts = AtomicInteger(0)
        val reads = AtomicInteger(0)
        @Volatile var correctPassenger = true
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
                    val code: Int
                    if (request.startsWith("PUT") && request.contains("/readiness-receipt")) {
                        val body = JSONObject(String(chars))
                        correctPassenger = correctPassenger && body.getString("participantId") == participant
                        code = if (attempts.incrementAndGet() == 1) 503 else 200
                        if (code == 200) received.set(body.getInt("sequence"))
                    } else { reads.incrementAndGet(); code = 200 }
                    val body = """{"status":"ACTIVE","place":{"label":"시험 장소","latitude":37,"longitude":127},"participant":{"participantId":"$participant","readiness":"${if (sequence.get() == 1) "READY" else "PREPARING"}","readinessSequence":${sequence.get()},"readinessReceivedSequence":${received.get()}}}"""
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
