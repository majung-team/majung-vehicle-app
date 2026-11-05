package app.majung.vehicle


import app.majung.vehicle.*
import app.majung.vehicle.ui.*
import app.majung.vehicle.ui.sections.*
import app.majung.vehicle.navi.*
import app.majung.vehicle.domain.*
import app.majung.vehicle.data.api.*
import app.majung.vehicle.data.storage.*
import app.majung.vehicle.util.*
import app.majung.vehicle.service.*

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Synthetic UI only; no production pickup or invitation is created. */
class PickupActionDockTest {
    @Test fun primaryActionRemainsVisibleWhenPickupDetailsScroll() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as VehicleApplication
        val fields = listOf("pickup", "registration", "account", "destination").map {
            VehicleApplication::class.java.getDeclaredField(it).apply { isAccessible = true }
        }
        val originals = fields.map { it.get(app) }
        val accepted = app.sharingNotice.accepted
        fun set(target: Any, name: String, value: Any?) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
        val api = PickupApiClient("http://127.0.0.1:1")
        val store = object : VehicleCredentialStore {
            override fun load(): VehicleCredential? = null
            override fun save(credential: VehicleCredential) {}
            override fun clear() {}
        }
        val registration = VehicleRegistrationController(api, store)
        val account = VehicleAccountController(api, registration)
        val pickup = PickupController(api, MemoryPickupSessionStore(), registration)
        var activity: MainActivity? = null
        try {
            app.sharingNotice.accept()
            instrumentation.runOnMainSync {
                fields.zip(listOf(pickup, registration, account, DestinationReader(app))).forEach { (field,value)->field.set(app,value) }
                set(registration,"development",true); set(registration,"registrationChecked",true)
                set(pickup,"pickupId","display-only"); set(pickup,"status","ACTIVE"); set(pickup,"online",true)
                set(pickup,"place",PickupPlace("시연역 1번 출구",37.5,127.0))
            }
            activity = instrumentation.startActivitySync(android.content.Intent(app,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val deadline=SystemClock.elapsedRealtime()+15000
            var visible=false
            while (!visible && SystemClock.elapsedRealtime()<deadline) {
                instrumentation.runOnMainSync { visible=activity!!.findViewById<View>(R.id.arrive_pickup).isShown }
                if (!visible) SystemClock.sleep(100)
            }
            assertTrue("Stationary primary action should appear",visible)
            SystemClock.sleep(1000)
            instrumentation.waitForIdleSync()
            var original=android.graphics.Rect()
            instrumentation.runOnMainSync {
                val button=activity!!.findViewById<Button>(R.id.arrive_pickup)
                assertTrue(button.getGlobalVisibleRect(original))
                assertEquals("Primary action must not be horizontally clipped",button.width,original.width())
                assertEquals("Primary action must not be vertically clipped",button.height,original.height())
                val scroll=activity!!.findViewById<android.widget.ScrollView>(R.id.parked_controls)
                scroll.fullScroll(View.FOCUS_DOWN)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val bounds=android.graphics.Rect()
                val button=activity!!.findViewById<Button>(R.id.arrive_pickup)
                assertTrue(button.getGlobalVisibleRect(bounds)); assertEquals(original,bounds)
                assertTrue(button.isEnabled)
                set(pickup,"status","ARRIVED")
                MainActivity::class.java.getDeclaredMethod("render").apply {isAccessible=true}.invoke(activity)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(activity!!.findViewById<Button>(R.id.complete_pickup).isShown)
                assertFalse(activity!!.findViewById<Button>(R.id.arrive_pickup).isShown)
            }
            File(app.filesDir,"pickup-action-dock.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)
            }
        } finally {
            // This test checks layout, not the emulator's delayed DESTROYED lifecycle transition.
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { fields.zip(originals).forEach { (field,value)->field.set(app,value) } }
            if (accepted) app.sharingNotice.accept() else app.sharingNotice.withdraw()
        }
    }
}
