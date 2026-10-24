package app.majung.vehicle

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleArrivalIntegrationTest {
    private val main get() = InstrumentationRegistry.getInstrumentation()
    @Test fun matchedArrivalRetriesLostAcknowledgementAndDoesNotRepeatAfterArrived() {
        ArrivalServer().use { server ->
            val store = MemoryPickupSessionStore().apply {
                save(SavedPickupSession(UUID.randomUUID().toString(), "A".repeat(43), null, 0))
            }
            lateinit var controller: PickupController
            main.runOnMainSync {
                controller = PickupController(PickupApiClient(server.origin), store)
                controller.restoreSession()
            }
            await { controller.needsSharingConfirmation }
            main.runOnMainSync {
                controller.confirmRecoveredSharing()
                controller.arriveFromSdk(PickupPlace("다른 장소", 38.0, 128.0))
            }
            SystemClock.sleep(100)
            assertEquals(0, server.arrivals.get())
            main.runOnMainSync { controller.arriveFromSdk(controller.place!!) }
            await { !controller.busy && !controller.online }
            assertEquals("ACTIVE", controller.status)
            assertTrue(controller.message.contains("연결하지 못했어요"))
            await { controller.status == "ARRIVED" }
            assertEquals(2, server.arrivals.get())
            main.runOnMainSync { controller.arriveFromSdk(controller.place!!) }
            SystemClock.sleep(100)
            assertEquals(2, server.arrivals.get())
        }
    }
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 9000
        while (SystemClock.elapsedRealtime() < until) {
            var done = false
            main.runOnMainSync { done = condition() }
            if (done) return
            SystemClock.sleep(30)
        }
        throw AssertionError("Arrival controller did not reach expected state")
    }
    private class ArrivalServer : AutoCloseable {
        private val socket = ServerSocket(0)
        val origin = "http://127.0.0.1:${socket.localPort}"
        val arrivals = AtomicInteger()
        private val thread = Thread {
            while (!socket.isClosed) try {
                socket.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    val request = reader.readLine()
                    while (reader.readLine()?.takeIf { it.isNotEmpty() } != null) { }
                    val attempt = if (request.startsWith("POST")) arrivals.incrementAndGet() else 0
                    val status = if (attempt == 1) 503 else 200
                    val body = if (status == 503) "{}" else
                        """{"status":"${if (attempt > 1) "ARRIVED" else "ACTIVE"}","place":{"label":"예시 장소","latitude":37,"longitude":127}}"""
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
