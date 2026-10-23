package app.majung.vehicle

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
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
    private lateinit var sharingButton: Button
    private lateinit var resumeButton: Button
    private var resumed = false
    private var startRequested = false
    private var ticks = 0
    private val tick = object : Runnable {
        override fun run() {
            render()
            if (++ticks % 3 == 0 && !app.sharingRunning) app.pickup.refresh()
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
        resumeButton = views.button("이전 픽업 계속하기") {
            app.pickup.confirmRecoveredSharing()
            requestSharingPermission()
        }
        resumeButton.id = R.id.resume_pickup
        sharingButton = views.button("화면 밖에서도공유 시작") { requestSharingPermission() }.apply { id = R.id.start_sharing }
        views.addView(views.label("좌표를 입력할 필요가 없어요.\n승인 후 SDK 위치·남은 시간을 서버로 전달해요.\n위치 권한 허용 후에는 화면을 내려도 공유를 유지해요. 장소 변경은 아직 지원하지 않아요.\n앱을 다시 열면 저장된 접근 정보로 서버 상태를 확인해요.", 14f))
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    override fun onStart() {
        super.onStart()
        app.pickup.changed = ::render
        render()
        handler.postDelayed(tick, 1000)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        startRequested = false
        render()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        app.pickup.changed = null
        if (!app.sharingRunning && !startRequested) app.destination.disconnect()
        super.onStop()
    }

    private fun requestSharingPermission() {
        if (!PickupSharingService.hasLocationPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 32)
        } else {
            startRequested = false
            render()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 32) {
            app.sharingMessage = if (PickupSharingService.hasLocationPermission(this)) "공유 시작 준비 중"
                else "위치 권한이 없어 위치·ETA를 보내지 않아요. 공유 시작을 눌러 다시 허용해주세요"
            if (Build.VERSION.SDK_INT >= 33 && PickupSharingService.hasLocationPermission(this) &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 33)
            }
            startRequested = false
        }
        render()
    }

    private fun render() {
        creation.render()
        status.render()
        participant.render()
        completion.render()
        val sharing = app.pickup.status in setOf("ACTIVE", "ARRIVED")
        resumeButton.visibility = if (app.pickup.needsSharingConfirmation) android.view.View.VISIBLE else android.view.View.GONE
        resumeButton.isEnabled = app.pickup.online && !app.pickup.busy
        sharingButton.visibility = if (sharing && !app.pickup.needsSharingConfirmation && !app.sharingRunning) android.view.View.VISIBLE else android.view.View.GONE
        if (resumed && sharing && !app.pickup.needsSharingConfirmation && !app.sharingRunning && !startRequested && PickupSharingService.hasLocationPermission(this)) {
            startRequested = true
            try {
                startForegroundService(Intent(this, PickupSharingService::class.java))
            } catch (_: IllegalStateException) {
                app.destination.disconnect()
                app.sharingMessage = "공유를 시작하지 못했어요. 화면에서 다시 시작해주세요"
            } catch (_: SecurityException) {
                app.destination.disconnect()
                app.sharingMessage = "위치 권한을 확인한 뒤 다시 시작해주세요"
            }
        }
    }
}
