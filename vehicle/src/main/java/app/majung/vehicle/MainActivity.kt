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
    private lateinit var noticeScreen: ScrollView
    private lateinit var noticeGroup: LinearLayout
    private lateinit var driving: DrivingRestrictionMonitor
    private lateinit var views: VehicleViews
    private lateinit var accountSection: VehicleAccountSection
    private lateinit var registrationSection: VehicleRegistrationSection
    private lateinit var parkedScreen: ScrollView
    private lateinit var parkedRoot: LinearLayout
    private lateinit var drivingSection: DrivingPickupSection
    private lateinit var restrictionText: android.widget.TextView
    private lateinit var requestSection: RequestPickupSection
    private lateinit var requestGroup: LinearLayout
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
    private lateinit var enrollmentGroup: LinearLayout
    private lateinit var enrollmentSection: VehicleEnrollmentSection
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
            if (ticks % 3 == 0 && driving.allowed && app.registration.needsRegistration) app.enrollment.refresh()
            preparePickup()
            if (++ticks % 3 == 0 && !app.sharingRunning) app.pickup.refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = object : LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maximum = (1360 * resources.displayMetrics.density).toInt()
                val available = View.MeasureSpec.getSize(widthMeasureSpec)
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(minOf(available, maximum), View.MeasureSpec.EXACTLY), heightMeasureSpec)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32.dp, 24.dp, 32.dp, 32.dp)
            setBackgroundColor(Color.rgb(247, 248, 250))
        }
        driving = DrivingRestrictionMonitor(this) { if (::creation.isInitialized && ::drivingSection.isInitialized) render() }
        views = VehicleViews(this, layout, ::render) { driving.allowed }
        restrictionText = views.label("차량 상태 확인 중", 16f).apply {
            setTextColor(Color.rgb(49, 109, 103))
        }
        views.addView(restrictionText)
        stageTitle = views.label("", 36f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        stageDescription = views.label("", 18f).apply { setTextColor(Color.rgb(92, 111, 128)) }
        views.addView(stageTitle)
        views.addView(stageDescription)
        enrollmentGroup = views.section()
        enrollmentSection = VehicleEnrollmentSection(app, views)
        settingsGroup = views.section()
        registrationSection = VehicleRegistrationSection(app, views)
        accountGroup = views.section()
        accountSection = VehicleAccountSection(app, views)
        requestGroup = views.section()
        requestSection = RequestPickupSection(app, views)
        createGroup = views.section()
        creation = PickupCreationSection(app, views) { accountSection.inviteSelectedPlace() }
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
            setBackgroundColor(Color.rgb(247,248,250))
            addView(layout, FrameLayout.LayoutParams(-1,-2,android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL))
            visibility = View.GONE
        }
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 28.dp, 16.dp, 24.dp)
            setBackgroundColor(Color.WHITE)
        }
        navigation.addView(views.label("마중", 30f).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(25, 31, 40))
            setPadding(16.dp, 0, 0, 32.dp)
        })
        listOf("pickup" to "픽업", "requests" to "받은 요청", "settings" to "설정").forEach { (key, title) ->
            val tab = Button(this).apply {
                text = title
                isAllCaps = false
                textSize = 20f
                gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                setPadding(20.dp, 0, 12.dp, 0)
                backgroundTintList = null
                flattenButtonSurface()
                setOnClickListener { if (driving.allowed) changeTab(key) }
            }
            tabs[key] = tab
            navigation.addView(tab, LinearLayout.LayoutParams(-1, 76.dp).apply { bottomMargin = 12.dp })
        }
        navigation.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        navigation.addView(views.label("함께 만날 약속", 15f).apply {
            setTextColor(Color.rgb(139, 149, 161))
            setPadding(12.dp, 0, 0, 0)
        })
        parkedRoot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            addView(navigation, LinearLayout.LayoutParams(184.dp, -1))
            addView(FrameLayout(this@MainActivity).apply {
                clipChildren = true
                addView(parkedScreen, FrameLayout.LayoutParams(-1, -1))
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }
        views.useRoot()
        noticeGroup = views.section()
        views.addView(views.label("위치 공유와 개인정보", 24f))
        views.button("공유 안내 다시 보기") {
            android.app.AlertDialog.Builder(this).setTitle("위치 공유 안내")
                .setMessage(SharingNotice.TEXT).setPositiveButton("확인", null)
                .show().also(views::trackDialog)
        }
        views.button("사용 동의 철회") {
            if (app.pickup.pickupId != null && app.pickup.status !in setOf("COMPLETED", "CANCELLED", "EXPIRED")) {
                android.app.AlertDialog.Builder(this).setTitle("먼저 픽업을 종료해주세요")
                    .setMessage("현재 픽업을 완료하거나 취소하면 위치 접근이 끝나요. 서버에서 종료를 확인한 뒤 동의를 철회할 수 있어요.")
                    .setPositiveButton("현재 픽업 보기") { _, _ -> if (driving.allowed) changeTab("pickup") }
                    .setNegativeButton("돌아가기", null).show().also(views::trackDialog)
            } else android.app.AlertDialog.Builder(this).setTitle("사용 동의를 철회할까요?")
                .setMessage("새 위치 공유를 시작하지 않아요. 계정 삭제나 차량 연결 해제와는 달라요.")
                .setPositiveButton("동의 철회") { _, _ ->
                    if (driving.allowed && app.sharingNotice.withdraw()) {
                        stopService(Intent(this, PickupSharingService::class.java))
                        app.destination.disconnect()
                        app.voice.setEnabled(false)
                        render()
                    }
                }.setNegativeButton("돌아가기", null).show().also(views::trackDialog)
        }
        val noticeContent = object : LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = minOf(View.MeasureSpec.getSize(widthMeasureSpec), 1040.dp)
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), heightMeasureSpec)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40.dp, 32.dp, 40.dp, 40.dp)
            setBackgroundColor(Color.WHITE)
            addView(views.label("마중을 시작하기 전에", 32f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
            addView(views.label(SharingNotice.TEXT, 20f))
            addView(Button(this@MainActivity).apply {
                text = "위치 공유 안내에 동의하고 시작"
                textSize = 22f
                minimumHeight = 76.dp
                flattenButtonSurface()
                backgroundTintList = null
                setTextColor(Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(38, 105, 221)); cornerRadius = 16.dp.toFloat()
                }
                setOnClickListener {
                    if (driving.allowed && app.sharingNotice.accept()) {
                        preparePickup(refresh = true)
                        render()
                    }
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 24.dp })
            addView(Button(this@MainActivity).apply {
                text = "지금은 사용하지 않기"
                flattenButtonSurface()
                backgroundTintList = null
                setTextColor(Color.rgb(51, 61, 75))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(242, 244, 247)); cornerRadius = 16.dp.toFloat()
                }
                textSize = 20f
                minimumHeight = 72.dp
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.dp })
        }
        noticeScreen = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(247, 248, 250))
            addView(noticeContent, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL))
            visibility = View.GONE
        }
        drivingSection = DrivingPickupSection(this, app)
        drivingSection.render(false, 0, 0)
        setContentView(FrameLayout(this).apply {
            addView(parkedRoot, FrameLayout.LayoutParams(-1, -1))
            addView(drivingSection.root, FrameLayout.LayoutParams(-1, -1))
            addView(noticeScreen, FrameLayout.LayoutParams(-1, -1))
        })
    }

    override fun onStart() {
        super.onStart()
        driving.start()
        app.pickup.changed = ::render
        app.account.changed = {
            // Account reads only update their section; do not redraw the navigation and pickup surface.
            if (driving.allowed) accountSection.render(selectedTab == "settings") else render()
        }
        app.account.refresh()
        // A waypoint request may open the navigation app. Revalidate on return;
        // do not start a location service before a pickup is approved.
        if (app.sharingNotice.accepted && app.destination.addingWaypoint) app.destination.connect()
        preparePickup(refresh = true)
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
        resetTabMotion()
        driving.stop()
        handler.removeCallbacks(tick)
        app.pickup.changed = null
        app.account.changed = null
        if (!app.sharingRunning && !startRequested) app.destination.disconnect()
        super.onStop()
    }

    private var tabMotionGeneration = 0

    private fun resetTabMotion() {
        tabMotionGeneration++
        parkedScreen.animate().withEndAction(null).cancel()
        parkedScreen.translationX = 0f
        parkedScreen.alpha = 1f
    }

    private fun changeTab(next: String) {
        resetTabMotion()
        if (next == selectedTab) return
        val order = listOf("pickup", "requests", "settings")
        val direction = if (order.indexOf(next) > order.indexOf(selectedTab)) 1 else -1
        val generation = tabMotionGeneration
        fun showNext() {
            selectedTab = next
            parkedScreen.scrollTo(0, 0)
            preparePickup(refresh = true)
            render()
        }
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) { showNext(); return }
        val distance = minOf(120.dp.toFloat(), parkedScreen.width * .15f)
        parkedScreen.animate().translationX(-direction * distance).alpha(0f)
            .setDuration(100).setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                if (generation != tabMotionGeneration || !driving.allowed || isFinishing) return@withEndAction
                showNext()
                parkedScreen.translationX = direction * distance
                parkedScreen.animate().translationX(0f).alpha(1f).setDuration(220)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                    .withEndAction(null).start()
            }.start()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private fun preparePickup(refresh: Boolean = false) {
        if (app.sharingNotice.accepted && driving.allowed && selectedTab == "pickup" && app.pickup.canCreate && !app.sharingRunning)
            app.destination.prepareForPickup(refresh)
    }

    private fun requestSharingPermission() {
        if (!driving.allowed || !app.sharingNotice.accepted) return
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
        if (!app.sharingNotice.accepted) {
            val parked = driving.allowed
            if (::noticeScreen.isInitialized) noticeScreen.visibility = if (parked) View.VISIBLE else View.GONE
            parkedRoot.visibility = View.GONE
            drivingSection.root.visibility = if (parked) View.GONE else View.VISIBLE
            drivingSection.render(driving.known, driving.stringLimit, driving.itemLimit)
            return
        }
        noticeScreen.visibility = View.GONE
        app.requestFlow.tick()
        if (app.requestFlow.stage == RequestPickupFlow.Stage.DONE) {
            val place = app.pickup.place
            if (driving.allowed && place != null && app.destination.currentStops()?.resolve(place) != null)
                app.pickup.confirmRecoveredSharing()
            app.requestFlow.consumeDone()
            selectedTab = "pickup"
        }
        views.beginRender()
        requestSection.render()
        registrationSection.render()
        enrollmentSection.render()
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
        restrictionText.updateText(if (driving.allowed) "●  정차 중 · 조작 가능" else
            "주행 제한 또는 차량 상태 미확인으로 조작을 잠갔어요. 안전하게 정차한 뒤 확인해주세요")
        val hasPickup = app.pickup.pickupId != null
        val active = app.pickup.status in setOf("ACTIVE", "ARRIVED")
        fun show(view: View, visible: Boolean) { view.visibility = if (visible) View.VISIBLE else View.GONE }
        val needsConnection = app.registration.needsRegistration
        show(enrollmentGroup, needsConnection)
        show(settingsGroup, selectedTab == "settings")
        show(voiceGroup, selectedTab == "settings")
        show(noticeGroup, selectedTab == "settings")
        show(accountGroup, !needsConnection && (selectedTab == "settings" || (selectedTab == "requests" && !app.requestFlow.visible)))
        show(createGroup, selectedTab == "pickup" && !hasPickup && !needsConnection && !app.requestFlow.visible)
        show(requestGroup, app.requestFlow.visible && selectedTab != "settings")
        show(statusGroup, selectedTab == "pickup" && hasPickup)
        show(participantGroup, selectedTab == "pickup" && hasPickup && app.pickup.status !in setOf("COMPLETED", "CANCELLED", "EXPIRED"))
        show(completionGroup, selectedTab == "pickup" && hasPickup)
        show(permissionGroup, selectedTab == "pickup" && active)
        stageTitle.updateText(if (app.requestFlow.visible && selectedTab != "settings") "마중 경로 준비" else when (selectedTab) {
            "requests" -> "누구를 마중할까요?"
            "settings" -> "차량과 계정"
            else -> when (app.pickup.status) {
                "INVITING", "WAITING_APPROVAL" -> "탑승자를 초대해주세요"
                "ACTIVE" -> "마중 가고 있어요"
                "ARRIVED" -> "도착했어요"
                "COMPLETED" -> "함께 만났어요"
                "CANCELLED", "EXPIRED" -> "픽업이 끝났어요"
                else -> if (needsConnection) "먼저 차량을 연결해주세요" else "어디서 만날까요?"
            }
        })
        stageDescription.updateText(when (selectedTab) {
            "requests" -> "받은 요청을 확인하고 순서대로 픽업할 수 있어요."
            "settings" -> if (app.account.state.optString("status") == "LINKED") "운전자 계정과 연결된 사람, 음성 안내를 관리해요."
                else "처음 한 번 운전자 휴대전화로 연결해주세요."
            else -> if (active) "${app.pickup.place?.label ?: "약속 장소"} · 탑승자의 준비 상태를 확인해주세요."
                else if (hasPickup) "픽업을 마치면 위치 공유도 끝나요."
                else "내비 장소를 확인한 뒤 초대할 사람이나 초대 링크를 선택하세요."
        })
        tabs.forEach { (key, tab) ->
            tab.isEnabled = driving.allowed
            tab.isSelected = key == selectedTab
            if (tab.tag != selectedTab) {
                tab.tag = selectedTab
                tab.setTextColor(android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                    intArrayOf(Color.rgb(38, 105, 221), Color.rgb(38, 105, 221), if (key == selectedTab) Color.rgb(38, 105, 221) else Color.rgb(107, 118, 132))))
                tab.background = android.graphics.drawable.StateListDrawable().apply {
                    fun shape(color: Int) = android.graphics.drawable.GradientDrawable().apply {
                        setColor(color)
                        cornerRadius = 14.dp.toFloat()
                    }
                    addState(intArrayOf(android.R.attr.state_pressed), shape(Color.rgb(220, 233, 255)))
                    addState(intArrayOf(android.R.attr.state_focused), shape(Color.rgb(220, 233, 255)).apply { setStroke(3.dp, Color.rgb(38, 105, 221)) })
                    addState(intArrayOf(), shape(if (key == selectedTab) Color.rgb(235, 243, 255) else Color.TRANSPARENT))
                }
            }
        }
        views.applyRestrictions()
        val parked = driving.allowed
        if (!parked) {
            resetTabMotion()
        }
        parkedRoot.visibility = if (parked) View.VISIBLE else View.GONE
        parkedScreen.visibility = if (parked) View.VISIBLE else View.GONE
        drivingSection.root.visibility = if (parked) View.GONE else View.VISIBLE
        drivingSection.render(driving.known, driving.stringLimit, driving.itemLimit)
    }
}
