package app.majung.vehicle

import android.app.AlertDialog
import android.widget.Button

internal class PickupCompletionSection(private val app: VehicleApplication, private val views: VehicleViews) {
    private var nextRequested = false
    private val nextFeedback = views.label("", 18f).also(views::addView)
    private val arriveButton: Button
    private val completeButton: Button
    private val cancelButton: Button
    private val nextButton: Button

    init {
        arriveButton = views.button("도착 알리기", primary = true) {
            if (!views.canInteract || !app.pickup.canArrive) return@button
            AlertDialog.Builder(views.context).setMessage("약속 장소에 도착했나요? 탑승을 확인하기 전까지 위치 공유는 유지돼요.")
                .setPositiveButton("도착 알리기") { _, _ -> if (views.canInteract) app.pickup.arrive() }
                .setNegativeButton("돌아가기", null).show().also(views::trackDialog)
        }.apply { id = R.id.arrive_pickup }
        completeButton = views.button("탑승 확인 · 픽업 완료", primary = true) {
            if (!views.canInteract || !app.pickup.canComplete) return@button
            AlertDialog.Builder(views.context).setMessage("탑승자가 차에 탔나요? 픽업을 완료하면 위치 공유가 종료돼요.")
                .setPositiveButton("탑승 확인하고 완료") { _, _ -> if (views.canInteract) app.pickup.complete() }
                .setNegativeButton("돌아가기", null).show().also(views::trackDialog)
        }.apply {
            id = R.id.complete_pickup
        }
        cancelButton = views.button("픽업 취소 · 공유 종료") {
            AlertDialog.Builder(views.context).setMessage("픽업을 취소하고 위치 공유를 종료할까요?")
                .setPositiveButton("픽업 취소") { _, _ -> if (views.canInteract) app.pickup.cancel() }
                .setNegativeButton("돌아가기", null).show().also(views::trackDialog)
        }
        cancelButton.id = R.id.cancel_pickup
        nextButton = views.button("다음 픽업 준비", primary = true) {
            nextRequested = true
            app.account.refresh {
                if (!views.canInteract) return@refresh
                val queued = app.account.requests.filter { it.optString("status") == "QUEUED" }
                if (queued.isEmpty()) {
                    app.pickup.prepareNextPickup()
                    app.destination.useFinalDestination()
                } else AlertDialog.Builder(views.context).setTitle("다음에 누구를 만날까요?")
                    .setItems(queued.map { "${it.optString("nickname")} · ${it.getJSONObject("place").optString("label")}" }.toTypedArray()) { _, index ->
                        if (views.canInteract) app.requestFlow.begin(queued[index].getString("requestId"))
                    }.setNegativeButton("닫기", null).show().also(views::trackDialog)
            }
        }
    }

    fun render() {
        val pickup = app.pickup
        arriveButton.visibility = if (pickup.status == "ACTIVE") android.view.View.VISIBLE else android.view.View.GONE
        completeButton.visibility = if (pickup.status == "ARRIVED") android.view.View.VISIBLE else android.view.View.GONE
        cancelButton.visibility = if (pickup.canCancel) android.view.View.VISIBLE else android.view.View.GONE
        arriveButton.isEnabled = pickup.canArrive
        completeButton.isEnabled = pickup.canComplete
        cancelButton.isEnabled = pickup.canCancel
        val ended = pickup.status in setOf("CANCELLED", "COMPLETED", "EXPIRED")
        nextFeedback.visibility = if (ended && nextRequested && !app.account.requestsFresh) android.view.View.VISIBLE else android.view.View.GONE
        nextFeedback.updateText(if (app.account.busy) "다음 대기자를 확인하고 있어요." else app.account.message)
        val queued = app.account.requests.count { it.optString("status") == "QUEUED" }
        nextButton.updateText(if (queued > 0) "다음 대기자 확인 · ${queued}명" else "다음 픽업 준비")
        nextButton.isEnabled = !pickup.busy && !app.account.busy
        nextButton.visibility = if (pickup.status in setOf("CANCELLED", "COMPLETED", "EXPIRED")) android.view.View.VISIBLE else android.view.View.GONE
    }
}
