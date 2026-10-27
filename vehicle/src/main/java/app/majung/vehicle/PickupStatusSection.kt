package app.majung.vehicle

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.ceil

internal class PickupStatusSection(private val app: VehicleApplication, private val views: VehicleViews) {
    private val connectionText = views.label("", 16f).apply {
        id = R.id.connection_status; maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val pickupText = views.label("", 28f).apply {
        id = R.id.pickup_details; minLines = 1; maxLines = 2
        setTextColor(Color.rgb(36,79,115))
    }
    private val passenger = views.label("",20f).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }
    private val readiness = views.label("", 32f).apply { setTypeface(typeface, Typeface.BOLD); minLines = 1; maxLines = 2 }
    private val eta = views.label("", 46f).apply {
        setTypeface(typeface, Typeface.BOLD); setTextColor(Color.rgb(36,79,115)); gravity = Gravity.END
        minLines = 1; maxLines = 2
    }
    private val sharing = views.label("", 16f).apply { minLines = 2; maxLines = 3 }
    private val problem = views.label("", 17f).apply { setTextColor(Color.rgb(145,81,27)) }
    init {
        views.addView(connectionText)
        val card = LinearLayout(views.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dp,8.dp,8.dp,8.dp)
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = 16.dp.toFloat()
            }
        }
        card.addView(pickupText)
        card.addView(passenger)
        val summary = LinearLayout(views.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        summary.addView(readiness,LinearLayout.LayoutParams(0,-2,1f))
        summary.addView(eta,LinearLayout.LayoutParams(0,-2,1f))
        card.addView(summary); card.addView(sharing); card.addView(problem)
        views.addView(card)
    }
    fun render() {
        val pickup = app.pickup
        connectionText.updateText(when {
            pickup.busy -> "요청 처리 중…"
            pickup.pickupId == null -> ""
            pickup.online -> "서버 연결됨"
            else -> "연결을 다시 확인하고 있어요"
        })
        pickupText.updateText(pickup.place?.label ?: "이번 픽업")
        passenger.updateText(pickup.participantName?.let { "$it 님과 만나요" } ?: "탑승자 연결 대기")
        readiness.updateText(if (!pickup.online && pickup.status in setOf("ACTIVE", "ARRIVED")) "마지막 준비 상태\n연결 확인 중" else when(pickup.status) {
            "INVITING", "WAITING_APPROVAL" -> "탑승자를\n기다리고 있어요"
            "ARRIVED" -> "도착했어요"
            "COMPLETED" -> "탑승 완료"
            "CANCELLED", "EXPIRED" -> "공유 종료"
            else -> when(pickup.readiness) {
                "READY" -> "탑승자\n준비 완료"
                "PREPARING" -> "탑승자가\n준비 중이에요"
                else -> "준비 상태를\n기다리고 있어요"
            }
        })
        val seconds = pickup.place?.let { app.destination.telemetry.current(it,SystemClock.elapsedRealtime())?.durationSeconds }
        eta.updateText(when {
            pickup.status == "ARRIVED" -> "탑승\n확인 대기"
            pickup.status !in setOf("ACTIVE","ARRIVED") -> ""
            !pickup.online -> "연결\n확인 중"
            pickup.needsSharingConfirmation || !app.sharingRunning -> "공유\n시작 대기"
            seconds == null -> "도착 시간\n확인 중"
            seconds < 60 -> "곧\n도착"
            else -> "약 ${ceil(seconds/60.0).toInt()}분"
        })
        sharing.updateText(when {
            !pickup.online -> "서버 연결이 끊겼어요. 이전 도착 시간을 표시하지 않고 연결을 다시 확인해요."
            pickup.needsSharingConfirmation -> "계속하기를 누르기 전에는 위치를 공유하지 않아요."
            pickup.status == "ARRIVED" -> "탑승할 때까지 위치 공유를 유지해요. 탑승했다면 완료해주세요."
            pickup.status == "ACTIVE" && app.sharingRunning -> pickup.vehicleMessage
            pickup.status == "ACTIVE" -> "위치 공유를 시작하면 탑승자가 도착 시간을 확인할 수 있어요."
            pickup.status == "INVITING" -> "아래 QR을 운전자 휴대전화로 열고, 탑승자에게 초대 링크를 보내주세요."
            else -> "픽업이 끝나면 위치 공유도 끝나요."
        })
        problem.updateText(pickup.message)
        problem.visibility = if(pickup.message.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
    }
    private val Int.dp: Int get() = (this * views.context.resources.displayMetrics.density).toInt()
}
