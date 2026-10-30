package app.majung.vehicle

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Synthetic display states. Does not create pickups, send invitations or change server data. */
class VehicleReadabilityTest {
    @Test fun endedPickupAndSettingsStayReadableAtCurrentFontScale() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as VehicleApplication
        val previousNotice = app.sharingNotice.accepted
        val names = listOf("pickup", "registration", "account", "destination")
        val fields = names.map { VehicleApplication::class.java.getDeclaredField(it).apply { isAccessible = true } }
        val originals = fields.map { it.get(app) }
        fun set(target: Any, name: String, value: Any?) {
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
        }
        fun descendants(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        val api = PickupApiClient("http://127.0.0.1:1")
        val store = object : VehicleCredentialStore {
            override fun load(): VehicleCredential? = null
            override fun save(credential: VehicleCredential) { }
            override fun clear() { }
        }
        val registration = VehicleRegistrationController(api, store)
        val account = VehicleAccountController(api, registration)
        val pickup = PickupController(api, MemoryPickupSessionStore(), registration)
        fun capture(name: String) {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(300)
            val scale = app.resources.configuration.fontScale
            File(app.filesDir, "readability-$name-$scale.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        try {
            app.sharingNotice.accept()
            instrumentation.runOnMainSync {
                fields.zip(listOf(pickup, registration, account, DestinationReader(app))).forEach { (field, value) -> field.set(app, value) }
                set(registration, "development", true)
                set(registration, "registrationChecked", true)
                set(pickup, "pickupId", "display-only")
                set(pickup, "status", "COMPLETED")
                set(pickup, "online", true)
                set(pickup, "place", PickupPlace("함께 만날 장소", 37.5, 127.0))
                set(pickup, "message", "픽업 공유가 종료됐어요.")
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                app.startActivity(android.content.Intent(app, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                val deadline = SystemClock.elapsedRealtime() + 15000
                var shown = false
                do {
                    scenario.onActivity { shown = it.hasWindowFocus() && it.findViewById<View>(R.id.parked_controls).isShown }
                    if (!shown) SystemClock.sleep(100)
                } while (!shown && SystemClock.elapsedRealtime() < deadline)
                assertTrue("Parked surface must appear", shown)
                fun checkText(activity: MainActivity) {
                    val texts = descendants(activity.findViewById(R.id.parked_controls)).filterIsInstance<TextView>().filter { it.isShown && it.text.isNotBlank() }
                    assertTrue(texts.isNotEmpty())
                    texts.forEach { view ->
                        val sp = android.util.TypedValue.deriveDimension(android.util.TypedValue.COMPLEX_UNIT_SP, view.textSize, view.resources.displayMetrics)
                        assertTrue("Small text: ${view.text}", sp >= 23.5f)
                        if (view is Button) {
                            assertTrue("Short touch target: ${view.text}", view.height >= (64 * view.resources.displayMetrics.density).toInt())
                            val layout = view.layout
                            assertTrue("Clipped button: ${view.text}", layout != null && (0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 } && layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
                        }
                    }
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    checkText(activity)
                    val texts = descendants(activity.findViewById(R.id.parked_controls)).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
                    assertFalse(texts.any { it.contains("탑승자 연결 대기") })
                    assertTrue(texts.contains("차량 위치와 도착 시간 공유가 끝났어요."))
                }
                capture("completed")
                scenario.onActivity { activity -> descendants(activity.window.decorView).filterIsInstance<Button>().single { it.text == "설정" }.performClick() }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(1000)
                scenario.onActivity { checkText(it) }
                capture("settings")
                scenario.onActivity { activity ->
                    set(account, "state", JSONObject().put("status", "LINKED").put("majungId", "MJ-DISPLAY"))
                    VehiclePeopleSection(app, VehicleViews(activity, android.widget.LinearLayout(activity), {}), {}).find()
                }
                instrumentation.waitForIdleSync()
                capture("search")
            }
        } finally {
            instrumentation.runOnMainSync { fields.zip(originals).forEach { (field, value) -> field.set(app, value) } }
            if (previousNotice) app.sharingNotice.accept() else app.sharingNotice.withdraw()
        }
    }
}
