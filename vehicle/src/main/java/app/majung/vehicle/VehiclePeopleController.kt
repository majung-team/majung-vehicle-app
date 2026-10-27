package app.majung.vehicle

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Executors

/** Queries and writes always use the registered vehicle and the driver shown when the action began. */
internal class VehiclePeopleController(
    private val api: PickupApiClient,
    private val credential: () -> String?,
    private val driver: () -> String?
) {
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    var busy=false
        private set
    fun search(query: String, page: Int, done: (JSONObject?, String?) -> Unit) {
        val escaped=URLEncoder.encode(query.trim(),"UTF-8")
        execute("GET","/vehicles/people/search?query=$escaped&page=$page",null,done)
    }
    fun connections(page: Int, done: (JSONObject?, String?) -> Unit) =
        execute("GET","/vehicles/people/connections?page=$page",null,done)
    fun request(memberId: String, done: (JSONObject?, String?) -> Unit) =
        execute("POST","/vehicles/people/connections",JSONObject().put("memberId",memberId),done)
    fun change(id: String, action: String, done: (JSONObject?, String?) -> Unit) {
        if(action !in setOf("accept","reject","cancel","disconnect")) return
        execute("POST","/vehicles/people/connections/$id/$action",JSONObject(),done)
    }
    private fun execute(method: String,path: String,body: JSONObject?,done: (JSONObject?,String?)->Unit) {
        if(busy) return
        val token=credential() ?: return done(null,"먼저 차량을 연결해주세요.")
        val owner=driver() ?: return done(null,"먼저 운전자 계정을 연결해주세요.")
        busy=true
        body?.put("driverMajungId",owner)
        worker.execute {
            val result=runCatching {
                api.requestApi(method,path,token,body).also { data ->
                    if(method=="GET") {
                        val items=data.getJSONArray("items")
                        for(index in 0 until items.length()) items.getJSONObject(index).also {
                            it.getString("nickname"); it.getString("majungId")
                            if(path.contains("/search?")) it.getString("memberId") else {
                                it.getString("connectionId"); it.getString("status"); it.getString("direction")
                            }
                        }
                    } else data.getJSONObject("connection").also { it.getString("status"); it.getString("nickname") }
                }
            }
            main.post {
                busy=false
                if(token!=credential() || owner!=driver()) {
                    done(null,"운전자 연결이 바뀌었어요. 사람 연결을 다시 열어주세요.")
                } else result.fold(onSuccess={ done(it,null) },onFailure={ error ->
                    val message=when {
                        error is ApiRejected && error.status==429 -> "요청이 많아요. 잠시 후 다시 시도해주세요."
                        error is ApiRejected && error.status in setOf(401,403) -> "차량 권한이나 운전자 연결을 다시 확인해주세요."
                        error is ApiRejected && error.code=="CONNECTION_COOLDOWN" -> "이 사람에게는 잠시 후 다시 연결을 요청할 수 있어요."
                        error is ApiRejected && error.code=="DRIVER_ACCOUNT_CHANGED" -> "운전자 계정이 바뀌었어요. 다시 확인해주세요."
                        error is ApiRejected && error.status in 400..499 -> "연결 상태가 바뀌었거나 요청을 처리할 수 없어요. 목록을 다시 확인해주세요."
                        method=="POST" -> "처리 결과를 확인하지 못했어요. 다시 보내기 전에 연결 목록을 확인해주세요."
                        else -> "사람 목록을 가져오지 못했어요. 잠시 후 다시 확인해주세요."
                    }
                    done(null,message)
                })
            }
        }
    }
}
