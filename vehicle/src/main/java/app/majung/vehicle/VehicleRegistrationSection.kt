package app.majung.vehicle

import android.widget.TextView

internal class VehicleRegistrationSection(private val app: VehicleApplication, private val views: VehicleViews) {
    private val text: TextView = views.label("", 16f).also(views::addView)
    private val refresh = views.button("차량 연결 상태 다시 확인") { app.registration.refresh() }
    fun render() {
        text.updateText(app.registration.message + if (app.registration.vehicleToken != null) "\n마중 앱 연결 정보예요. 실제 차량의 차대번호(VIN) 인증은 아니에요." else "")
        refresh.isEnabled = !app.registration.busy
    }
}
