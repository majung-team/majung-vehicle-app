package app.majung.vehicle.notification

import android.Manifest
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.majung.vehicle.VehicleApplication
import app.majung.vehicle.data.storage.MemoryPickupSessionStore
import app.majung.vehicle.feature.pickup.controller.PickupController
import app.majung.vehicle.navi.PickupPlace
import org.junit.Assert.*
import org.junit.Test

class PickupAlertsTest {
    @Test
    fun messageAndProposalKeepTheirChannelsActionsAndEndCleanup() {
        val main = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<VehicleApplication>()
        main.uiAutomation.grantRuntimePermission(
            app.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        val manager = app.getSystemService(NotificationManager::class.java)
        val pickupField =
            VehicleApplication::class.java.getDeclaredField("pickup").apply { isAccessible = true }
        lateinit var fixture: PickupController
        val original = app.pickup
        val voiceEnabled = app.voice.enabled
        val resumed = app.activityResumed
        try {
            main.runOnMainSync {
                app.voice.setEnabled(false)
                app.activityResumed = true
                fixture = PickupController(sessionStore = MemoryPickupSessionStore())
                pickupField.set(app, fixture)
                fun set(name: String, value: Any?) =
                    PickupController::class
                        .java
                        .getDeclaredField(name)
                        .apply { isAccessible = true }
                        .set(fixture, value)
                set("status", "ACTIVE")
                set("proposalId", "test-proposal")
                set("placeProposal", PickupPlace("시험 장소", 37.0, 127.0))
                val alerts = PickupAlerts(app)
                alerts.notifyQuickMessage("지금 내려가요")
                alerts.notifyPassengerEvents()
                val messages = manager.activeNotifications.associateBy { it.id }
                val quick =
                    messages
                        .getValue(VehicleApplication.ALERT_QUICK_MSG_NOTIFICATION_ID)
                        .notification
                assertEquals(VehicleApplication.ALERT_CHANNEL, quick.channelId)
                assertTrue(
                    quick.extras.getCharSequence("android.text").toString().contains("지금 내려가요")
                )
                assertEquals("확인", quick.actions.single().title.toString())
                val proposal =
                    messages
                        .getValue(VehicleApplication.ALERT_PROPOSAL_NOTIFICATION_ID)
                        .notification
                assertEquals("수락 및 내비 변경", proposal.actions.single().title.toString())
                assertEquals(
                    NotificationManager.IMPORTANCE_HIGH,
                    manager.getNotificationChannel(VehicleApplication.ALERT_CHANNEL).importance,
                )
                set("status", "COMPLETED")
                alerts.notifyPassengerEvents()
                assertFalse(
                    manager.activeNotifications.any {
                        it.id == VehicleApplication.ALERT_PROPOSAL_NOTIFICATION_ID
                    }
                )
            }
        } finally {
            main.runOnMainSync {
                pickupField.set(app, original)
                app.activityResumed = resumed
                app.voice.setEnabled(voiceEnabled)
                manager.cancel(VehicleApplication.ALERT_PROPOSAL_NOTIFICATION_ID)
                manager.cancel(VehicleApplication.ALERT_QUICK_MSG_NOTIFICATION_ID)
            }
        }
    }
}
