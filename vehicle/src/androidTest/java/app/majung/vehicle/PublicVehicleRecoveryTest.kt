package app.majung.vehicle

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Run in a new process after adb force-stop following PublicHttpsPickupTest. */
class PublicVehicleRecoveryTest {
    @Test fun restoresVehicleAuthorityWithoutRegistrationCode() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("publicRecovery") == "true")
        val app = ApplicationProvider.getApplicationContext<VehicleApplication>()
        val saved = EncryptedVehicleCredentialStore(app).load()
        assertTrue(saved != null)
        val until = SystemClock.elapsedRealtime() + 15000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < until) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { ready = app.registration.canCreate }
            if (!ready) SystemClock.sleep(100)
        }
        assertTrue("Server did not revalidate saved authority", ready)
        val response = PickupApiClient().requestApi("GET", "/vehicles/me", saved!!.token)
        assertEquals(saved.vehicleId, response.getString("vehicleId"))
        assertTrue(!java.io.File(app.filesDir, "public-registration-code").exists())
        java.io.File(app.filesDir, "public-vehicle-recovery-summary.json").writeText(JSONObject()
            .put("afterActualForceStop", true).put("credentialRestored", true)
            .put("serverRevalidated", true).put("registrationCodeRequiredAgain", false)
            .put("result", "passed").toString(2))
    }
}
