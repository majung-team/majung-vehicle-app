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

internal class PickupVoiceSection(private val app: VehicleApplication, views: VehicleViews) {
    init { views.addView(views.label("음성 안내", 24f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }) }
    private val status = views.label("", 16f).apply { id = R.id.voice_status }.also(views::addView)
    private val toggle = views.button("음성 안내 켜기") {
        app.voice.setEnabled(!app.voice.enabled)
    }.apply { id = R.id.toggle_voice }

    fun render() {
        status.updateText(app.voice.message)
        toggle.updateText(if (app.voice.enabled) "음성 안내 끄기" else "음성 안내 켜기")
    }
}
