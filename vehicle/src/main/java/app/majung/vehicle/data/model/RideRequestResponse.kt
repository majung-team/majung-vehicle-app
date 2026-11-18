package app.majung.vehicle.data.model

import app.majung.vehicle.navi.PickupPlace
import org.json.JSONObject

/** Keeps the original payload signature so changes to additional fields still refresh the row. */
data class RideRequestResponse(
    val requestId: String,
    val nickname: String,
    val majungId: String,
    val status: String,
    val place: PickupPlace,
    val signature: String,
) {
    companion object {
        fun fromJson(data: JSONObject): RideRequestResponse {
            val point = data.getJSONObject("place")
            return RideRequestResponse(
                data.getString("requestId"),
                data.optString("nickname"),
                data.optString("majungId"),
                data.optString("status"),
                PickupPlace(
                    point.getString("label"),
                    point.getDouble("latitude"),
                    point.getDouble("longitude"),
                ),
                data.toString(),
            )
        }
    }
}
