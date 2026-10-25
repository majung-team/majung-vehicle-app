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
            instrumentation.runOnMainSync { account.confirm() }; await { !account.busy }
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
    @Test fun vehicleScreenShowsLinkedDriverAndMultipleRequests() {
        Fixture().use { fixture ->
            fixture.linked.set(true)
            val api=PickupApiClient(fixture.origin); val registration=registered(api)
            val app=androidx.test.core.app.ApplicationProvider.getApplicationContext<VehicleApplication>()
            val registrationField=VehicleApplication::class.java.getDeclaredField("registration").apply { isAccessible=true }
            val accountField=VehicleApplication::class.java.getDeclaredField("account").apply { isAccessible=true }
            val pickupField=VehicleApplication::class.java.getDeclaredField("pickup").apply { isAccessible=true }
            val oldRegistration=app.registration; val oldAccount=app.account; val oldPickup=app.pickup
            lateinit var account: VehicleAccountController
            instrumentation.runOnMainSync {
                account=VehicleAccountController(api,registration)
                registrationField.set(app,registration); accountField.set(app,account)
                pickupField.set(app,PickupController(api,MemoryPickupSessionStore(),registration))
                account.refresh()
            }
            await { !account.busy }
            try {
                androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        val texts=mutableListOf<String>()
                        fun collect(view:android.view.View) {
                            if(view is android.widget.TextView) texts.add(view.text.toString())
                            if(view is android.view.ViewGroup) for(index in 0 until view.childCount) collect(view.getChildAt(index))
                        }
                        collect(activity.window.decorView)
                        assertTrue(texts.any { it.contains("가상운전자") && it.contains("2건") })
                        assertTrue(texts.any { it.contains("받은 마중 요청과 픽업 대기 목록") })
                        fun openRequests(view:android.view.View): Boolean {
                            if(view is android.widget.Button && view.text.toString()=="받은 마중 요청과 픽업 대기 목록") { view.performClick(); return true }
                            if(view is android.view.ViewGroup) for(index in 0 until view.childCount) if(openRequests(view.getChildAt(index))) return true
                            return false
                        }
                        assertTrue(openRequests(activity.window.decorView))
                    }
                    if (InstrumentationRegistry.getArguments().getString("screenshots")=="true") {
                        val screenshot=instrumentation.uiAutomation.takeScreenshot()
                        File(app.filesDir,"vehicle-account-requests.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                    }
                }
            } finally {
                instrumentation.runOnMainSync { registrationField.set(app,oldRegistration); accountField.set(app,oldAccount); pickupField.set(app,oldPickup) }
            }
        }
    }
    private class Fixture: AutoCloseable {
        val linked=AtomicBoolean(false); val claimed=AtomicBoolean(false); val authorityCorrect=AtomicBoolean(true)
        val failAccept=AtomicBoolean(false); val malformed=AtomicBoolean(false)
        val confirmations=AtomicInteger(); val accepts=AtomicInteger()
        private val server=ServerSocket(0)
        val origin="http://127.0.0.1:${server.localPort}"
        private val running=AtomicBoolean(true)
        private val challenge=UUID.randomUUID().toString()
        private var queued=false
        private val worker=Thread {
            while(running.get()) try { server.accept().use { socket ->
                val reader=socket.getInputStream().bufferedReader(); val request=reader.readLine()?:return@use
                val headers=mutableMapOf<String,String>(); while(true) { val line=reader.readLine()?:break; if(line.isBlank())break; val fields=line.split(":",limit=2); if(fields.size==2)headers[fields[0].lowercase()]=fields[1].trim() }
                val length=headers["content-length"]?.toIntOrNull()?:0; repeat(length) { reader.read() }
                val path=request.split(" ")[1]
                if(path!="/api/v1/config" && headers["authorization"]!="Bearer "+"V".repeat(43)) authorityCorrect.set(false)
                var status=200
                val body=when {
                    path=="/api/v1/config" -> "{\"developmentToolsEnabled\":false}"
                    path=="/api/v1/vehicles/me" -> "{\"vehicleId\":\"vehicle\"}"
                    path.endsWith("/challenges") -> if(malformed.get()) "{}" else "{\"challengeId\":\"$challenge\",\"code\":\"${"Q".repeat(43)}\"}"
                    path.endsWith("/confirm") -> { confirmations.incrementAndGet(); linked.set(true); "{\"status\":\"LINKED\",\"nickname\":\"가상운전자\",\"majungId\":\"MJ-0000000000\"}" }
                    path.endsWith("/account-link") -> if(linked.get()) "{\"status\":\"LINKED\",\"nickname\":\"가상운전자\",\"majungId\":\"MJ-0000000000\"}" else "{\"status\":\"${if(claimed.get()) "AWAITING_VEHICLE_CONFIRMATION" else "AWAITING_ACCOUNT"}\",\"challengeId\":\"$challenge\"}"
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
