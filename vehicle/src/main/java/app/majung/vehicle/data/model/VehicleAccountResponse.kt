package app.majung.vehicle.data.model

import org.json.JSONObject

data class VehicleAccountResponse(
    val status: String,
    val majungId: String,
    val nickname: String,
    val challengeId: String,
) {
    companion object {
        fun fromJson(data: JSONObject) =
            VehicleAccountResponse(
                data.optString("status"),
                data.optString("majungId"),
                data.optString("nickname"),
                data.optString("challengeId"),
            )
    }
}
