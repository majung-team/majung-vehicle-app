package app.majung.vehicle

import android.content.Context
import android.os.SystemClock
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.net.ServerSocket
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PickupVoiceGuideTest {
    private val main get() = InstrumentationRegistry.getInstrumentation()

    @Test fun serverChangesProduceOneNoticeAndStopWhenConnectionOrPickupEnds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("voice-instrumentation-only", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().clear().commit())
        Fixture().use { server ->
            val output = RecordingOutput()
            lateinit var pickup: PickupController
            lateinit var guide: PickupVoiceGuide
            main.runOnMainSync {
                pickup = PickupController(PickupApiClient(server.origin), MemoryPickupSessionStore().apply {
                    save(SavedPickupSession("fixture-pickup", "T".repeat(43), null, 0, null))
                })
                guide = PickupVoiceGuide(context, pickup, preferences) { output }
                pickup.restoreSession()
            }
            try {
                await { pickup.status == "ACTIVE" && pickup.online }
                main.runOnMainSync { pickup.confirmRecoveredSharing(); guide.setEnabled(true) }
                assertTrue(preferences.getBoolean("enabled", false))
                assertTrue(output.notices.isEmpty())
                server.ready = true
                refresh(pickup) { pickup.readiness == "READY" }
                assertEquals(listOf(PickupVoicePolicy.Notice.READY.text), output.notices)
                refresh(pickup) { pickup.readiness == "READY" }
                assertEquals(1, output.notices.size)
                server.status = "ARRIVED"
                refresh(pickup) { pickup.status == "ARRIVED" }
                assertEquals(PickupVoicePolicy.Notice.ARRIVED.text, output.notices.last())
                server.fail.set(true)
                refresh(pickup) { !pickup.online }
                assertFalse(output.playing)
                server.fail.set(false)
                refresh(pickup) { pickup.online }
                assertEquals(2, output.notices.size)
                server.status = "COMPLETED"
                refresh(pickup) { pickup.status == "COMPLETED" }
                assertFalse(output.playing)
                main.runOnMainSync { guide.setEnabled(false) }
                assertTrue(output.closed)
                assertFalse(preferences.getBoolean("enabled", true))
            } finally {
                main.runOnMainSync { guide.close() }
                assertTrue(preferences.edit().clear().commit())
            }
        }
    }

    @Test fun unavailableOrInitializingOutputNeverQueuesOldSpeech() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("voice-unavailable-only", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().clear().putBoolean("enabled", true).commit())
        Fixture().use { server ->
            val output = RecordingOutput().apply { ready = false }
            lateinit var pickup: PickupController
            lateinit var guide: PickupVoiceGuide
            main.runOnMainSync {
                pickup = PickupController(PickupApiClient(server.origin), MemoryPickupSessionStore().apply {
                    save(SavedPickupSession("fixture-pickup", "T".repeat(43), null, 0, null))
                })
                guide = PickupVoiceGuide(context, pickup, preferences) { output }
                pickup.restoreSession()
            }
            try {
                await { pickup.status == "ACTIVE" && pickup.online }
                main.runOnMainSync { pickup.confirmRecoveredSharing() }
                server.ready = true
                refresh(pickup) { pickup.readiness == "READY" }
                main.runOnMainSync { output.ready = true }
                refresh(pickup) { pickup.readiness == "READY" }
                assertTrue(output.notices.isEmpty())
            } finally {
                main.runOnMainSync { guide.close() }
                assertTrue(preferences.edit().clear().commit())
            }
        }
    }

    @Test fun pleosOnDeviceSpeechInitializesStartsAndCompletes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        lateinit var output: PleosPickupVoiceOutput
        var started = false
        var completed = false
        main.runOnMainSync {
            output = PleosPickupVoiceOutput(context) {
                if (output.message == "음성 안내 중") started = true
                if (started && output.message.startsWith("음성 안내 켜짐")) completed = true
            }
        }
        try {
            await { output.message != "음성 안내 준비 중" }
            assertTrue(output.message, output.ready)
            main.runOnMainSync { output.speak(PickupVoicePolicy.Notice.READY.text) }
            await { started && completed }
            val report = JSONObject().put("mode", "ON_DEVICE").put("initialized", true)
                .put("playbackStarted", started).put("playbackCompleted", completed).put("physicalSpeakerChecked", false)
            File(context.filesDir, "voice-sdk-summary.json").writeText(report.toString(2))
        } finally { main.runOnMainSync { output.close() } }
    }

    @Test fun parkedScreenTogglesVoiceAndSavesChoice() {
        val app = ApplicationProvider.getApplicationContext<VehicleApplication>()
        val preferences = app.getSharedPreferences("voice-screen-only", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().clear().commit())
        val field = VehicleApplication::class.java.getDeclaredField("voice").apply { isAccessible = true }
        val original = app.voice
        lateinit var guide: PickupVoiceGuide
        main.runOnMainSync {
            guide = PickupVoiceGuide(app, app.pickup, preferences) { RecordingOutput() }
            field.set(app, guide)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var toggle: Button
                scenario.onActivity { toggle = it.findViewById(R.id.toggle_voice) }
                await { toggle.isEnabled }
                scenario.onActivity {
                    val button = it.findViewById<Button>(R.id.toggle_voice)
                    button.performClick()
                    assertTrue(guide.enabled)
                    assertEquals("음성 안내 끄기", button.text.toString())
                    assertTrue(preferences.getBoolean("enabled", false))
                    button.performClick()
                    assertFalse(guide.enabled)
                    assertEquals("음성 안내 켜기", button.text.toString())
                    val status=it.findViewById<android.widget.TextView>(R.id.voice_status)
                    status.requestRectangleOnScreen(android.graphics.Rect(0, 0, status.width, status.height), true)
                }
                if (InstrumentationRegistry.getArguments().getString("captureVoice") == "true") {
                    main.waitForIdleSync()
                    scenario.onActivity { activity ->
                        val view = activity.window.decorView
                        val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                        view.draw(android.graphics.Canvas(image))
                        File(app.filesDir, "voice-controls.png").outputStream().use {
                            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                }
            }
        } finally {
            main.runOnMainSync { field.set(app, original); guide.close() }
            assertTrue(preferences.edit().clear().commit())
        }
    }

    private fun refresh(pickup: PickupController, condition: () -> Boolean) {
        val completed = CountDownLatch(1)
        val listener: () -> Unit = { completed.countDown() }
        main.runOnMainSync { pickup.addObserver(listener); pickup.refresh() }
        try {
            assertTrue("Server refresh did not finish", completed.await(10, TimeUnit.SECONDS))
            await(condition)
        } finally { main.runOnMainSync { pickup.removeObserver(listener) } }
    }

    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < until) {
            var done = false
            main.runOnMainSync { done = condition() }
            if (done) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Voice test did not reach expected state")
    }

    private class RecordingOutput : PickupVoiceOutput {
        override var ready = true
        override val message = "시험 출력"
        val notices = mutableListOf<String>()
        var playing = false
        var closed = false
        override fun speak(text: String) { notices.add(text); playing = true }
        override fun stop() { playing = false }
        override fun close() { stop(); closed = true }
    }

    private class Fixture : AutoCloseable {
        val fail = AtomicBoolean(false)
        @Volatile var ready = false
        @Volatile var status = "ACTIVE"
        private val server = ServerSocket(0)
        val origin = "http://127.0.0.1:${server.localPort}"
        private val running = AtomicBoolean(true)
        private val worker = Thread {
            while (running.get()) try { server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                reader.readLine()
                while (!reader.readLine().isNullOrBlank()) { }
                val body = JSONObject().put("status", status).put("participant", JSONObject()
                    .put("participantId", "fixture-passenger").put("displayName", "시험")
                    .put("readiness", if (ready) "READY" else "PREPARING")).toString().toByteArray()
                val code = if (fail.get()) 503 else 200
                socket.getOutputStream().apply {
                    write("HTTP/1.1 $code Test\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(body); flush()
                }
            } } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        override fun close() { running.set(false); server.close(); worker.join(1000) }
    }
}
