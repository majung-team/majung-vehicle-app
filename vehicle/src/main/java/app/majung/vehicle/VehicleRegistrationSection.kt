package app.majung.vehicle

import android.widget.TextView

internal class VehicleRegistrationSection(private val app: VehicleApplication, private val views: VehicleViews) {
    private val text: TextView = views.label("", 16f).also(views::addView)
    private val refresh = views.button("차량 연결 상태 다시 확인") { app.registration.refresh() }
    fun render() {
        text.updateText(app.registration.message)
        refresh.isEnabled = !app.registration.busy
    }
}
