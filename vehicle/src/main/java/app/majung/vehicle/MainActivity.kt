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
import android.widget.FrameLayout
import android.view.View

/** Parked controls and a separate read-only surface follow the live system UX policy. */
class MainActivity : Activity() {
    private val app: VehicleApplication get() = application as VehicleApplication
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var driving: DrivingRestrictionMonitor
    private lateinit var views: VehicleViews
    private lateinit var accountSection: VehicleAccountSection
    private lateinit var registrationSection: VehicleRegistrationSection
    private lateinit var parkedScreen: ScrollView
    private lateinit var drivingSection: DrivingPickupSection
    private lateinit var restrictionText: android.widget.TextView
    private lateinit var creation: PickupCreationSection
    private lateinit var status: PickupStatusSection
    private lateinit var voice: PickupVoiceSection
    private lateinit var participant: PickupParticipantSection
    private lateinit var completion: PickupCompletionSection
    private lateinit var sharingButton: Button
    private lateinit var resumeButton: Button
    private var selectedTab = "pickup"
    private lateinit var createGroup: LinearLayout
    private lateinit var statusGroup: LinearLayout
    private lateinit var participantGroup: LinearLayout
    private lateinit var completionGroup: LinearLayout
    private lateinit var accountGroup: LinearLayout
    private lateinit var settingsGroup: LinearLayout
    private lateinit var voiceGroup: LinearLayout
    private lateinit var permissionGroup: LinearLayout
    private lateinit var stageTitle: android.widget.TextView
    private lateinit var stageDescription: android.widget.TextView
    private val tabs = linkedMapOf<String, Button>()
    private var resumed = false
    private var startRequested = false
    private var ticks = 0
    private val tick = object : Runnable {
        override fun run() {
            render()
            if (ticks % 10 == 0) app.account.refresh()
            if (ticks % 5 == 0 && driving.allowed && selectedTab == "pickup" && app.pickup.canCreate && !app.sharingRunning) {
                app.destination.connect()
                app.destination.pollTelemetry()
            }
            if (++ticks % 3 == 0 && !app.sharingRunning) app.pickup.refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = object : LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maximum = (1200 * resources.displayMetrics.density).toInt()
                val available = View.MeasureSpec.getSize(widthMeasureSpec)
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(minOf(available, maximum), View.MeasureSpec.EXACTLY), heightMeasureSpec)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            setBackgroundColor(Color.rgb(247, 248, 247))
        }
        driving = DrivingRestrictionMonitor(this) { if (::creation.isInitialized && ::drivingSection.isInitialized) render() }
        views = VehicleViews(this, layout, ::render) { driving.allowed }
        restrictionText = views.label("차량의 조작 허용 상태를 확인하고 있어요", 18f)
        views.addView(restrictionText)
        views.addView(views.label("마중", 30f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        val navigation = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("pickup" to "픽업", "requests" to "마중 요청", "settings" to "설정").forEach { (key, title) ->
            val tab = Button(this).apply {
                text = title; isAllCaps = false; textSize = 18f
                setOnClickListener { if (driving.allowed) { selectedTab = key; render() } }
            }
            tabs[key] = tab
            navigation.addView(tab, LinearLayout.LayoutParams(0, 64, 1f))
        }
        views.addView(navigation)
        stageTitle = views.label("", 30f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        stageDescription = views.label("", 17f)
        views.addView(stageTitle); views.addView(stageDescription)
        settingsGroup = views.section()
        registrationSection = VehicleRegistrationSection(app, views)
        accountGroup = views.section()
        accountSection = VehicleAccountSection(app, views)
        createGroup = views.section()
        creation = PickupCreationSection(app, views)
        statusGroup = views.section()
        status = PickupStatusSection(app, views)
        voiceGroup = views.section()
        voice = PickupVoiceSection(app, views)
        participantGroup = views.section()
        participant = PickupParticipantSection(app, views)
        completionGroup = views.section()
        completion = PickupCompletionSection(app, views)
        permissionGroup = views.section()
        resumeButton = views.button("이전 픽업 계속하기") {
            app.pickup.confirmRecoveredSharing()
            requestSharingPermission()
        }
        resumeButton.id = R.id.resume_pickup
        sharingButton = views.button("화면 밖에서도 공유 시작") { requestSharingPermission() }.apply { id = R.id.start_sharing }
        layout.removeView(permissionGroup)
        layout.addView(permissionGroup, layout.indexOfChild(statusGroup))
        parkedScreen = ScrollView(this).apply {
            id = R.id.parked_controls
            setBackgroundColor(Color.rgb(247,248,247))
            addView(layout, FrameLayout.LayoutParams(-1,-2,android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL))
            visibility = View.GONE
        }
        drivingSection = DrivingPickupSection(this, app)
        drivingSection.render(false, 0, 0)
        setContentView(FrameLayout(this).apply {
            addView(parkedScreen, FrameLayout.LayoutParams(-1, -1))
            addView(drivingSection.root, FrameLayout.LayoutParams(-1, -1))
        })
    }

    override fun onStart() {
        super.onStart()
        driving.start()
        app.pickup.changed = ::render
        app.account.changed = ::render
        app.account.refresh()
        // A waypoint request may open the navigation app. Revalidate on return;
        // do not start a location service before a pickup is approved.
        if (app.destination.addingWaypoint) app.destination.connect()
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
        driving.stop()
        handler.removeCallbacks(tick)
        app.pickup.changed = null
        app.account.changed = null
        if (!app.sharingRunning && !startRequested) app.destination.disconnect()
        super.onStop()
    }

    private fun requestSharingPermission() {
        if (!driving.allowed) return
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
        if (app.pickup.pickupId == null && app.registration.needsRegistration && selectedTab == "pickup") selectedTab = "settings"
        views.beginRender()
        registrationSection.render()
        accountSection.render(selectedTab == "settings")
        creation.render()
        status.render()
        voice.render()
        participant.render()
        completion.render()
        val sharing = app.pickup.status in setOf("ACTIVE", "ARRIVED")
        resumeButton.visibility = if (app.pickup.needsSharingConfirmation) android.view.View.VISIBLE else android.view.View.GONE
        resumeButton.isEnabled = app.pickup.online && !app.pickup.busy
        sharingButton.visibility = if (sharing && !app.pickup.needsSharingConfirmation && !app.sharingRunning) android.view.View.VISIBLE else android.view.View.GONE
        if (resumed && driving.allowed && sharing && !app.pickup.needsSharingConfirmation && !app.sharingRunning && !startRequested && PickupSharingService.hasLocationPermission(this)) {
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
        restrictionText.updateText(if (driving.allowed) "정차 중 · 화면 조작 가능" else
            "주행 제한 또는 차량 상태 미확인으로 조작을 잠갔어요. 안전하게 정차한 뒤 확인해주세요")
        val hasPickup = app.pickup.pickupId != null
        val active = app.pickup.status in setOf("ACTIVE", "ARRIVED")
        fun show(view: View, visible: Boolean) { view.visibility = if (visible) View.VISIBLE else View.GONE }
        show(settingsGroup, selectedTab == "settings")
        show(voiceGroup, selectedTab == "settings")
        show(accountGroup, selectedTab == "requests" || selectedTab == "settings")
        show(createGroup, selectedTab == "pickup" && !hasPickup)
        show(statusGroup, selectedTab == "pickup" && hasPickup)
        show(participantGroup, selectedTab == "pickup" && hasPickup && app.pickup.status !in setOf("COMPLETED", "CANCELLED", "EXPIRED"))
        show(completionGroup, selectedTab == "pickup" && hasPickup)
        show(permissionGroup, selectedTab == "pickup" && active)
        stageTitle.updateText(when (selectedTab) {
            "requests" -> "누구를 마중할까요?"
            "settings" -> "차량과 계정"
            else -> when (app.pickup.status) {
                "INVITING", "WAITING_APPROVAL" -> "탑승자를 초대해주세요"
                "ACTIVE" -> "마중 가고 있어요"
                "ARRIVED" -> "도착했어요"
                "COMPLETED" -> "함께 만났어요"
                "CANCELLED", "EXPIRED" -> "픽업이 끝났어요"
                else -> "만날 장소를 골라주세요"
            }
        })
        stageDescription.updateText(when (selectedTab) {
            "requests" -> "받은 요청을 확인하고 순서대로 픽업할 수 있어요."
            "settings" -> "처음 한 번 차량을 등록하고 운전자 계정을 연결해주세요."
            else -> if (active) "${app.pickup.place?.label ?: "약속 장소"} · 탑승자의 준비 상태를 확인해주세요."
                else if (hasPickup) "픽업을 마치면 위치 공유도 끝나요."
                else "내비게이션의 목적지나 경유지에서 태울 수 있어요."
        })
        tabs.forEach { (key, tab) ->
            tab.isEnabled = driving.allowed
            tab.isSelected = key == selectedTab
            tab.setTextColor(if (key == selectedTab) Color.WHITE else Color.rgb(36,79,115))
            tab.backgroundTintList = android.content.res.ColorStateList.valueOf(if (key == selectedTab) Color.rgb(36,79,115) else Color.rgb(231,238,243))
        }
        views.applyRestrictions()
        val parked = driving.allowed
        parkedScreen.visibility = if (parked) View.VISIBLE else View.GONE
        drivingSection.root.visibility = if (parked) View.GONE else View.VISIBLE
        drivingSection.render(driving.known, driving.stringLimit, driving.itemLimit)
    }
}
