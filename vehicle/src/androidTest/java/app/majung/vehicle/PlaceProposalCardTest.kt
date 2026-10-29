package app.majung.vehicle

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Synthetic view states only; no production API calls or navigation changes. */
class PlaceProposalCardTest {
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun pendingProposalRemainsVisibleOfflineAndCannotBeConfirmedWithoutRouteProof() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as VehicleApplication
        instrumentation.runOnMainSync {
            val original = app.pickup
            val controller = PickupController()
            fun assign(name: String, value: Any?) = PickupController::class.java.getDeclaredField(name).apply {
                isAccessible = true
            }.set(controller, value)
            val field = VehicleApplication::class.java.getDeclaredField("pickup").apply { isAccessible = true }
            try {
                assign("status", "ACTIVE")
                assign("place", PickupPlace("기존 만날 장소", 37.49, 127.03))
                assign("placeProposal", PickupPlace("새로 제안한 장소", 37.5, 127.04))
                assign("proposalId", "synthetic-proposal")
                assign("online", true)
                field.set(app, controller)
                val root = LinearLayout(app)
                val section = PickupParticipantSection(app, VehicleViews(app, root, {}))
                section.render()
                val all = descendants(root)
                val title = all.filterIsInstance<TextView>().single { it.text == "탑승자가 장소 변경을 요청했어요" }
                assertEquals(View.VISIBLE, (title.parent as View).visibility)
                assertTrue(all.filterIsInstance<TextView>().any { it.text.contains("현재 약속 · 기존 만날 장소") && it.text.contains("제안 장소 · 새로 제안한 장소") })
                val review = all.filterIsInstance<Button>().single { it.text == "1. 제안 장소로 경로 확인" }
                val confirm = all.filterIsInstance<Button>().single { it.text == "2. 새 장소 확정" }
                assertTrue(review.isEnabled)
                assertEquals(View.GONE, confirm.visibility)
                assertFalse(confirm.isEnabled)
                assign("online", false)
                section.render()
                assertEquals(View.VISIBLE, (title.parent as View).visibility)
                assertFalse(review.isEnabled)
                assertTrue(all.filterIsInstance<TextView>().any { it.text.contains("연결되면 제안을 확인") })
                assign("placeProposal", null)
                assign("proposalId", null)
                section.render()
                assertEquals(View.GONE, (title.parent as View).visibility)
            } finally {
                field.set(app, original)
            }
        }
    }
}
