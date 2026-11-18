package app.majung.vehicle.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PickupSessionPersistenceTest {
    private val session = SavedPickupSession("pickup", "test-owner", null, 12, "test-reference")

    private class Store : PickupSessionStore {
        var writes = 0
        var failWrite = false
        var failClear = false
        var value: SavedPickupSession? = null

        override fun load() = value

        override fun save(session: SavedPickupSession) {
            writes++
            check(!failWrite)
            value = session
        }

        override fun clear() {
            check(!failClear)
            value = null
        }
    }

    @Test
    fun failedWriteIsRetriedAndDoesNotPopulateCache() {
        val store = Store()
        val persistence = PickupSessionPersistence(store)
        store.failWrite = true
        assertTrue(runCatching { persistence.save(session) }.isFailure)
        store.failWrite = false
        persistence.save(session)
        persistence.save(session)
        assertEquals(2, store.writes)
        assertEquals(session, store.value)
    }

    @Test
    fun failedClearKeepsCacheAndSuccessfulClearAllowsSameSessionToBeSavedAgain() {
        val store = Store()
        val persistence = PickupSessionPersistence(store)
        persistence.save(session)
        store.failClear = true
        assertTrue(runCatching { persistence.clear() }.isFailure)
        persistence.save(session)
        assertEquals(1, store.writes)
        store.failClear = false
        persistence.clear()
        persistence.save(session)
        assertEquals(2, store.writes)
        assertEquals(session, store.value)
    }

    @Test
    fun restoredSequenceIsKeptUntilANewerSequenceIsPersisted() {
        val store = Store().apply { value = session }
        val persistence = PickupSessionPersistence(store)
        val restored = persistence.load()!!
        persistence.restored(restored)
        persistence.save(restored)
        assertEquals(0, store.writes)
        persistence.save(restored.copy(vehicleSequence = 13))
        assertEquals(1, store.writes)
        assertEquals(13L, store.value!!.vehicleSequence)
    }
}
