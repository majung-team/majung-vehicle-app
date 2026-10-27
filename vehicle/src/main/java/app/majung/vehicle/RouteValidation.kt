package app.majung.vehicle

/** A missing completion event never opens the gate by elapsed time alone. */
class RouteValidation {
    private enum class Awaiting { NONE, STATE, DESTINATION, WAYPOINT }
    private var awaiting = Awaiting.NONE
    private var requestedAt = 0L
    private var candidate: List<PickupPlace>? = null
    private var candidateAt = 0L
    var ready = false
        private set

    fun invalidate() {
        awaiting = Awaiting.NONE
        candidate = null
        ready = false
    }

    /** A second observation must be spaced from the response, not its request. */
    fun confirmationDelay(now: Long): Long =
        if (awaiting == Awaiting.NONE && !ready && candidate != null)
            (candidateAt + CONFIRMATION_GAP_MS - now).coerceAtLeast(0L)
        else 0L

    fun requestState(now: Long): Boolean {
        if (awaiting != Awaiting.NONE && now - requestedAt < RESPONSE_TIMEOUT_MS) return false
        // A timed-out validation cannot carry evidence into the next attempt.
        if (awaiting != Awaiting.NONE) invalidate()
        awaiting = Awaiting.STATE
        requestedAt = now
        return true
    }

    fun receiveState(guiding: Boolean, now: Long): Boolean {
        if (!expected(Awaiting.STATE, now)) return false
        if (!guiding) { invalidate(); return false }
        awaiting = Awaiting.DESTINATION
        requestedAt = now
        return true
    }

    fun beginWaypointResponse(now: Long): Boolean {
        if (!expected(Awaiting.DESTINATION, now)) return false
        awaiting = Awaiting.WAYPOINT
        requestedAt = now
        return true
    }

    fun receiveDestination(place: PickupPlace?, now: Long): Boolean =
        receiveRoute(place?.let { listOf(it) }, now)

    fun receiveRoute(places: List<PickupPlace>?, now: Long): Boolean {
        val kind = if (awaiting == Awaiting.WAYPOINT) Awaiting.WAYPOINT else Awaiting.DESTINATION
        if (!expected(kind, now)) return false
        awaiting = Awaiting.NONE
        if (places.isNullOrEmpty()) { invalidate(); return false }
        if (ready && candidate == places) return true
        ready = false
        val previous = candidate
        if (previous == places && now - candidateAt >= CONFIRMATION_GAP_MS) {
            ready = true
            return true
        }
        if (previous != places) {
            candidate = places.toList()
            candidateAt = now
        }
        return false
    }

    private fun expected(kind: Awaiting, now: Long): Boolean {
        if (awaiting != kind) return false
        if (now < requestedAt || now - requestedAt >= RESPONSE_TIMEOUT_MS) {
            invalidate()
            return false
        }
        return true
    }

    companion object {
        private const val RESPONSE_TIMEOUT_MS = 4000L
        private const val CONFIRMATION_GAP_MS = 5000L
    }
}
