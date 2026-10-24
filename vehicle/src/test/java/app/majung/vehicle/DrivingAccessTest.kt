package app.majung.vehicle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingAccessTest {
    @Test fun unknownRestrictedAndDisconnectedNeverAllowActions() {
        val access = DrivingAccess()
        assertFalse(access.allowed)
        access.update(true, false, 0)
        assertTrue(access.allowed)
        access.update(true, true, 0)
        assertFalse(access.allowed)
        access.update(true, false, 1)
        assertFalse(access.allowed)
        access.update(true, false, 0)
        access.unavailable()
        assertFalse(access.allowed)
        access.update(false, false, 0)
        assertFalse(access.allowed)
    }
}
