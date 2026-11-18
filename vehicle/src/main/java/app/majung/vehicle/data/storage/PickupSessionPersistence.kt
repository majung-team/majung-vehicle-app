package app.majung.vehicle.data.storage

/** Cache changes only after successful encrypted writes. Failures propagate to the controller. */
internal class PickupSessionPersistence(private val store: PickupSessionStore) {
    private var saved: SavedPickupSession? = null

    fun load(): SavedPickupSession? = store.load()

    fun restored(session: SavedPickupSession) {
        saved = session
    }

    fun save(session: SavedPickupSession) {
        if (session == saved) return
        store.save(session)
        saved = session
    }

    fun clear() {
        store.clear()
        saved = null
    }
}
