package app.majung.vehicle

import android.app.AlertDialog
import android.widget.Button

internal class PickupCompletionSection(private val app: VehicleApplication, private val views: VehicleViews) {
    private val arriveButton: Button
    private val completeButton: Button
    private val cancelButton: Button
    private val nextButton: Button

    init {
        arriveButton = views.button("도착 알리기 · 공유 유지") {
            if (!views.canInteract || !app.pickup.canArrive) return@button
            AlertDialog.Builder(views.context).setMessage("약속 장소에 도착했나요? 탑승을 확인하기 전까지 위치 공유는 유지돼요.")
                .setPositiveButton("도착 알리기") { _, _ -> if (views.canInteract) app.pickup.arrive() }
                .setNegativeButton("돌아가기", null).show().also(views::trackDialog)
        }.apply { id = R.id.arrive_pickup }
        completeButton = views.button("탑승 확인 · 픽업 완료") {
            if (!views.canInteract || !app.pickup.canComplete) return@button
            AlertDialog.Builder(views.context).setMessage("탑승자가 차에 탔나요? 픽업을 완료하면 위치 공유가 종료돼요.")
                .setPositiveButton("탑승 확인하고 완료") { _, _ -> if (views.canInteract) app.pickup.complete() }
                .setNegativeButton("돌아가기", null).show().also(views::trackDialog)
        }.apply { id = R.id.complete_pickup }
        cancelButton = views.button("픽업 취소 · 공유 종료") {
            AlertDialog.Builder(views.context).setMessage("픽업을 취소하고 위치 공유를 종료할까요?")
                .setPositiveButton("픽업 취소") { _, _ -> if (views.canInteract) app.pickup.cancel() }
                .setNegativeButton("돌아가기", null).show().also(views::trackDialog)
        }
        cancelButton.id = R.id.cancel_pickup
        nextButton = views.button("새 픽업 준비") {
            app.pickup.prepareNextPickup()
            app.destination.useFinalDestination()
        }
    }

    fun render() {
        val pickup = app.pickup
        arriveButton.isEnabled = pickup.canArrive
        completeButton.isEnabled = pickup.canComplete
        cancelButton.isEnabled = pickup.canCancel
        nextButton.visibility = if (pickup.status in setOf("CANCELLED", "COMPLETED", "EXPIRED")) android.view.View.VISIBLE else android.view.View.GONE
    }
}
