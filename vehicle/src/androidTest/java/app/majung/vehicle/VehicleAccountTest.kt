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
                        dialog.findViewById<android.widget.EditText>(R.id.people_search).setText("가상")
                        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
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
                    fixture.emptyContacts.set(true)
                    scenario.onActivity { activity -> activity.findViewById<android.widget.Button>(R.id.pickup_invite_contact).performClick() }
                    await { !account.busy && account.contacts.isEmpty() }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity -> currentDialog(activity).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick() }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity -> currentDialog(activity).listView.performItemClick(null,2,2) }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        val dialog=currentDialog(activity)
                        assertTrue(dialog.findViewById<android.widget.TextView>(android.R.id.message).text.toString().contains("차량에 연결한 계정"))
                        assertEquals("연결 목록 다시 확인",dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).text.toString())
                        if (InstrumentationRegistry.getArguments().getString("screenshots")=="true") {
                            val view=dialog.window!!.decorView
                            val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                            view.draw(android.graphics.Canvas(bitmap))
                            File(app.filesDir,"connections-entry.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                        }
                        dialog.dismiss()
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
                    }
                }
            } finally {
                instrumentation.runOnMainSync { peopleField.set(app,oldPeople); registrationField.set(app,oldRegistration); accountField.set(app,oldAccount); pickupField.set(app,oldPickup); destinationField.set(app,oldDestination) }
            }
        }
    }
    private class Fixture: AutoCloseable {
        val linked=AtomicBoolean(false); val claimed=AtomicBoolean(false); val authorityCorrect=AtomicBoolean(true)
        val failAccept=AtomicBoolean(false); val malformed=AtomicBoolean(false)
        val invitations=AtomicInteger(); val invitationIds=mutableListOf<String>()
        val peopleRequests=AtomicInteger()
        var peopleTarget: String?=null
        var peopleDriver: String?=null
        val twoContacts=AtomicBoolean(false)
        val emptyContacts=AtomicBoolean(false)
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
                        val items=org.json.JSONArray().put(JSONObject().put("connectionId","connection-test").put("nickname","가상탑승자B").put("majungId","MJ-2222222222"))
                        if(twoContacts.get()) items.put(JSONObject().put("connectionId","connection-second").put("nickname","가상탑승자C").put("majungId","MJ-3333333333"))
                        JSONObject().put("items",if(emptyContacts.get()) org.json.JSONArray() else items).toString()
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
