package app.majung.vehicle

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ProgressBar
import android.view.View
import kotlin.math.ceil

/** The Figma draft is a visual reference; every place and ETA here comes from the SDK. */
internal class PickupCreationSection(private val app: VehicleApplication, private val views: VehicleViews, private val inviteConnected: () -> Unit = {}) {
    private val destinationText: TextView
    private val placeName: TextView
    private val routeDetails: TextView
    private val createButton: Button
    private val addWaypointButton: Button
    private val finalDestinationButton: Button
    private val retryButton: Button
    private val directInviteButton: Button
    private val actionHint: TextView
    private val progress: ProgressBar

    init {
        views.addView(views.label("1  장소 확인    →    2  탑승자 초대", 16f))
        destinationText = views.label("", 18f)
        views.addView(destinationText)
        progress = ProgressBar(views.context, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        views.addView(progress)
        val card = LinearLayout(views.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 18.dp, 24.dp, 18.dp)
            background = GradientDrawable().apply {
                setColor(Color.rgb(231, 238, 243))
                cornerRadius = 8.dp.toFloat()
                setStroke(1.dp, Color.rgb(36, 79, 115))
            }
        }
        placeName = views.label("장소 확인 중", 25f).apply {
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.rgb(32, 40, 46))
        }
        routeDetails = views.label("", 18f).apply { minLines = 1; maxLines = 3; setTextColor(Color.rgb(36, 79, 115)) }
        card.addView(placeName)
        card.addView(routeDetails)
        views.addView(card)
        directInviteButton = views.button("연결된 탑승자에게 초대") { inviteConnected() }.apply {
            id = R.id.pickup_invite_contact
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(36, 79, 115))
        }
        createButton = views.button("초대 링크 만들기 · QR로 공유") {
            val selected = app.destination.selection.current(SystemClock.elapsedRealtime()) ?: return@button
            if (app.destination.currentStops()?.resolve(selected) != null && !app.destination.addingWaypoint)
                app.pickup.create(selected, app.destination.referenceFor(selected))
        }.apply { id = R.id.create_pickup }
        actionHint = views.label("", 16f).also(views::addView)
        retryButton = views.button("내비 장소 다시 확인") { app.destination.retryPickupPreparation() }
        addWaypointButton = views.button("다른 장소에서 태우기 · 경유지 추가") {
            if (!views.canInteract || !app.pickup.canCreate) return@button
            app.destination.loadRecentPlaces { places ->
                if (!views.canInteract || !app.pickup.canCreate) return@loadRecentPlaces
                if (places.isEmpty()) {
                    AlertDialog.Builder(views.context).setMessage("확인할 수 있는 최근 장소가 없어요. 내비게이션에서 장소를 검색한 뒤 다시 확인해주세요.")
                        .setPositiveButton("확인", null).show().also(views::trackDialog)
                } else {
                    AlertDialog.Builder(views.context).setTitle("어디서 태울까요?")
                        .setItems(places.map { it.place.label }.toTypedArray()) { _, index ->
                            if (!views.canInteract) return@setItems
                            val place = places[index]
                            AlertDialog.Builder(views.context)
                                .setMessage("${place.place.label}을 픽업 경유지로 추가할까요? 기존 최종 목적지는 유지해요.")
                                .setPositiveButton("경유지로 추가") { _, _ ->
                                    if (views.canInteract && app.pickup.canCreate) app.destination.addPickupWaypoint(place)
                                    render()
                                }.setNegativeButton("돌아가기", null).show().also(views::trackDialog)
                        }.setNegativeButton("돌아가기", null).show().also(views::trackDialog)
                }
            }
        }.apply { id = R.id.add_pickup_waypoint }
        finalDestinationButton = views.button("경유지 대신 최종 목적지에서 만나기") { app.destination.useFinalDestination(); render() }
    }

    fun render() {
        val place = app.destination.selection.current(SystemClock.elapsedRealtime())
        val stops = app.destination.currentStops()
        val stop = place?.let { stops?.resolve(it) }
        val failure = app.destination.failure
        val adding = app.destination.addingWaypoint
        val ready = place != null && stop != null && app.pickup.canCreate && !adding
        progress.visibility = if (!ready && failure == null && !app.destination.waitingForGuidance) View.VISIBLE else View.GONE
        destinationText.updateText(when {
            app.pickup.busy -> "초대 링크를 만들고 있어요. 잠시만 기다려주세요."
            adding -> "경유지를 추가하고 있어요. 확인이 끝나면 초대할 수 있어요."
            ready -> "이 장소에서 만날 사람을 초대해주세요."
            failure != null -> failure.message
            else -> app.destination.preparationMessage
        })
        placeName.updateText(place?.label ?: if (failure != null) "내비 장소를 확인해주세요" else "장소 확인 중")
        val eta = stop?.durationSeconds?.takeIf { it >= 0 }?.let {
            if (it < 60) "1분 미만" else "약 ${ceil(it / 60.0).toInt()}분"
        } ?: "도착 시간 확인 중"
        routeDetails.updateText(when {
            stop?.kind == NavigationStopKind.WAYPOINT -> "만날 경유지 · $eta\n이후 목적지 · ${stops?.destination?.place?.label ?: "확인 중"}"
            ready -> "내비 목적지에서 만나요 · $eta"
            failure == NavigationFailure.ROUTE_UNVERIFIED -> "내비에서 목적지를 검색하고 경로 안내를 시작한 뒤 돌아와주세요."
            else -> "장소와 경로가 일치하는지 확인한 뒤 초대 버튼이 열려요."
        })
        val linked = app.account.state.optString("status") == "LINKED"
        directInviteButton.visibility = if (ready || app.pickup.busy) View.VISIBLE else View.GONE
        createButton.visibility = directInviteButton.visibility
        directInviteButton.isEnabled = ready && linked && !app.account.busy
        directInviteButton.text = if (app.account.busy) "연결된 사람 확인 중…" else "연결된 탑승자에게 초대"
        createButton.isEnabled = ready
        actionHint.updateText(app.account.invitationMessage ?: when {
            !ready -> "내비 연결·장소 확인은 자동으로 진행돼요. 완료되기 전에는 초대를 보내지 않아요."
            !linked -> "연결된 사람에게 바로 보내려면 설정에서 운전자 계정을 연결해주세요. 초대 링크는 바로 만들 수 있어요."
            else -> "직접 초대는 사람을 고른 뒤 전송해요. 링크 초대는 QR을 휴대전화로 열어 전달해요."
        })
        retryButton.visibility = if (failure != null && !adding) View.VISIBLE else View.GONE
        retryButton.isEnabled = app.pickup.canCreate
        addWaypointButton.visibility = if (ready) View.VISIBLE else View.GONE
        addWaypointButton.isEnabled = ready
        finalDestinationButton.visibility = if (ready && stop?.kind == NavigationStopKind.WAYPOINT) View.VISIBLE else View.GONE
        finalDestinationButton.isEnabled = ready
    }

    private val Int.dp: Int get() = (this * views.context.resources.displayMetrics.density).toInt()
}
