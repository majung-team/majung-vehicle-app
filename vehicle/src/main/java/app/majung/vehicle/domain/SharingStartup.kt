package app.majung.vehicle.domain

/** A first-use prompt follows an approved pickup, never a background or restored session. */
internal object SharingStartup {
    enum class Action { NONE, REQUEST_PERMISSION, START }

    fun action(resumed: Boolean, parked: Boolean, active: Boolean, needsConfirmation: Boolean,
               running: Boolean, pending: Boolean, hasLocation: Boolean, alreadyAsked: Boolean): Action {
        if (!resumed || !parked || !active || needsConfirmation || running || pending) return Action.NONE
        if (hasLocation) return Action.START
        return if (alreadyAsked) Action.NONE else Action.REQUEST_PERMISSION
    }
}
