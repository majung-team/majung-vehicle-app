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

/** Unknown and disconnected are blocked; a previous allowed state must not survive. */
internal class DrivingAccess {
    var known = false
        private set
    var allowed = false
        private set
    fun update(available: Boolean, requiresOptimization: Boolean, activeRestrictions: Int) {
        known = available
        allowed = available && !requiresOptimization && activeRestrictions == 0
    }
    fun unavailable() { known = false; allowed = false }
}
