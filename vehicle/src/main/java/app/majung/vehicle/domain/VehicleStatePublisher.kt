package app.majung.vehicle.domain

import android.os.Handler
import android.os.SystemClock
import app.majung.vehicle.data.api.ApiRejected
import app.majung.vehicle.data.api.PickupApiClient
import app.majung.vehicle.navi.DestinationReader
import app.majung.vehicle.navi.PickupPlace
import app.majung.vehicle.navi.VehicleSharingFeedback
import java.util.concurrent.Executor
import org.json.JSONObject

/** Serializes telemetry uploads and ignores replies from a previous pickup or place version. */
internal class VehicleStatePublisher(
    private val api: PickupApiClient,
    private val worker: Executor,
    private val handler: Handler,
    private val host: Host,
) {
    interface Host {
        val needsSharingConfirmation: Boolean
        val status: String
        val pickupId: String?
        val driverToken: String?
        val place: PickupPlace?
        val placeVersion: Long

        fun checkAutoConfirmProposal(reader: DestinationReader)

        fun persistSession(): Boolean

        fun notifyChanged()
    }

    var vehicleSequence = 0L
        private set

    var vehicleGeneration = 0
        private set

    var vehicleInFlight = false
        private set

    var vehicleKnownEmpty = true
        private set

    var nextVehicleSend = 0L
        private set

    var nextInvalidationSend = 0L
        private set

    var vehicleMessage = "차량 정보 전송 대기"
        private set

    fun sync(reader: DestinationReader) {
        host.checkAutoConfirmProposal(reader)
        if (host.needsSharingConfirmation || host.status !in setOf("ACTIVE", "ARRIVED")) return
        val id = host.pickupId ?: return
        val token = host.driverToken ?: return
        val confirmedPlace = host.place ?: return
        val now = SystemClock.elapsedRealtime()
        val sample = reader.telemetry.current(confirmedPlace, now)
        if (sample == null) {
            vehicleMessage = "최신 차량 정보가 없어 위치·ETA 공유를 멈췄어요"
            if (vehicleKnownEmpty && !vehicleInFlight) return
            if (now < nextInvalidationSend) return
        } else {
            if (now < nextVehicleSend) return
        }
        if (vehicleInFlight) return
        val sequence = ++vehicleSequence
        if (!host.persistSession()) {
            host.notifyChanged()
            return
        }
        vehicleInFlight = true
        val generation = vehicleGeneration
        if (sample != null) nextVehicleSend = now + 5000 else nextInvalidationSend = now + 3000
        val body = JSONObject().put("sequence", sequence).put("placeVersion", host.placeVersion)
        if (sample != null) {
            body
                .put("latitude", sample.latitude)
                .put("longitude", sample.longitude)
                .put("durationSeconds", sample.durationSeconds)
                .put("sourceTime", sample.observedAt.toString())
        }
        worker.execute {
            val result = runCatching {
                api.request(
                    if (sample == null) "DELETE" else "PUT",
                    "/$id/vehicle-state",
                    token,
                    body,
                )
            }
            handler.post {
                if (
                    generation != vehicleGeneration ||
                        id != host.pickupId ||
                        host.status in setOf("COMPLETED", "CANCELLED", "EXPIRED")
                )
                    return@post
                vehicleInFlight = false
                result.fold(
                    onSuccess = { response ->
                        vehicleKnownEmpty = sample == null
                        vehicleMessage =
                            VehicleSharingFeedback.accepted(
                                sample != null,
                                response.optString("freshness"),
                                response.optLong("validForMillis", 0),
                            )
                    },
                    onFailure = { error ->
                        // A timed-out request may have committed: invalidate until acknowledged.
                        vehicleKnownEmpty = false
                        vehicleMessage =
                            VehicleSharingFeedback.rejected((error as? ApiRejected)?.code)
                    },
                )
                host.notifyChanged()
                // Do not wait for the next poll if the SDK was invalidated during an upload.
                if (
                    sample != null &&
                        reader.telemetry.current(confirmedPlace, SystemClock.elapsedRealtime()) ==
                            null
                ) {
                    sync(reader)
                }
            }
        }
    }

    fun restoreSequence(sequence: Long) {
        vehicleSequence = sequence
        vehicleKnownEmpty = sequence == 0L
    }

    fun reset() {
        vehicleGeneration++
        vehicleSequence = 0
        vehicleInFlight = false
        vehicleKnownEmpty = true
        nextVehicleSend = 0
        nextInvalidationSend = 0
        vehicleMessage = "차량 정보 전송 대기"
    }

    fun invalidatePlace() {
        vehicleGeneration++
        vehicleInFlight = false
        vehicleKnownEmpty = true
        nextVehicleSend = 0
    }
}
