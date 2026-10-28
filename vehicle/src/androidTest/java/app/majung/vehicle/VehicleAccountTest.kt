package app.majung.vehicle

import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VehicleAccountTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun await(test:()->Boolean) { val until=SystemClock.elapsedRealtime()+10000; while(!test() && SystemClock.elapsedRealtime()<until) SystemClock.sleep(30); assertTrue(test()) }
    private fun registered(api: PickupApiClient): VehicleRegistrationController {
        val store=object: VehicleCredentialStore {
            override fun load()=VehicleCredential("vehicle","V".repeat(43))
            override fun save(credential: VehicleCredential) { }
            override fun clear() { }
        }
        lateinit var registration: VehicleRegistrationController
        instrumentation.runOnMainSync { registration=VehicleRegistrationController(api,store); registration.refresh() }
        await { registration.canCreate }; return registration
    }
    @Test fun explicitClaimConfirmationAndMultipleRequestsUseVehicleAuthority() {
        Fixture().use { fixture ->
            val api=PickupApiClient(fixture.origin); val registration=registered(api)
            lateinit var account: VehicleAccountController
            instrumentation.runOnMainSync { account=VehicleAccountController(api,registration); account.issueQr() }
            await { !account.busy }
            assertNotNull(account.qrLink); assertTrue(account.qrLink!!.contains("/account#vehicle/"))
            fixture.claimed.set(true)
            instrumentation.runOnMainSync { account.refresh() }; await { !account.busy }
            assertEquals("AWAITING_VEHICLE_CONFIRMATION",account.state.optString("status"))
            assertEquals(0,fixture.confirmations.get())
            instrumentation.runOnMainSync { account.confirm(account.state.getString("challengeId"),account.state.getString("majungId")) }; await { !account.busy }
            assertEquals(1,fixture.confirmations.get()); assertNull(account.qrLink)
            instrumentation.runOnMainSync { account.refresh() }; await { !account.busy }
            assertEquals(2,account.requests.size)
            instrumentation.runOnMainSync { account.changeRequest("request-two","accept") }; await { !account.busy }
            assertEquals("QUEUED",account.requests[1].getString("status")); assertEquals(1,fixture.accepts.get())
            instrumentation.runOnMainSync { account.refresh() }; await { !account.busy }
            assertEquals(1,fixture.accepts.get())
            assertTrue(fixture.authorityCorrect.get())
        }
    }
    @Test fun staleConfirmationNeverApprovesTheReplacementChallenge() {
        Fixture().use { fixture ->
            fixture.claimed.set(true)
            val api=PickupApiClient(fixture.origin); val registration=registered(api)
            lateinit var account: VehicleAccountController
            instrumentation.runOnMainSync { account=VehicleAccountController(api,registration); account.refresh() }
            await { !account.busy }
            val shownId=account.state.getString("challengeId")
            val shownMember=account.state.getString("majungId")
            fixture.challenge=UUID.randomUUID().toString()
            instrumentation.runOnMainSync { account.refresh() }; await { !account.busy }
            instrumentation.runOnMainSync { account.confirm(shownId,shownMember) }
            assertEquals(0,fixture.confirmations.get())
            assertTrue(account.message.contains("다시 확인"))
            instrumentation.runOnMainSync { account.confirm(account.state.getString("challengeId"),shownMember) }
            await { !account.busy }
            assertEquals(1,fixture.confirmations.get())
        }
    }
    @Test fun malformedAndFailedMutationResponsesDoNotCrashOrRetry() {
        Fixture().use { fixture ->
            val api=PickupApiClient(fixture.origin); val registration=registered(api)
            lateinit var account: VehicleAccountController
            fixture.linked.set(true)
            instrumentation.runOnMainSync { account=VehicleAccountController(api,registration); account.refresh() }; await { !account.busy }
            fixture.failAccept.set(true)
            instrumentation.runOnMainSync { account.changeRequest("request-two","accept") }; await { !account.busy }
            assertEquals(1,fixture.accepts.get()); assertTrue(account.message.contains("확인하지 못했어요"))
            instrumentation.runOnMainSync { account.refresh() }; await { !account.busy }
            assertEquals(1,fixture.accepts.get())
            fixture.malformed.set(true)
            instrumentation.runOnMainSync { account.issueQr() }; await { !account.busy }
            assertNull(account.qrLink)
        }
    }
    @Test fun connectedPassengerInvitationUsesStableIdAndNeverRetriesByPolling() {
        Fixture().use { fixture ->
            fixture.linked.set(true)
            val api=PickupApiClient(fixture.origin); val registration=registered(api)
            lateinit var account: VehicleAccountController
            instrumentation.runOnMainSync { account=VehicleAccountController(api,registration); account.refresh() }
            await { !account.busy }
            var loaded=false
            instrumentation.runOnMainSync { account.loadContacts { loaded=true } }
            await { loaded && !account.busy }
            assertEquals("가상탑승자B",account.contacts.single().getString("nickname"))
            fixture.failInvitation.set(true)
            val place=PickupPlace("초대 시험 장소",37.3,127.3)
            instrumentation.runOnMainSync { account.invite("connection-test",place) }
            await { !account.busy }; assertEquals(1,fixture.invitations.get())
            assertTrue(account.invitationMessage!!.contains("확인하지 못했어요"))
            instrumentation.runOnMainSync { account.refresh() }
            await { !account.busy }; assertEquals(1,fixture.invitations.get())
            fixture.failInvitation.set(false)
            instrumentation.runOnMainSync { account.invite("connection-test",place) }
            await { !account.busy }; assertEquals(2,fixture.invitations.get())
            assertEquals(fixture.invitationIds[0],fixture.invitationIds[1])
            assertTrue(account.message.contains("초대를 보냈어요"))
            instrumentation.runOnMainSync { account.refresh() }; await { !account.busy }
            assertTrue(account.invitationMessage!!.contains("초대를 보냈어요"))
            assertTrue(fixture.authorityCorrect.get())
        }
    }
    @Test fun vehiclePeopleSearchAndRequestRequireStableDriverAndNeverRetryOnReads() {
        Fixture().use { fixture ->
            val api=PickupApiClient(fixture.origin)
            val registration=registered(api)
            var driver: String?="MJ-0000000000"
            lateinit var people: VehiclePeopleController
            var response: JSONObject?=null
            var failure: String?=null
            var done=false
            instrumentation.runOnMainSync {
                people=VehiclePeopleController(api,{ registration.vehicleToken },{ driver })
                people.search("가상",0) { data,error -> response=data; failure=error; done=true }
            }
            await { done && !people.busy }
            assertNull(failure)
            assertEquals(2,response!!.getJSONArray("items").length())
            val target=response!!.getJSONArray("items").getJSONObject(1).getString("memberId")
            done=false
            instrumentation.runOnMainSync { people.request(target) { data,error -> response=data; failure=error; done=true } }
            await { done && !people.busy }
            assertNull(failure)
            assertEquals("PENDING",response!!.getJSONObject("connection").getString("status"))
            assertEquals(target,fixture.peopleTarget)
            assertEquals("MJ-0000000000",fixture.peopleDriver)
            assertEquals(1,fixture.peopleRequests.get())
            done=false
            instrumentation.runOnMainSync { people.connections(0) { data,error -> response=data; failure=error; done=true } }
            await { done && !people.busy }
            assertEquals(1,fixture.peopleRequests.get())
            done=false
            instrumentation.runOnMainSync {
                people.search("가상",0) { data,error -> response=data; failure=error; done=true }
                driver="MJ-9999999999"
            }
            await { done && !people.busy }
            assertNull(response)
            assertTrue(failure!!.contains("운전자 연결이 바뀌었어요"))
            assertTrue(fixture.authorityCorrect.get())
        }
    }
    @Test fun vehicleScreenShowsLinkedDriverAndMultipleRequests() {
        Fixture().use { fixture ->
            fixture.linked.set(true)
            fixture.twoContacts.set(true)
            val api=PickupApiClient(fixture.origin); val registration=registered(api)
            val app=androidx.test.core.app.ApplicationProvider.getApplicationContext<VehicleApplication>()
            val registrationField=VehicleApplication::class.java.getDeclaredField("registration").apply { isAccessible=true }
            val accountField=VehicleApplication::class.java.getDeclaredField("account").apply { isAccessible=true }
            val pickupField=VehicleApplication::class.java.getDeclaredField("pickup").apply { isAccessible=true }
            val destinationField=VehicleApplication::class.java.getDeclaredField("destination").apply { isAccessible=true }
            val peopleField=VehicleApplication::class.java.getDeclaredField("people").apply { isAccessible=true }
            val oldPeople=app.people
            val oldDestination=app.destination
            val oldRegistration=app.registration; val oldAccount=app.account; val oldPickup=app.pickup
            lateinit var account: VehicleAccountController
            instrumentation.runOnMainSync {
                account=VehicleAccountController(api,registration)
                peopleField.set(app,VehiclePeopleController(api,{ registration.vehicleToken },{ account.state.takeIf { it.optString("status")=="LINKED" }?.optString("majungId") }))
                val destination=DestinationReader(app)
                val place=PickupPlace("초대 시험 장소",37.3,127.3)
                destination.selection.receive(place.label,place.latitude,place.longitude,SystemClock.elapsedRealtime())
                DestinationReader::class.java.getDeclaredField("route").apply { isAccessible=true }.set(destination,NavigationStops(NavigationStop(place,120,NavigationStopKind.DESTINATION),emptyList()))
                DestinationReader::class.java.getDeclaredField("routeReceivedAt").apply { isAccessible=true }.setLong(destination,SystemClock.elapsedRealtime())
                val validation=DestinationReader::class.java.getDeclaredField("routeValidation").apply { isAccessible=true }.get(destination) as RouteValidation
                val now=SystemClock.elapsedRealtime()
                validation.requestState(now-6000); validation.receiveState(true,now-6000); validation.receiveDestination(place,now-6000)
                validation.requestState(now); validation.receiveState(true,now); validation.receiveDestination(place,now)
                destinationField.set(app,destination)
                registrationField.set(app,registration); accountField.set(app,account)
                pickupField.set(app,PickupController(api,MemoryPickupSessionStore(),registration))
                account.refresh()
            }
            await { !account.busy }
            try {
                androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        // This test supplies route evidence; do not mix in live SDK callbacks.
                        val handler=MainActivity::class.java.getDeclaredField("handler").apply { isAccessible=true }.get(activity) as android.os.Handler
                        val tick=MainActivity::class.java.getDeclaredField("tick").apply { isAccessible=true }.get(activity) as Runnable
                        handler.removeCallbacks(tick)
                        app.destination.disconnect()
                        val place=PickupPlace("초대 시험 장소",37.3,127.3)
                        val now=SystemClock.elapsedRealtime()
                        app.destination.selection.receive(place.label,place.latitude,place.longitude,now)
                        DestinationReader::class.java.getDeclaredField("route").apply { isAccessible=true }.set(app.destination,NavigationStops(NavigationStop(place,120,NavigationStopKind.DESTINATION),emptyList()))
                        DestinationReader::class.java.getDeclaredField("routeReceivedAt").apply { isAccessible=true }.setLong(app.destination,now)
                        val validation=DestinationReader::class.java.getDeclaredField("routeValidation").apply { isAccessible=true }.get(app.destination) as RouteValidation
                        validation.requestState(now-6000); validation.receiveState(true,now-6000); validation.receiveDestination(place,now-6000)
                        validation.requestState(now); validation.receiveState(true,now); validation.receiveDestination(place,now)
                        MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible=true }.invoke(activity)
                        val texts=mutableListOf<String>()
                        fun collect(view:android.view.View) {
                            if(view is android.widget.TextView) texts.add(view.text.toString())
                            if(view is android.view.ViewGroup) for(index in 0 until view.childCount) collect(view.getChildAt(index))
                        }
                        collect(activity.window.decorView)
                        assertTrue(texts.any { it.contains("가상운전자") && it.contains("2건") })
                        assertTrue(texts.any { it.contains("받은 마중 요청과 픽업 대기 목록") })
                        fun findButton(view: android.view.View, label: String): android.widget.Button? {
                            if (view is android.widget.Button && view.text.toString()==label) return view
                            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
                                val found=findButton(view.getChildAt(index),label)
                                if(found!=null) return found
                            }
                            return null
                        }
                        assertEquals(android.view.View.GONE,findButton(activity.window.decorView,"운전자 계정 연결 QR 만들기")!!.visibility)
                    }
                    await {
                        var enabled=false
                        scenario.onActivity { activity -> enabled=activity.findViewById<android.widget.Button>(R.id.pickup_invite_contact).isEnabled }
                        enabled
                    }
                    fun currentDialog(activity: MainActivity): android.app.AlertDialog {
                        val views=MainActivity::class.java.getDeclaredField("views").apply { isAccessible=true }.get(activity)
                        val dialogs=VehicleViews::class.java.getDeclaredField("dialogs").apply { isAccessible=true }.get(views) as Set<*>
                        return dialogs.filterIsInstance<android.app.AlertDialog>().last { it.isShowing }
                    }
                    scenario.onActivity { activity ->
                        val section=MainActivity::class.java.getDeclaredField("accountSection").apply { isAccessible=true }.get(activity)
                        val people=VehicleAccountSection::class.java.getDeclaredField("people").apply { isAccessible=true }.get(section) as VehiclePeopleSection
                        people.open()
                        currentDialog(activity).listView.performItemClick(null,0,0)
                    }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        val dialog=currentDialog(activity)
                        listOf(android.app.AlertDialog.BUTTON_POSITIVE, android.app.AlertDialog.BUTTON_NEGATIVE).forEach { which ->
                            val button = dialog.getButton(which)
                            val parent = button.parent as android.view.ViewGroup
                            assertTrue("Dialog button top must not be clipped", button.top >= parent.paddingTop)
                            assertTrue("Dialog button bottom must not be clipped", button.bottom <= parent.height - parent.paddingBottom)
                            assertNull(button.backgroundTintList)
                        }
                        if (InstrumentationRegistry.getArguments().getString("screenshots")=="true") {
                            val view=dialog.window!!.decorView
                            val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                            view.draw(android.graphics.Canvas(bitmap))
                            File(app.filesDir,"people-search-input.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                        }
                        if (InstrumentationRegistry.getArguments().getString("screenshots")=="true") {
                            val icon=activity.packageManager.getApplicationIcon(activity.packageName)
                            val bitmap=android.graphics.Bitmap.createBitmap(256,256,android.graphics.Bitmap.Config.ARGB_8888)
                            icon.setBounds(0,0,256,256)
                            icon.draw(android.graphics.Canvas(bitmap))
                            File(app.filesDir,"vehicle-app-icon.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                        }
                        dialog.findViewById<android.widget.EditText>(R.id.people_search).setText("가상")
                        dialog.findViewById<android.widget.EditText>(R.id.people_search)
                            .onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)
                    }
                    await { !app.people.busy }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        val dialog=currentDialog(activity)
                        assertEquals(2,dialog.listView.adapter.count)
                        assertTrue(dialog.listView.adapter.getItem(1).toString().contains("MJ-2222222222"))
                        if (InstrumentationRegistry.getArguments().getString("screenshots")=="true") {
                            val view=dialog.window!!.decorView
                            val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                            view.draw(android.graphics.Canvas(bitmap))
                            File(app.filesDir,"people-search-results.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                        }
                        dialog.listView.performItemClick(null,1,1)
                        assertEquals(0,fixture.peopleRequests.get())
                        currentDialog(activity).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                    }
                    await { !app.people.busy && fixture.peopleRequests.get()==1 }
                    assertEquals("person-two",fixture.peopleTarget)
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity -> currentDialog(activity).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick() }
                    instrumentation.waitForIdleSync()
                    fun selectSecond(activity: MainActivity) {
                        val dialog=currentDialog(activity)
                        assertEquals(2, dialog.listView.adapter.count)
                        assertTrue(dialog.listView.adapter.getItem(0).toString().contains("MJ-2222222222"))
                        assertTrue(dialog.listView.adapter.getItem(1).toString().contains("MJ-3333333333"))
                        dialog.listView.performItemClick(null,1,1)
                        val confirmation=currentDialog(activity)
                        assertTrue(confirmation.findViewById<android.widget.TextView>(android.R.id.message).text.toString().contains("MJ-3333333333"))
                    }
                    if (InstrumentationRegistry.getArguments().getString("screenshots")=="true") {
                        scenario.onActivity { activity ->
                            val view=activity.window.decorView
                            val screenshot=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                            view.draw(android.graphics.Canvas(screenshot))
                            File(app.filesDir,"pickup-start-flow.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                            val root=MainActivity::class.java.getDeclaredField("parkedRoot").apply { isAccessible=true }.get(activity) as android.widget.LinearLayout
                            val originalWidth=root.width
                            val originalHeight=root.height
                            fun checkWindow(widthDp: Int, heightDp: Int, name: String) {
                                val density=activity.resources.displayMetrics.density
                                val width=(widthDp*density).toInt()
                                val height=(heightDp*density).toInt()
                                root.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(height,android.view.View.MeasureSpec.EXACTLY))
                                root.layout(0,0,width,height)
                                val button=activity.findViewById<android.widget.Button>(R.id.pickup_invite_contact)
                                assertTrue("Invite label clipped",button.layout.getLineBottom(button.layout.lineCount-1)<=button.height-button.compoundPaddingTop-button.compoundPaddingBottom)
                                val scroll=activity.findViewById<android.widget.ScrollView>(R.id.parked_controls)
                                assertTrue("Content exceeds window",scroll.right<=root.width)
                                fun columns(view: android.view.View): VehicleColumns? {
                                    if(view is VehicleColumns && view.isShown) return view
                                    if(view is android.view.ViewGroup) for(index in 0 until view.childCount) columns(view.getChildAt(index))?.let { return it }
                                    return null
                                }
                                val split=columns(root)!!
                                assertTrue(split.leading.right<=split.width && split.trailing.right<=split.width)
                                if(widthDp==900) assertTrue("Narrow actions overlap place",split.trailing.top>=split.leading.bottom)
                                val bitmap=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888)
                                root.draw(android.graphics.Canvas(bitmap))
                                File(app.filesDir,name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                            }
                            checkWindow(900,1100,"vehicle-layout-narrow.png")
                            checkWindow(1440,900,"vehicle-layout-wide.png")
                            root.measure(android.view.View.MeasureSpec.makeMeasureSpec(originalWidth,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(originalHeight,android.view.View.MeasureSpec.EXACTLY))
                            root.layout(0,0,originalWidth,originalHeight)
                        }
                    }
                    scenario.onActivity { activity ->
                        val button=activity.findViewById<android.widget.Button>(R.id.pickup_invite_contact)
                        assertTrue(button.isShown)
                        assertTrue(button.performClick())
                    }
                    await { !account.busy && account.contacts.size==2 }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        selectSecond(activity)
                        assertEquals(0,fixture.invitations.get())
                        currentDialog(activity).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                    }
                    await { !account.busy && fixture.invitations.get()==1 }
                    assertEquals("connection-second",fixture.invitedConnection)
                    fixture.failContacts.set(true)
                    scenario.onActivity { activity -> activity.findViewById<android.widget.Button>(R.id.pickup_invite_contact).performClick() }
                    await { !account.busy }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        val dialog=currentDialog(activity)
                        assertTrue(dialog.findViewById<android.widget.TextView>(android.R.id.message).text.toString().contains("초대는 보내지 않았어요"))
                        if (InstrumentationRegistry.getArguments().getString("screenshots")=="true") {
                            val view=dialog.window!!.decorView
                            val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                            view.draw(android.graphics.Canvas(bitmap))
                            File(app.filesDir,"contacts-retry.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                        }
                        fixture.failContacts.set(false)
                        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                    }
                    await { !account.busy && account.contacts.size==2 }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity -> currentDialog(activity).dismiss() }
                    assertEquals(1,fixture.invitations.get())
                    fixture.failContacts.set(true)
                    scenario.onActivity { activity -> activity.findViewById<android.widget.Button>(R.id.pickup_invite_contact).performClick() }
                    await { !account.busy }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        account.state.put("status","UNLINKED")
                        currentDialog(activity).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                    }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        assertTrue(currentDialog(activity).findViewById<android.widget.TextView>(android.R.id.message).text.toString().contains("연결 상태가 바뀌었어요"))
                        assertFalse(account.busy)
                        currentDialog(activity).dismiss()
                        account.state.put("status","LINKED")
                    }
                    fixture.failContacts.set(false)
                    fixture.contactsDelay.set(700)
                    scenario.onActivity { activity ->
                        activity.findViewById<android.widget.Button>(R.id.pickup_invite_contact).performClick()
                        assertTrue(currentDialog(activity).findViewById<android.widget.TextView>(android.R.id.message).text.toString().contains("초대할 사람"))
                        currentDialog(activity).getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
                    }
                    await { !account.busy }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        val views=MainActivity::class.java.getDeclaredField("views").apply { isAccessible=true }.get(activity)
                        val dialogs=VehicleViews::class.java.getDeclaredField("dialogs").apply { isAccessible=true }.get(views) as Set<*>
                        assertTrue(dialogs.filterIsInstance<android.app.AlertDialog>().none { it.isShowing })
                    }
                    fixture.contactsDelay.set(0)
                    fixture.emptyContacts.set(true)
                    scenario.onActivity { activity -> activity.findViewById<android.widget.Button>(R.id.pickup_invite_contact).performClick() }
                    await { !account.busy && account.contacts.isEmpty() }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity -> currentDialog(activity).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick() }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        assertTrue(currentDialog(activity).findViewById<android.widget.EditText>(R.id.people_search).isShown)
                        currentDialog(activity).dismiss()
                        val section=MainActivity::class.java.getDeclaredField("accountSection").apply { isAccessible=true }.get(activity)
                        (VehicleAccountSection::class.java.getDeclaredField("people").apply { isAccessible=true }.get(section) as VehiclePeopleSection).open()
                        currentDialog(activity).listView.performItemClick(null,2,2)
                    }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        val dialog=currentDialog(activity)
                        assertTrue(dialog.findViewById<android.widget.TextView>(android.R.id.message).text.toString().contains("차량에 연결한 계정"))
                        assertEquals("연결된 사람 보기",dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).text.toString())
                        if (InstrumentationRegistry.getArguments().getString("screenshots")=="true") {
                            val view=dialog.window!!.decorView
                            val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                            view.draw(android.graphics.Canvas(bitmap))
                            File(app.filesDir,"connections-entry.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                        }
                        app.destination.selection.invalidate()
                        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                    }
                    await { !app.people.busy }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        assertTrue(currentDialog(activity).findViewById<android.widget.TextView>(android.R.id.message).text.toString().contains("아직 연결된 사람이나 요청이 없어요"))
                        currentDialog(activity).dismiss()
                        app.destination.selection.receive("초대 시험 장소",37.3,127.3,SystemClock.elapsedRealtime())
                    }
                    fixture.emptyContacts.set(false)
                    scenario.onActivity { activity -> activity.findViewById<android.widget.Button>(R.id.pickup_invite_contact).performClick() }
                    await { !account.busy }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        selectSecond(activity)
                        app.destination.selection.invalidate()
                        currentDialog(activity).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                    }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        assertTrue(currentDialog(activity).findViewById<android.widget.TextView>(android.R.id.message).text.toString().contains("만날 장소가 바뀌었거나"))
                        assertEquals(1,fixture.invitations.get())
                        currentDialog(activity).dismiss()
                    }
                    fun captureTab(key: String, filename: String) {
                        var moving=false
                        scenario.onActivity { activity ->
                            val tabs=MainActivity::class.java.getDeclaredField("tabs").apply { isAccessible=true }.get(activity) as Map<*, *>
                            (tabs[key] as android.widget.Button).performClick()
                        }
                        await {
                            var settled=false
                            scenario.onActivity { activity ->
                                val selected=MainActivity::class.java.getDeclaredField("selectedTab").apply { isAccessible=true }.get(activity)
                                val view=activity.findViewById<android.view.View>(R.id.parked_controls)
                                if (view.translationX!=0f) moving=true
                                settled=selected==key && view.alpha==1f && view.translationX==0f
                            }
                            settled
                        }
                        if (android.animation.ValueAnimator.areAnimatorsEnabled()) assertTrue("Expected a slide between distinct menus",moving)
                        instrumentation.waitForIdleSync()
                        scenario.onActivity { activity ->
                            fun checkButtonShape(view: android.view.View) {
                                if (view is android.widget.Button) {
                                    assertNull("Default button animator must not cast a square shadow",view.stateListAnimator)
                                    assertEquals(0f,view.elevation,0f)
                                    assertEquals(0f,view.translationZ,0f)
                                }
                                if (view is android.view.ViewGroup) for(index in 0 until view.childCount) checkButtonShape(view.getChildAt(index))
                            }
                            checkButtonShape(activity.window.decorView)
                            val parked=activity.findViewById<android.view.View>(R.id.parked_controls)
                            assertTrue(parked.isShown)
                            if (InstrumentationRegistry.getArguments().getString("screenshots")=="true") {
                                val view=activity.window.decorView
                                val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                                view.draw(android.graphics.Canvas(bitmap))
                                File(app.filesDir,filename).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                            }
                        }
                    }
                    scenario.onActivity { activity ->
                        val tabs=MainActivity::class.java.getDeclaredField("tabs").apply { isAccessible=true }.get(activity) as Map<*, *>
                        listOf("settings","requests","pickup","settings").forEach { (tabs[it] as android.widget.Button).performClick() }
                    }
                    captureTab("requests", "vehicle-requests-layout.png")
                    captureTab("settings", "vehicle-settings-layout.png")
                    await { !account.busy }
                    lateinit var refreshButton: android.widget.Button
                    var buttonTop = 0
                    var screenScroll = 0
                    var readsBefore = 0
                    scenario.onActivity { activity ->
                        val section=MainActivity::class.java.getDeclaredField("accountSection").apply { isAccessible=true }.get(activity)
                        refreshButton=VehicleAccountSection::class.java.getDeclaredField("refresh").apply { isAccessible=true }.get(section) as android.widget.Button
                        buttonTop=refreshButton.top
                        screenScroll=activity.findViewById<android.view.View>(R.id.parked_controls).scrollY
                        fixture.accountDelay.set(500)
                        readsBefore=fixture.accountReads.get()
                        refreshButton.performClick()
                        assertTrue(account.refreshing)
                        assertTrue(refreshButton.isEnabled)
                        assertEquals("새로운 정보 확인 중…",refreshButton.text.toString())
                        assertTrue(account.message.contains("가상운전자"))
                        refreshButton.performClick()
                    }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        assertEquals(buttonTop,refreshButton.top)
                        assertEquals(screenScroll,activity.findViewById<android.view.View>(R.id.parked_controls).scrollY)
                    }
                    await { !account.busy }
                    assertEquals(readsBefore+1,fixture.accountReads.get())
                    assertFalse(account.refreshing)
                    fixture.accountDelay.set(0)
                    scenario.onActivity {
                        assertEquals("운전자 연결 상태 새로고침",refreshButton.text.toString())
                    }

                    scenario.onActivity { activity ->
                        val section=MainActivity::class.java.getDeclaredField("accountSection").apply { isAccessible=true }.get(activity)
                        (VehicleAccountSection::class.java.getDeclaredField("people").apply { isAccessible=true }.get(section) as VehiclePeopleSection).open()
                        assertTrue(currentDialog(activity).isShowing)
                        // A lost system policy must hide the rail as well as content, and close open dialogs.
                        val monitor=MainActivity::class.java.getDeclaredField("driving").apply { isAccessible=true }.get(activity) as DrivingRestrictionMonitor
                        monitor.stop()
                        MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible=true }.invoke(activity)
                        val tabs=MainActivity::class.java.getDeclaredField("tabs").apply { isAccessible=true }.get(activity) as Map<*, *>
                        assertTrue(tabs.values.all { !(it as android.widget.Button).isShown && !it.isEnabled })
                        assertTrue(!activity.findViewById<android.view.View>(R.id.parked_controls).isShown)
                        assertTrue(activity.findViewById<android.view.View>(R.id.driving_summary).isShown)
                        val views=MainActivity::class.java.getDeclaredField("views").apply { isAccessible=true }.get(activity)
                        val dialogs=VehicleViews::class.java.getDeclaredField("dialogs").apply { isAccessible=true }.get(views) as Set<*>
                        assertTrue(dialogs.filterIsInstance<android.app.AlertDialog>().none { it.isShowing })
                    }
                }
            } finally {
                instrumentation.runOnMainSync { peopleField.set(app,oldPeople); registrationField.set(app,oldRegistration); accountField.set(app,oldAccount); pickupField.set(app,oldPickup); destinationField.set(app,oldDestination) }
            }
        }
    }
    private class Fixture: AutoCloseable {
        val accountDelay = java.util.concurrent.atomic.AtomicLong(0)
        val accountReads = AtomicInteger()
        val linked=AtomicBoolean(false); val claimed=AtomicBoolean(false); val authorityCorrect=AtomicBoolean(true)
        val failAccept=AtomicBoolean(false); val malformed=AtomicBoolean(false)
        val invitations=AtomicInteger(); val invitationIds=mutableListOf<String>()
        val peopleRequests=AtomicInteger()
        var peopleTarget: String?=null
        var peopleDriver: String?=null
        val twoContacts=AtomicBoolean(false)
        val emptyContacts=AtomicBoolean(false)
        val failContacts=AtomicBoolean(false)
        val contactsDelay=java.util.concurrent.atomic.AtomicLong(0)
        var invitedConnection: String?=null
        val failInvitation=AtomicBoolean(false)
        val confirmations=AtomicInteger(); val accepts=AtomicInteger()
        private val server=ServerSocket(0)
        val origin="http://127.0.0.1:${server.localPort}"
        private val running=AtomicBoolean(true)
        var challenge=UUID.randomUUID().toString()
        private var queued=false
        private val worker=Thread {
            while(running.get()) try { server.accept().use { socket ->
                val input=socket.getInputStream()
                fun line(): String { val bytes=java.io.ByteArrayOutputStream(); while(true) { val value=input.read(); if(value<0 || value==10)break; if(value!=13)bytes.write(value) }; return bytes.toString("UTF-8") }
                val request=line(); if(request.isBlank())return@use
                val headers=mutableMapOf<String,String>(); while(true) { val header=line(); if(header.isBlank())break; val fields=header.split(":",limit=2); if(fields.size==2)headers[fields[0].lowercase()]=fields[1].trim() }
                val length=headers["content-length"]?.toIntOrNull()?:0
                val content=String(input.readNBytes(length),Charsets.UTF_8)
                val path=request.split(" ")[1]
                if(path!="/api/v1/config" && headers["authorization"]!="Bearer "+"V".repeat(43)) authorityCorrect.set(false)
                if (path.endsWith("/account-link")) {
                    accountReads.incrementAndGet()
                    SystemClock.sleep(accountDelay.get())
                }
                var status=200
                val body=when {
                    path=="/api/v1/config" -> "{\"developmentToolsEnabled\":false}"
                    path=="/api/v1/vehicles/me" -> "{\"vehicleId\":\"vehicle\"}"
                    path.startsWith("/api/v1/vehicles/people/search?") -> JSONObject().put("items",org.json.JSONArray()
                        .put(JSONObject().put("memberId","person-one").put("nickname","가상회원").put("majungId","MJ-1111111111"))
                        .put(JSONObject().put("memberId","person-two").put("nickname","가상회원").put("majungId","MJ-2222222222"))).toString()
                    path=="/api/v1/vehicles/people/connections" && request.startsWith("POST") -> {
                        val posted=JSONObject(content)
                        peopleTarget=posted.getString("memberId"); peopleDriver=posted.getString("driverMajungId"); peopleRequests.incrementAndGet()
                        JSONObject().put("connection",JSONObject().put("connectionId","people-connection").put("status","PENDING").put("nickname","가상회원")).toString()
                    }
                    path.startsWith("/api/v1/vehicles/people/connections?") -> JSONObject().put("items",org.json.JSONArray()).toString()
                    path.startsWith("/api/v1/vehicles/connections?") -> {
                        SystemClock.sleep(contactsDelay.get())
                        val items=org.json.JSONArray().put(JSONObject().put("connectionId","connection-test").put("nickname","가상탑승자B").put("majungId","MJ-2222222222"))
                        if(twoContacts.get()) items.put(JSONObject().put("connectionId","connection-second").put("nickname","가상탑승자C").put("majungId","MJ-3333333333"))
                        if(failContacts.get()) { status=503; "{\"code\":\"TEST_UNAVAILABLE\",\"message\":\"시험 연결 오류\"}" }
                        else JSONObject().put("items",if(emptyContacts.get()) org.json.JSONArray() else items).toString()
                    }
                    path.endsWith("/invitations") -> {
                        val posted=JSONObject(content); invitedConnection=posted.getString("connectionId"); invitations.incrementAndGet(); invitationIds.add(posted.getString("requestId"))
                        if(failInvitation.get()) { status=503; "{\"code\":\"TEMPORARY\"}" } else "{}"
                    }
                    path.endsWith("/challenges") -> if(malformed.get()) "{}" else "{\"challengeId\":\"$challenge\",\"code\":\"${"Q".repeat(43)}\"}"
                    path.endsWith("/confirm") -> { confirmations.incrementAndGet(); linked.set(true); "{\"status\":\"LINKED\",\"nickname\":\"가상운전자\",\"majungId\":\"MJ-0000000000\"}" }
                    path.endsWith("/account-link") -> if(linked.get()) "{\"status\":\"LINKED\",\"nickname\":\"가상운전자\",\"majungId\":\"MJ-0000000000\"}" else "{\"status\":\"${if(claimed.get()) "AWAITING_VEHICLE_CONFIRMATION" else "AWAITING_ACCOUNT"}\",\"challengeId\":\"$challenge\",\"nickname\":\"가상운전자\",\"majungId\":\"MJ-0000000000\"}"
                    path.endsWith("/accept") -> { accepts.incrementAndGet(); if(failAccept.get()) { status=503; "{\"code\":\"TEMPORARY\"}" } else { queued=true; "{}" } }
                    path.endsWith("/ride-requests") -> "{\"requests\":[{\"requestId\":\"request-one\",\"nickname\":\"가상탑승자A\",\"majungId\":\"MJ-1111111111\",\"place\":{\"label\":\"시험 장소 A\",\"latitude\":37.1,\"longitude\":127.1},\"status\":\"ACTIVE\"},{\"requestId\":\"request-two\",\"nickname\":\"가상탑승자B\",\"majungId\":\"MJ-2222222222\",\"place\":{\"label\":\"시험 장소 B\",\"latitude\":37.2,\"longitude\":127.2},\"status\":\"${if(queued) "QUEUED" else "PENDING"}\"}]}"
                    else -> "{}"
                }
                val bytes=body.toByteArray(); socket.getOutputStream().apply { write("HTTP/1.1 $status OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush() }
            } } catch(_:Exception) { }
        }.apply { isDaemon=true; start() }
        override fun close() { running.set(false); server.close(); worker.join(1000) }
    }
}
