package app.majung.vehicle

internal class PickupVoiceSection(private val app: VehicleApplication, views: VehicleViews) {
    init { views.addView(views.label("음성 안내", 24f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }) }
    private val status = views.label("", 14f).apply { id = R.id.voice_status }
    private val toggle = views.button("음성 안내 켜기") {
        app.voice.setEnabled(!app.voice.enabled)
    }.apply { id = R.id.toggle_voice }

    init { views.addView(status) }

    fun render() {
        status.updateText(app.voice.message)
        toggle.updateText(if (app.voice.enabled) "음성 안내 끄기" else "음성 안내 켜기")
    }
}
