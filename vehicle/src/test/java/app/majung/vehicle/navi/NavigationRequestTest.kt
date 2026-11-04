package app.majung.vehicle.navi


import app.majung.vehicle.*
import app.majung.vehicle.ui.*
import app.majung.vehicle.ui.sections.*
import app.majung.vehicle.navi.*
import app.majung.vehicle.domain.*
import app.majung.vehicle.data.api.*
import app.majung.vehicle.data.storage.*
import app.majung.vehicle.util.*
import app.majung.vehicle.service.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationRequestTest {
    @Test fun failedMutationIsNotRetriedAndFailureDoesNotExposeExceptionText() {
        val cases = listOf(
            SecurityException("private token") to NavigationFailure.PERMISSION_DENIED,
            IllegalArgumentException("private coordinates") to NavigationFailure.INVALID_REQUEST,
            IllegalStateException("private payload") to NavigationFailure.DISCONNECTED,
            RuntimeException("private address") to NavigationFailure.SDK_REQUEST_FAILED
        )
        cases.forEach { (exception, expected) ->
            var attempts = 0
            val failures = mutableListOf<NavigationFailure>()
            val successful = NavigationRequest.execute({ attempts++; throw exception }, failures::add)
            assertFalse(successful)
            assertEquals(1, attempts)
            assertEquals(listOf(expected), failures)
            assertFalse(failures.single().message.contains("private"))
        }
    }

    @Test fun successfulRequestDoesNotReportFailure() {
        var completed = false
        assertTrue(NavigationRequest.execute({ completed = true }) { error("Unexpected failure") })
        assertTrue(completed)
    }

    @Test(expected = AssertionError::class)
    fun fatalErrorsAreNotDisguisedAsRecoverableSdkFailures() {
        NavigationRequest.execute({ throw AssertionError("fatal") }) { error("Must not handle Error") }
    }
}
