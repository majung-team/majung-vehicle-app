package app.majung.vehicle

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView

/** Parked development screen, not an approved driving UI. No coordinate entry. */
class MainActivity : Activity() {
    private val app: VehicleApplication get() = application as VehicleApplication
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var creation: PickupCreationSection
    private lateinit var status: PickupStatusSection
    private lateinit var participant: PickupParticipantSection
    private lateinit var completion: PickupCompletionSection
    private var ticks = 0
    private val tick = object : Runnable {
        override fun run() {
            render()
            if (++ticks % 3 == 0) app.pickup.refresh()
            if (app.pickup.status in setOf("ACTIVE", "ARRIVED")) {
                if (ticks % 5 == 0) app.destination.pollTelemetry()
                app.pickup.syncVehicle(app.destination)
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            setBackgroundColor(Color.rgb(246, 247, 239))
        }
        val views = VehicleViews(this, layout, ::render)
        views.addView(views.label("마중 · 차량에서 약속 만들기", 26f))
        views.addView(views.label("주차 중에 사용하는 개발 확인 화면\n내비게이션에서 목적지를 고른 뒤 확인해주세요.\n버튼을 누르면 선택한 장소가 로컬 개발 서버에 저장돼요.", 16f))
        creation = PickupCreationSection(app, views)
        status = PickupStatusSection(app, views)
        participant = PickupParticipantSection(app, views)
        completion = PickupCompletionSection(app, views)
        views.addView(views.label("좌표를 입력할 필요가 없어요.\n승인 후 SDK 위치·남은 시간을 서버로 전달해요.\n화면을 떠나면 공유를 멈춰요. 장소 변경은 아직 지원하지 않아요.\n앱 프로세스가 종료되면 초대·운전자 접근 정보는 복원되지 않아요.", 14f))
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    override fun onStart() {
        super.onStart()
        app.pickup.changed = ::render
        render()
        handler.postDelayed(tick, 1000)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        app.pickup.changed = null
        // Destination freshness must be re-established after returning to the screen.
        app.destination.disconnect()
        super.onStop()
    }

    private fun render() {
        creation.render()
        status.render()
        participant.render()
        completion.render()
    }
}
