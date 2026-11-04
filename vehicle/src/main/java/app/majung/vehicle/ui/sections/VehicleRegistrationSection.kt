package app.majung.vehicle.ui.sections


import app.majung.vehicle.*
import app.majung.vehicle.ui.*
import app.majung.vehicle.ui.sections.*
import app.majung.vehicle.navi.*
import app.majung.vehicle.domain.*
import app.majung.vehicle.data.api.*
import app.majung.vehicle.data.storage.*
import app.majung.vehicle.util.*
import app.majung.vehicle.service.*

import android.widget.TextView

internal class VehicleRegistrationSection(private val app: VehicleApplication, private val views: VehicleViews) {
    init { views.addView(views.label("차량 연결", 24f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }) }
    private val text: TextView = views.label("", 16f).also(views::addView)
    private val refresh = views.button("차량 연결 상태 다시 확인") { app.registration.refresh() }
    fun render() {
        text.updateText(app.registration.message + if (app.registration.vehicleToken != null) "\n이 앱과 운전자 계정을 연결한 상태예요." else "")
        refresh.isEnabled = !app.registration.busy
    }
}
