package app.majung.vehicle

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class SharingNoticeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as VehicleApplication
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun choiceIsVersionedAndSharingServiceRefusesBeforeAcceptance() {
        val previous = app.sharingNotice.accepted
        try {
            assertTrue(app.sharingNotice.withdraw())
            assertFalse(SharingNotice(app).accepted)
            instrumentation.runOnMainSync {
                app.startService(Intent(app, PickupSharingService::class.java))
            }
            SystemClock.sleep(300)
            assertFalse(app.sharingRunning)
            assertTrue(app.sharingNotice.accept())
            assertTrue(SharingNotice(app).accepted)
            assertTrue(app.sharingNotice.withdraw())
            assertFalse(SharingNotice(app).accepted)
        } finally {
            if (previous) app.sharingNotice.accept() else app.sharingNotice.withdraw()
        }
    }

    @Test fun firstUseShowsPurposeAndDoesNotSilentlyAccept() {
        val previous = app.sharingNotice.accepted
        try {
            app.sharingNotice.withdraw()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val deadline = SystemClock.elapsedRealtime() + 15000
                var visible = false
                do {
                    scenario.onActivity { activity ->
                        visible = descendants(activity.window.decorView).filterIsInstance<TextView>()
                            .any { it.isShown && it.text.toString() == "마중을 시작하기 전에" }
                    }
                    if (!visible) SystemClock.sleep(100)
                } while (!visible && SystemClock.elapsedRealtime() < deadline)
                assertTrue("Notice must appear while parked", visible)
                assertFalse(app.sharingNotice.accepted)
                scenario.onActivity { activity ->
                    val buttons = descendants(activity.window.decorView).filterIsInstance<Button>()
                    assertTrue(buttons.any { it.text == "지금은 사용하지 않기" })
                    buttons.first { it.text == "위치 공유 안내에 동의하고 시작" }.performClick()
                }
                assertTrue(app.sharingNotice.accepted)
            }
        } finally {
            if (previous) app.sharingNotice.accept() else app.sharingNotice.withdraw()
        }
    }
}
