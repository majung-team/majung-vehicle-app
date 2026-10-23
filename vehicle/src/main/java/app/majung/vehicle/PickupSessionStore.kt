package app.majung.vehicle

/** Location and passenger names are deliberately not part of the saved session. */
data class SavedPickupSession(val pickupId: String, val driverToken: String,
                              val inviteToken: String?, val vehicleSequence: Long) {
    override fun toString(): String = "SavedPickupSession(redacted)"
}

interface PickupSessionStore {
    fun load(): SavedPickupSession?
    fun save(session: SavedPickupSession)
    fun clear()
}

/** Controller-only tests can avoid disk; the application injects the encrypted store. */
class MemoryPickupSessionStore : PickupSessionStore {
    private var session: SavedPickupSession? = null
    override fun load(): SavedPickupSession? = session
    override fun save(session: SavedPickupSession) { this.session = session }
    override fun clear() { session = null }
}
