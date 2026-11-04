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

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class NavigationConnectionTimeoutTest {
    @Test fun missingInitializationCallbackReleasesHelperAndClosesPlaceGate() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<VehicleApplication>()
            val reader = DestinationReader(context)
            val helper = DestinationReader::class.java.getDeclaredField("helper").apply { isAccessible = true }
            helper.set(reader, ai.pleos.playground.navi.helper.NaviHelper(context))
            val generation = DestinationReader::class.java.getDeclaredField("generation").apply { isAccessible = true }
            val before = generation.getInt(reader)
            val deadline = DestinationReader::class.java.getDeclaredField("connectionTimeout").apply { isAccessible = true }.get(reader) as Runnable
            deadline.run()
            assertEquals(NavigationFailure.CONNECTION_TIMEOUT, reader.failure)
            assertNull(helper.get(reader))
            assertTrue(generation.getInt(reader) > before)
            assertNull(reader.currentStops())
            assertNull(reader.selection.current(android.os.SystemClock.elapsedRealtime()))
            deadline.run()
            assertEquals(NavigationFailure.CONNECTION_TIMEOUT, reader.failure)
            // Late completion of a successful connection must not be disconnected by its old timer.
            helper.set(reader, ai.pleos.playground.navi.helper.NaviHelper(context))
            DestinationReader::class.java.getDeclaredField("connected").apply { isAccessible = true }.setBoolean(reader, true)
            deadline.run()
            assertNotNull(helper.get(reader))
            reader.disconnect()
        }
    }
}
