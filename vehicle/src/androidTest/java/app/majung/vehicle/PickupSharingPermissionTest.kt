package app.majung.vehicle

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.net.URI
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Run separately after revoking location permission. The pickup place is a synthetic fixture. */
class PickupSharingPermissionTest {
    @Test fun approvedPickupDoesNotTransmitWithoutLocationPermission() {
        val main = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<VehicleApplication>()
        val api = PickupApiClient()
        assumeTrue("Run separately with location permission revoked", !PickupSharingService.hasLocationPermission(app))
        ActivityScenario.launch(MainActivity::class.java).use {
            await { app.pickup.canCreate }
            main.runOnMainSync { app.pickup.create(PickupPlace("권한 확인 시험", 37.0, 127.0)) }
            await { app.pickup.pickupId != null && !app.pickup.busy }
            var invitation: String? = null
            main.runOnMainSync { invitation = app.pickup.invitationForHostBrowser() }
            val parts = URI(invitation!!).fragment.split('/')
            val id = parts[1]
            val guest = api.request("POST", "/$id/participants", body = JSONObject()
                .put("inviteToken", parts[2]).put("displayName", "권한 시험"))
            try {
                main.runOnMainSync { app.pickup.refresh() }
                await { app.pickup.canApprove }
                main.runOnMainSync { app.pickup.approve() }
                await { app.pickup.status == "ACTIVE" && !app.pickup.busy }
                SystemClock.sleep(6000)
                assertTrue(!app.sharingRunning)
                assertTrue(EncryptedPickupSessionStore(app).load()?.vehicleSequence == 0L)
                val state = api.request("GET", "/$id/vehicle-state", guest.getString("participantToken"))
                assertTrue(state.isNull("latitude") && state.isNull("durationSeconds"))
            } finally {
                main.runOnMainSync { app.pickup.cancel() }
                await { app.pickup.status == "CANCELLED" && !app.pickup.busy }
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Permission test did not reach expected state")
    }
}
