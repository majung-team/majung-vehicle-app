package app.majung.vehicle

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiRejected(val status: Int, val code: String? = null) : Exception("서버 요청 거절")

/** Blocking IO; callers must use the controller's worker. Never log bodies or tokens. */
class PickupApiClient(private val origin: String = BuildConfig.PICKUP_API) {
    fun request(method: String, path: String = "", token: String? = null, body: JSONObject? = null): JSONObject {
        return requestApi(method, "/pickups$path", token, body)
    }

    fun requestApi(method: String, path: String, token: String? = null, body: JSONObject? = null): JSONObject {
        check(origin.isNotEmpty()) { "외부 배포 서버가 아직 설정되지 않았어요" }
        val connection = URL("$origin/api/v1$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val code = runCatching {
                    connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use {
                        JSONObject(it.readText().take(8192)).optString("code").takeIf { value -> value.isNotBlank() }
                    }
                }.getOrNull()
                throw ApiRejected(status, code)
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        } finally { connection.disconnect() }
    }

    fun create(place: PickupPlace, vehicleToken: String? = null): JSONObject = request("POST", token = vehicleToken, body = JSONObject().put("place",
        JSONObject().put("label", place.label).put("latitude", place.latitude).put("longitude", place.longitude)))
}
