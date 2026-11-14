package app.majung.vehicle.util

internal enum class PickupAlertKind {
    QUICK_MESSAGE,
    PLACE_PROPOSAL,
    NEW_RIDE_REQUEST,
}

internal enum class AlertTapBehavior {
    DISMISS_ONLY,
    OPEN_APP_SCREEN,
}

internal data class PickupAlertBehavior(
    val useFullScreenIntent: Boolean,
    val tapBehavior: AlertTapBehavior,
    val showOverlayToast: Boolean,
)

/** Keeps driving-time navigation focus intact unless a parked screen transition is genuinely needed. */
internal object PickupNotificationPolicy {
    fun behaviorFor(kind: PickupAlertKind): PickupAlertBehavior = when (kind) {
        PickupAlertKind.QUICK_MESSAGE -> PickupAlertBehavior(
            useFullScreenIntent = false,
            tapBehavior = AlertTapBehavior.DISMISS_ONLY,
            showOverlayToast = true,
        )
        PickupAlertKind.PLACE_PROPOSAL -> PickupAlertBehavior(
            useFullScreenIntent = false,
            tapBehavior = AlertTapBehavior.OPEN_APP_SCREEN,
            showOverlayToast = true,
        )
        PickupAlertKind.NEW_RIDE_REQUEST -> PickupAlertBehavior(
            useFullScreenIntent = false,
            tapBehavior = AlertTapBehavior.OPEN_APP_SCREEN,
            showOverlayToast = true,
        )
    }
}
