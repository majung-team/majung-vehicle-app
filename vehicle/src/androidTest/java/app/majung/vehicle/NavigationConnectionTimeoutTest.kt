package app.majung.vehicle

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.majung.vehicle.navi.DestinationReader
import app.majung.vehicle.navi.connection.NavigationFailure
import org.junit.Assert.*
import org.junit.Test

class NavigationConnectionTimeoutTest {
    @Test
    fun missingInitializationCallbackReleasesHelperAndClosesPlaceGate() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<VehicleApplication>()
            val reader = DestinationReader(context)
            val connection =
                DestinationReader::class
                    .java
                    .getDeclaredField("sdkConnection")
                    .apply { isAccessible = true }
                    .get(reader)
            val helper =
                connection.javaClass.getDeclaredField("helper").apply { isAccessible = true }
            helper.set(connection, ai.pleos.playground.navi.helper.NaviHelper(context))
            val generation =
                connection.javaClass.getDeclaredField("generation").apply { isAccessible = true }
            val before = generation.getInt(connection)
            val deadline =
                connection.javaClass
                    .getDeclaredField("connectionTimeout")
                    .apply { isAccessible = true }
                    .get(connection) as Runnable
            deadline.run()
            assertEquals(NavigationFailure.CONNECTION_TIMEOUT, reader.failure)
            assertNull(helper.get(connection))
            assertTrue(generation.getInt(connection) > before)
            assertNull(reader.currentStops())
            assertNull(reader.selection.current(android.os.SystemClock.elapsedRealtime()))
            deadline.run()
            assertEquals(NavigationFailure.CONNECTION_TIMEOUT, reader.failure)
            // Late completion of a successful connection must not be disconnected by its old timer.
            helper.set(connection, ai.pleos.playground.navi.helper.NaviHelper(context))
            connection.javaClass
                .getDeclaredField("connected")
                .apply { isAccessible = true }
                .setBoolean(connection, true)
            deadline.run()
            assertNotNull(helper.get(connection))
            reader.disconnect()
        }
    }
}
