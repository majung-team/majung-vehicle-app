package app.majung.vehicle.domain


import app.majung.vehicle.*
import app.majung.vehicle.ui.*
import app.majung.vehicle.ui.sections.*
import app.majung.vehicle.navi.*
import app.majung.vehicle.domain.*
import app.majung.vehicle.data.api.*
import app.majung.vehicle.data.storage.*
import app.majung.vehicle.util.*
import app.majung.vehicle.service.*

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingAccessTest {
    @Test fun unknownRestrictedAndDisconnectedNeverAllowActions() {
        val access = DrivingAccess()
        assertFalse(access.known)
        assertFalse(access.allowed)
        access.update(true, false, 0)
        assertTrue(access.known)
        assertTrue(access.allowed)
        access.update(true, true, 0)
        assertFalse(access.allowed)
        access.update(true, false, 1)
        assertFalse(access.allowed)
        access.update(true, false, 0)
        access.unavailable()
        assertFalse(access.known)
        assertFalse(access.allowed)
        access.update(false, false, 0)
        assertFalse(access.known)
        assertFalse(access.allowed)
    }
}
