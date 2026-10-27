package app.majung.vehicle

import android.app.AlertDialog
import android.view.View
import kotlin.math.ceil

internal class RequestPickupSection(private val app: VehicleApplication, private val views: VehicleViews) {
    private val title = views.label("", 28f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }.also(views::addView)
    private val details = views.label("", 21f).also(views::addView)
    private val message = views.label("", 19f).also(views::addView)
    private val progress = android.widget.ProgressBar(views.context, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }.also(views::addView)
    private val start = views.button("마중 시작 · 위치 공유", primary = true) { app.requestFlow.confirm(views.canInteract) }
    private val retry = views.button("경로 준비 다시 시도") { app.requestFlow.ride?.id?.let(app.requestFlow::begin) }
    private val cancel = views.button("준비 그만하기") {
        AlertDialog.Builder(views.context).setTitle("경로 준비를 그만할까요?")
            .setMessage("마중 요청은 대기 목록에 남아요. 이미 추가한 내비 경유지는 그대로 유지해요.")
            .setPositiveButton("준비 그만하기") { _, _ -> if (views.canInteract) app.requestFlow.cancel() }
            .setNegativeButton("계속 준비", null).show().also(views::trackDialog)
    }
    fun render() {
        val flow = app.requestFlow
        val ride = flow.ride
        title.updateText(ride?.let { "${it.nickname} 님께 마중 가기" } ?: "마중 준비")
        val seconds = ride?.place?.let { app.destination.currentStops()?.resolve(it)?.durationSeconds }
        details.updateText(ride?.let { "만날 장소 · ${it.place.label}" + if (seconds != null && flow.stage == RequestPickupFlow.Stage.READY) "\n약 ${ceil(seconds / 60.0).toInt().coerceAtLeast(1)}분 후 도착 예정" else "" } ?: "")
        message.updateText(flow.message)
        progress.visibility = if (flow.stage in setOf(RequestPickupFlow.Stage.PREPARING, RequestPickupFlow.Stage.STARTING)) View.VISIBLE else View.GONE
        start.visibility = if (flow.stage == RequestPickupFlow.Stage.READY) View.VISIBLE else View.GONE
        start.isEnabled = flow.stage == RequestPickupFlow.Stage.READY && !app.account.busy
        retry.visibility = if (flow.stage == RequestPickupFlow.Stage.FAILED) View.VISIBLE else View.GONE
        cancel.isEnabled = flow.stage != RequestPickupFlow.Stage.STARTING
    }
}
