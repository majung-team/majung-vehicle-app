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
import kotlin.math.ceil

/** The Figma draft is a visual reference; every place and ETA here comes from the SDK. */
internal class PickupCreationSection(private val app: VehicleApplication, private val views: VehicleViews) {
    private val destinationText: TextView
    private val placeName: TextView
    private val routeDetails: TextView
    private val createButton: Button
    private val addWaypointButton: Button
    private val finalDestinationButton: Button

    init {
        views.addView(views.label("어디서 태울까요?", 30f).apply { setTypeface(typeface, Typeface.BOLD) })
        views.addView(views.label("최종 목적지를 그대로 두고, 픽업할 장소를 경유지로 추가할 수 있어요.", 17f))
        views.button("내비게이션 연결") { app.destination.connect() }
        views.button("장소와 경로 다시 확인") { app.destination.query(); render() }
        destinationText = views.label("", 16f).apply { minLines = 2; maxLines = 3 }
        views.addView(destinationText)
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
        routeDetails = views.label("", 17f).apply { minLines = 2; maxLines = 3; setTextColor(Color.rgb(36, 79, 115)) }
        card.addView(placeName)
        card.addView(routeDetails)
        views.addView(card)
        addWaypointButton = views.button("최근 내비 장소를 경유지로 추가") {
            if (!app.pickup.canCreate) return@button
            app.destination.loadRecentPlaces { places ->
                if (!app.pickup.canCreate) return@loadRecentPlaces
                if (places.isEmpty()) {
                    AlertDialog.Builder(views.context).setMessage("확인할 수 있는 최근 장소가 없어요. 내비게이션에서 장소를 검색한 뒤 다시 확인해주세요.")
                        .setPositiveButton("확인", null).show()
                } else {
                    AlertDialog.Builder(views.context).setTitle("어디서 태울까요?")
                        .setItems(places.map { it.place.label }.toTypedArray()) { _, index ->
                            val place = places[index]
                            AlertDialog.Builder(views.context)
                                .setMessage("${place.place.label}을 픽업 경유지로 추가할까요? 기존 최종 목적지는 유지해요.")
                                .setPositiveButton("경유지로 추가") { _, _ ->
                                    if (app.pickup.canCreate) app.destination.addPickupWaypoint(place)
                                    render()
                                }.setNegativeButton("돌아가기", null).show()
                        }.setNegativeButton("돌아가기", null).show()
                }
            }
        }.apply { id = R.id.add_pickup_waypoint }
        finalDestinationButton = views.button("픽업 장소는 최종 목적지 사용") { app.destination.useFinalDestination(); render() }
        createButton = views.button("이 장소에서 태우기") {
            val selected = app.destination.selection.current(SystemClock.elapsedRealtime()) ?: return@button
            app.pickup.create(selected, app.destination.referenceFor(selected))
        }.apply {
            id = R.id.create_pickup
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(Color.rgb(36, 79, 115), Color.rgb(148, 164, 177)))
        }
    }

    fun render() {
        val place = app.destination.selection.current(SystemClock.elapsedRealtime())
        val stops = app.destination.currentStops()
        val stop = place?.let { stops?.resolve(it) }
        destinationText.updateText(app.destination.status)
        placeName.updateText(place?.label ?: "확인된 픽업 장소가 없어요")
        val eta = stop?.durationSeconds?.takeIf { it >= 0 }?.let {
            if (it < 60) "1분 미만" else "약 ${ceil(it / 60.0).toInt()}분"
        } ?: "예상 시간 확인 중"
        routeDetails.updateText(if (stop?.kind == NavigationStopKind.WAYPOINT)
            "픽업 경유지 · $eta\n최종 목적지 ${stops?.destination?.place?.label ?: "확인 중"} 유지"
            else "최종 목적지 · $eta\n경유지 추가 결과가 확인된 뒤에만 픽업을 만들 수 있어요")
        createButton.isEnabled = place != null && stop != null && app.pickup.canCreate && !app.destination.addingWaypoint
        addWaypointButton.isEnabled = app.pickup.canCreate && stops != null && !app.destination.addingWaypoint
        finalDestinationButton.isEnabled = app.pickup.canCreate
    }

    private val Int.dp: Int get() = (this * views.context.resources.displayMetrics.density).toInt()
}
