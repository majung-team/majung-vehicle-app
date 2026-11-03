package app.majung.vehicle

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
