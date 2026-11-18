package app.majung.vehicle.service

import android.os.Handler
import android.os.Looper
import app.majung.vehicle.VehicleApplication

/** Application fallback polling; telemetry work yields to the foreground sharing service. */
internal class BackgroundPickupPoller(private val app: VehicleApplication) {
    private var backgroundTicks = 0
    private val backgroundHandler = Handler(Looper.getMainLooper())
    private val backgroundPoller =
        object : Runnable {
            override fun run() {
                if (!app.activityResumed) {
                    if (
                        backgroundTicks % 2 == 0 &&
                            app.registration.vehicleToken != null &&
                            app.account.accountSnapshot.status == "LINKED"
                    ) {
                        app.account.refresh(background = true)
                    }
                    if (
                        !app.serviceRunning &&
                            app.sharingNotice.accepted &&
                            !app.pickup.needsSharingConfirmation &&
                            app.pickup.status in
                                setOf("INVITING", "WAITING_APPROVAL", "ACTIVE", "ARRIVED")
                    ) {
                        app.pickup.refresh()
                        if (PickupSharingService.hasLocationPermission(app)) {
                            app.destination.restorePickupReference(
                                app.pickup.place,
                                app.pickup.navigationReference,
                            )
                            app.destination.connect()
                            app.destination.pollTelemetry()
                            if (app.pickup.status in setOf("ACTIVE", "ARRIVED")) {
                                app.pickup.checkAutoConfirmProposal(app.destination)
                                app.pickup.syncVehicle(app.destination)
                            }
                        }
                    }
                }
                backgroundTicks++
                backgroundHandler.postDelayed(this, 3000)
            }
        }

    fun start() {
        backgroundHandler.post(backgroundPoller)
    }
}
