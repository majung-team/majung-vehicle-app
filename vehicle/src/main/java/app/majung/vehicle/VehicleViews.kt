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
    fun addView(view: View) { container.addView(view) }

    fun label(value: String, size: Float): TextView = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(Color.rgb(25, 59, 52))
        setPadding(0, 12, 0, 12)
    }

    fun button(title: String, action: () -> Unit): Button = Button(context).apply {
        text = title
        setOnClickListener { if (canInteract) action(); onAction() }
        buttons.add(this)
        container.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 60.dp))
    }

    private val Int.dp: Int get() = (this * context.resources.displayMetrics.density).toInt()
}

internal fun TextView.updateText(value: String) {
    if (text.toString() != value) text = value
}
