package app.majung.vehicle

import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.TextView

internal class VehicleRegistrationSection(private val app: VehicleApplication, private val views: VehicleViews) {
    private val text: TextView = views.label("", 16f).also(views::addView)
    private val register = views.button("차량 최초 등록") {
        if (!app.registration.needsRegistration) return@button
        val input = EditText(views.context).apply {
            hint = "관리자에게 받은 일회용 코드"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setAutofillHints(null)
            filters = arrayOf(android.text.InputFilter.LengthFilter(43))
        }
        val dialog = AlertDialog.Builder(views.context).setTitle("차량 최초 등록").setView(input)
            .setPositiveButton("등록", null).setNegativeButton("돌아가기", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!views.canInteract) { dialog.dismiss(); return@setOnClickListener }
                val code = input.text.toString().trim()
                if (!code.matches(Regex("[A-Za-z0-9_-]{43}"))) { input.error = "43자리 등록 코드를 확인해주세요"; return@setOnClickListener }
                input.text.clear()
                app.registration.register(code)
                dialog.dismiss()
            }
        }
        dialog.show()
        views.trackDialog(dialog)
    }
    private val refresh = views.button("차량 등록 상태 다시 확인") { app.registration.refresh() }
    fun render() {
        text.updateText(app.registration.message)
        register.visibility = if (app.registration.needsRegistration) android.view.View.VISIBLE else android.view.View.GONE
        refresh.isEnabled = !app.registration.busy
    }
}
