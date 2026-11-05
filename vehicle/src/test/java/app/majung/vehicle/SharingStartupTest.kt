package app.majung.vehicle

import app.majung.vehicle.domain.SharingStartup
import org.junit.Assert.assertEquals
import org.junit.Test

class SharingStartupTest {
    @Test fun firstPickupRequestsPermissionAndGrantedPermissionStartsWithoutAnotherButton() {
        assertEquals(SharingStartup.Action.REQUEST_PERMISSION, action(hasLocation=false, asked=false))
        assertEquals(SharingStartup.Action.START, action(hasLocation=true, asked=true))
    }
    @Test fun denialDoesNotLoopButSettingsGrantCanResume() {
        repeat(20) { assertEquals(SharingStartup.Action.NONE, action(hasLocation=false, asked=true)) }
        assertEquals(SharingStartup.Action.START, action(hasLocation=true, asked=true))
    }
    @Test fun restoredPickupDrivingAndBackgroundNeverStartOrPrompt() {
        assertEquals(SharingStartup.Action.NONE, action(confirmation=true))
        assertEquals(SharingStartup.Action.NONE, action(parked=false))
        assertEquals(SharingStartup.Action.NONE, action(resumed=false))
        assertEquals(SharingStartup.Action.NONE, action(active=false))
        assertEquals(SharingStartup.Action.NONE, action(pending=true))
        assertEquals(SharingStartup.Action.NONE, action(running=true))
    }
    private fun action(hasLocation:Boolean=false, asked:Boolean=false, confirmation:Boolean=false,
                       parked:Boolean=true,resumed:Boolean=true,active:Boolean=true,pending:Boolean=false,running:Boolean=false) =
        SharingStartup.action(resumed,parked,active,confirmation,running,pending,hasLocation,asked)
}
