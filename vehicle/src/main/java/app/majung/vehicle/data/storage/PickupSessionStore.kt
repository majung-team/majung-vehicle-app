package app.majung.vehicle.data.storage


import app.majung.vehicle.*
import app.majung.vehicle.ui.*
import app.majung.vehicle.ui.sections.*
import app.majung.vehicle.navi.*
import app.majung.vehicle.domain.*
import app.majung.vehicle.data.api.*
import app.majung.vehicle.data.storage.*
import app.majung.vehicle.util.*
import app.majung.vehicle.service.*

/** Location and passenger names are deliberately not part of the saved session. */
data class SavedPickupSession(val pickupId: String, val driverToken: String,
                              val inviteToken: String?, val vehicleSequence: Long,
                              val navigationReference: String? = null) {
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
