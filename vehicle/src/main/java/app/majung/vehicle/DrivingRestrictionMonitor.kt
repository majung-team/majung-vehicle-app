package app.majung.vehicle

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.os.Handler
import android.os.Looper

/** The system UX policy selects parked controls or a read-only summary. */
internal class DrivingRestrictionMonitor(private val context: Context, private val changed: () -> Unit) {
    private val access = DrivingAccess()
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private var active = false
    private var generation = 0
    val known: Boolean get() = active && access.known
    var stringLimit = 0
        private set
    var itemLimit = 0
        private set
    val allowed: Boolean get() {
        if (!active) return false
        try {
            val current = manager?.currentCarUxRestrictions ?: run { access.unavailable(); return false }
            accept(current)
        } catch (_: Exception) { access.unavailable() }
        return access.allowed
    }
    private fun accept(value: CarUxRestrictions) {
        access.update(true, value.isRequiresDistractionOptimization, value.activeRestrictions)
        stringLimit = if (value.activeRestrictions and CarUxRestrictions.UX_RESTRICTIONS_LIMIT_STRING_LENGTH != 0) {
            value.maxRestrictedStringLength.coerceAtLeast(0)
        } else 40
        itemLimit = if (value.activeRestrictions and CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT != 0) {
            value.maxCumulativeContentItems.coerceAtLeast(0)
        } else 3
    }
    fun start() {
        if (active) return
        active = true
        val version = ++generation
        access.unavailable()
        try {
            car = Car.createCar(context, Handler(Looper.getMainLooper()), Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready ->
                if (!active || generation != version) return@createCar
                access.unavailable()
                manager = null
                if (ready) try {
                    val restrictions = connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                    manager = restrictions
                    restrictions.registerListener { value ->
                        if (active && generation == version) { accept(value); changed() }
                    }
                    accept(restrictions.currentCarUxRestrictions)
                } catch (_: Exception) { access.unavailable() }
                changed()
            }
        } catch (_: Exception) { access.unavailable(); changed() }
    }
    fun stop() {
        active = false
        generation++
        access.unavailable()
        try { manager?.unregisterListener() } catch (_: Exception) { }
        manager = null
        try { car?.disconnect() } catch (_: Exception) { }
        car = null
        changed()
    }
}
