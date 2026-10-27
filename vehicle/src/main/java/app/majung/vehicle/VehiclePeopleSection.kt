package app.majung.vehicle

import android.app.AlertDialog
import android.text.InputFilter
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import org.json.JSONObject

internal class VehiclePeopleSection(private val app: VehicleApplication,private val views: VehicleViews,private val openPhone: ()->Unit) {
    private val controller=app.people
    private var owner: String?=null
    private fun currentDriver(): String? = app.account.state.takeIf { it.optString("status")=="LINKED" }?.optString("majungId")?.takeIf { it.isNotBlank() }
    private fun allowed()=views.canInteract && owner!=null && owner==currentDriver()
    fun find() {
        owner=currentDriver()
        if(allowed() && !controller.busy) searchInput()
    }
    fun open() {
        owner=currentDriver()
        if(!allowed() || controller.busy) return
        AlertDialog.Builder(views.context).setTitle("사람 연결")
            .setAdapter(rows(listOf("닉네임·마중 ID로 사람 찾기","연결 요청·연결된 사람 보기","휴대전화로 연결하기 · QR"))) { _,index ->
                if(allowed()) when(index) { 0->searchInput(); 1->connections(0); 2->openPhone() }
            }.setNegativeButton("닫기",null).show().also(views::trackDialog)
    }
    private fun searchInput() {
        if(!allowed()) return
        val input=EditText(views.context).apply {
            id=R.id.people_search
            hint="닉네임 또는 마중 ID"
            textSize=20f
            isSingleLine=true
            filters=arrayOf(InputFilter.LengthFilter(32))
        }
        val dialog=AlertDialog.Builder(views.context).setTitle("누구를 찾을까요?")
            .setMessage("닉네임이 같으면 마중 ID를 확인해주세요.")
            .setView(input).setPositiveButton("검색",null).setNegativeButton("돌아가기",null).show()
        views.trackDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if(!allowed()) return@setOnClickListener
            val query=input.text.toString().trim()
            if(query.length<2) { input.error="두 글자 이상 입력해주세요."; return@setOnClickListener }
            dialog.dismiss()
            search(query,0)
        }
    }
    private fun load(call: ((JSONObject?,String?)->Unit)->Unit, loaded: (JSONObject)->Unit) {
        if(!allowed() || controller.busy) return
        val expectedOwner=owner
        val waiting=AlertDialog.Builder(views.context).setMessage("사람 연결 정보를 확인하고 있어요.")
            .setNegativeButton("닫기",null).show().also(views::trackDialog)
        call { data,error ->
            val visible=waiting.isShowing
            waiting.dismiss()
            if(!visible || expectedOwner!=owner || !allowed()) return@call
            if(data!=null) loaded(data) else notice(error ?: "연결 정보를 다시 확인해주세요.")
        }
    }
    private fun search(query: String,page: Int): Unit = load({ controller.search(query,page,it) }) { data ->
        val array=data.getJSONArray("items")
        val items=(0 until array.length()).map { array.getJSONObject(it) }
        val dialog=AlertDialog.Builder(views.context).setTitle("‘$query’ 검색 결과")
            .setNegativeButton("닫기",null)
        if(items.isEmpty()) dialog.setMessage("일치하는 사람이 없어요. 상대방의 마중 ID를 확인하거나 다른 닉네임으로 검색해주세요.")
            .setPositiveButton("다시 검색") { _,_->searchInput() }
        else dialog.setAdapter(rows(items.map { "${it.getString("nickname")} · ${it.getString("majungId")}" })) { _,index ->
            if(allowed()) confirmRequest(items[index])
        }
        if(page>0) dialog.setNeutralButton("이전 결과") { _,_->search(query,page-1) }
        if(items.size==20 && page<50) dialog.setPositiveButton("다음 결과") { _,_->search(query,page+1) }
        dialog.show().also(views::trackDialog)
    }
    private fun confirmRequest(person: JSONObject) {
        if(!allowed()) return
        AlertDialog.Builder(views.context).setTitle("연결 요청을 보낼까요?")
            .setMessage("${person.getString("nickname")} · ${person.getString("majungId")}\n상대방이 승인해야 연결돼요. 차량 위치는 공유하지 않아요.")
            .setPositiveButton("연결 요청 보내기") { _,_->
                load({ controller.request(person.getString("memberId"),it) }) { data ->
                    val status=data.getJSONObject("connection").getString("status")
                    notice(if(status=="ACCEPTED") "이미 연결된 사람이에요. 픽업 화면에서 초대할 수 있어요." else "연결 요청을 보냈어요. 상대방의 승인을 기다려주세요.")
                }
            }.setNegativeButton("돌아가기",null).show().also(views::trackDialog)
    }
    private fun connections(page: Int): Unit = load({ controller.connections(page,it) }) { data ->
        val array=data.getJSONArray("items")
        val items=(0 until array.length()).map { array.getJSONObject(it) }
        val dialog=AlertDialog.Builder(views.context).setTitle("연결 요청·연결된 사람")
            .setNegativeButton("닫기",null)
        if(items.isEmpty()) dialog.setMessage("아직 연결된 사람이나 요청이 없어요.").setPositiveButton("사람 찾기") { _,_->searchInput() }
        else dialog.setAdapter(rows(items.map { "${it.getString("nickname")} · ${it.getString("majungId")}\n${label(it)}" })) { _,index -> if(allowed()) details(items[index]) }
        if(page>0) dialog.setNeutralButton("이전 사람들") { _,_->connections(page-1) }
        if(items.size==20 && page<50) dialog.setPositiveButton("다음 사람들") { _,_->connections(page+1) }
        dialog.show().also(views::trackDialog)
    }
    private fun details(person: JSONObject) {
        if(!allowed()) return
        val dialog=AlertDialog.Builder(views.context).setTitle("${person.getString("nickname")} · ${person.getString("majungId")}")
            .setMessage(label(person)).setNegativeButton("닫기",null)
        val incoming=person.getString("direction")=="INCOMING"
        if(person.getString("status")=="PENDING") {
            if(incoming) {
                dialog.setPositiveButton("연결 승인") { _,_->change(person,"accept") }
                dialog.setNeutralButton("거절") { _,_->change(person,"reject") }
            } else dialog.setPositiveButton("보낸 요청 취소") { _,_->change(person,"cancel") }
        }
        // Destructive disconnection stays on the existing phone account screen.
        dialog.show().also(views::trackDialog)
    }
    private fun change(person: JSONObject,action: String) =
        load({ controller.change(person.getString("connectionId"),action,it) }) { connections(0) }
    private fun label(person: JSONObject): String = when(person.getString("status")) {
        "PENDING" -> if(person.getString("direction")=="INCOMING") "받은 연결 요청 · 승인 대기" else "보낸 연결 요청 · 상대방 승인 대기"
        "ACCEPTED" -> "연결됨 · 픽업 화면에서 초대할 수 있어요"
        "REJECTED" -> "거절된 요청"
        "CANCELLED" -> "취소된 요청"
        "DISCONNECTED" -> "연결 해제됨"
        else -> "연결 상태를 다시 확인해주세요"
    }
    private fun rows(labels: List<String>): ArrayAdapter<String> = object: ArrayAdapter<String>(views.context,android.R.layout.simple_list_item_1,labels) {
        override fun getView(position: Int,convertView: View?,parent: ViewGroup): View {
            return (super.getView(position,convertView,parent) as TextView).apply {
                textSize=22f
                minHeight=(72*resources.displayMetrics.density).toInt()
                maxLines=3
                gravity=Gravity.CENTER_VERTICAL
                val density=resources.displayMetrics.density
                setPadding((24*density).toInt(),(16*density).toInt(),(24*density).toInt(),(16*density).toInt())
            }
        }
    }
    private fun notice(message: String) {
        if(allowed()) AlertDialog.Builder(views.context).setMessage(message).setPositiveButton("확인",null).show().also(views::trackDialog)
    }
}
