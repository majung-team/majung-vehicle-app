package app.majung.vehicle

/** Metadata only. Equality means payload values matched, not that the SDK acquired a new GPS fix. */
data class SdkTimingEvent(val type: SdkTimingType, val elapsedMs: Long, val changed: Boolean? = null)

enum class SdkTimingType {
    LOCATION, DESTINATION_ETA, DRIVING_ETA,
    LOCATION_REQUEST, DESTINATION_REQUEST, WAYPOINT_REQUEST, WAYPOINT, WAYPOINT_CHANGED, GUIDANCE_VALID, GUIDANCE_INVALID, ROUTE_STATE_REQUEST, ROUTE_STATE,
    CONNECTED, DISCONNECTED, ROUTE_STARTED, DESTINATION_CHANGED,
    ROUTING_COMPLETED, REROUTE_ENDED, ROUTE_CANCELLED, ERROR
}
