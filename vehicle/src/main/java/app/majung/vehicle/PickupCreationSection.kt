package app.majung.vehicle

import android.os.SystemClock
import android.widget.Button
import android.widget.TextView

internal class PickupCreationSection(private val app: VehicleApplication, private val views: VehicleViews) {
    private val destinationText: TextView
    private val createButton: Button

    init {
        views.button("내비게이션 연결") { app.destination.connect() }
        views.button("선택한 목적지 다시 확인") { app.destination.query(); render() }
        destinationText = views.label("", 20f)
        views.addView(destinationText)
        createButton = views.button("이 장소로 픽업 만들기") {
            val selected = app.destination.selection.current(SystemClock.elapsedRealtime()) ?: return@button
            app.pickup.create(selected)
        }
    }

    fun render() {
        val place = app.destination.selection.current(SystemClock.elapsedRealtime())
        destinationText.updateText("${app.destination.status}\n\n${place?.label ?: "확인된 목적지가 없어요. 목적지를 다시 확인해주세요."}")
        createButton.isEnabled = place != null && app.pickup.canCreate
    }
}
