package app.majung.vehicle

/** Unknown and disconnected are blocked; a previous allowed state must not survive. */
internal class DrivingAccess {
    var allowed = false
        private set
    fun update(available: Boolean, requiresOptimization: Boolean, activeRestrictions: Int) {
        allowed = available && !requiresOptimization && activeRestrictions == 0
    }
    fun unavailable() { allowed = false }
}
