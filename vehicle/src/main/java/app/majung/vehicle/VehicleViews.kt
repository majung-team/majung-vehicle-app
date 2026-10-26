package app.majung.vehicle

import android.content.Context
import android.app.AlertDialog
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Shared styling and action refresh only; sections own their controls and dialogs. */
internal class VehicleViews(val context: Context, private val container: LinearLayout,
                           private val onAction: () -> Unit,
                           private val actionAllowed: () -> Boolean = { true }) {
    private var target = container
    fun section(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        container.addView(this, LinearLayout.LayoutParams(-1, -2))
        target = this
    }
    fun useRoot() { target = container }
    private val buttons = mutableListOf<Button>()
    private val lockedButtons = linkedMapOf<Button, Boolean>()
    fun beginRender() {
        lockedButtons.forEach { (button, enabled) -> button.isEnabled = enabled }
        lockedButtons.clear()
    }
    private val dialogs = mutableSetOf<AlertDialog>()
    val canInteract: Boolean get() = actionAllowed()
    fun trackDialog(dialog: AlertDialog) {
        if (!canInteract) { dialog.dismiss(); return }
        dialogs.add(dialog)
        dialog.setOnDismissListener { dialogs.remove(dialog) }
    }
    fun applyRestrictions() {
        if (!canInteract) {
            buttons.forEach { lockedButtons[it] = it.isEnabled; it.isEnabled = false }
            dialogs.toList().forEach { it.dismiss() }
        }
    }
    fun addView(view: View) { target.addView(view) }

    fun label(value: String, size: Float): TextView = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(Color.rgb(32,40,46))
        setPadding(0, 12, 0, 12)
    }

    fun button(title: String, action: () -> Unit): Button = Button(context).apply {
        text = title
        isAllCaps = false
        textSize = 19f
        gravity = android.view.Gravity.CENTER
        setTextColor(Color.rgb(36, 79, 115))
        backgroundTintList = null
        background = android.graphics.drawable.StateListDrawable().apply {
            fun shape(color: Int) = android.graphics.drawable.GradientDrawable().apply {
                setColor(color); cornerRadius = 10.dp.toFloat(); setStroke(1.dp, Color.rgb(215,222,223))
            }
            addState(intArrayOf(-android.R.attr.state_enabled), shape(Color.rgb(239,242,244)))
            addState(intArrayOf(android.R.attr.state_pressed), shape(Color.rgb(215,226,235)))
            addState(intArrayOf(), shape(Color.WHITE))
        }
        setPadding(16.dp, 12.dp, 16.dp, 12.dp)
        setOnClickListener { if (canInteract) action(); onAction() }
        buttons.add(this)
        target.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 64.dp).apply { topMargin = 8.dp })
    }

    private val Int.dp: Int get() = (this * context.resources.displayMetrics.density).toInt()
}

internal fun TextView.updateText(value: String) {
    if (text.toString() != value) text = value
}
