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
        setPadding(24.dp, 20.dp, 24.dp, 24.dp)
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = 20.dp.toFloat()
            setStroke(1.dp, Color.rgb(239, 242, 246))
        }
        container.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 20.dp })
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
        styleDialog(dialog)
        dialog.setOnDismissListener { dialogs.remove(dialog) }
    }
    private fun styleDialog(dialog: AlertDialog) {
        val window = dialog.window ?: return
        window.setWindowAnimations(if (android.animation.ValueAnimator.areAnimatorsEnabled()) R.style.MajungDialogMotion else 0)
        val available = (context as? android.app.Activity)?.window?.decorView?.width?.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val width = minOf(760.dp, available - 48.dp)
        window.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = 24.dp.toFloat()
        })
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        fun readable(view: View) {
            if (view is android.widget.ImageView && view.contentDescription?.toString()?.contains("QR") == true) {
                view.adjustViewBounds = true
                view.maxHeight = 320.dp
                view.minimumHeight = minOf(view.minimumHeight, 320.dp)
                view.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                view.setPadding(16.dp, 16.dp, 16.dp, 16.dp)
                view.layoutParams = view.layoutParams.apply { height = 320.dp }
            }
            if (view is android.widget.EditText) {
                view.backgroundTintList = null
                view.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(242, 244, 247))
                    cornerRadius = 16.dp.toFloat()
                    setStroke(1.dp, Color.rgb(225, 230, 237))
                }
                view.setPadding(20.dp, 16.dp, 20.dp, 16.dp)
                view.minimumHeight = 72.dp
                view.setTextColor(Color.rgb(25, 31, 40))
                view.setHintTextColor(Color.rgb(107, 118, 132))
            }
            if (view is TextView && view !is Button && view !is android.widget.EditText) {
                val metrics = context.resources.displayMetrics
                val size = if (android.os.Build.VERSION.SDK_INT >= 34)
                    android.util.TypedValue.deriveDimension(android.util.TypedValue.COMPLEX_UNIT_SP, view.textSize, metrics)
                else view.textSize / metrics.scaledDensity
                view.textSize = maxOf(20f, size)
                view.setTextColor(Color.rgb(41, 53, 65))
                view.setLineSpacing(4.dp.toFloat(), 1f)
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) readable(view.getChildAt(index))
        }
        readable(window.decorView)
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            setPadding(paddingLeft, paddingTop + 8.dp, paddingRight, paddingBottom + 16.dp)
        }
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { which ->
            dialog.getButton(which)?.apply {
                textSize = 20f
                isAllCaps = false
                minimumHeight = 64.dp
                (layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                    it.marginStart = 8.dp
                    it.topMargin = 12.dp
                    it.bottomMargin = 8.dp
                    layoutParams = it
                }
                minWidth = 120.dp
                setPadding(20.dp, 12.dp, 20.dp, 12.dp)
                setTextColor(if (which == AlertDialog.BUTTON_POSITIVE) Color.WHITE else Color.rgb(38, 83, 117))
                background = android.graphics.drawable.StateListDrawable().apply {
                    fun shape(color: Int) = android.graphics.drawable.GradientDrawable().apply {
                        setColor(color); cornerRadius = 12.dp.toFloat()
                    }
                    addState(intArrayOf(-android.R.attr.state_enabled), shape(Color.rgb(220, 227, 234)))
                    addState(intArrayOf(android.R.attr.state_pressed), shape(if (which == AlertDialog.BUTTON_POSITIVE) Color.rgb(29, 80, 172) else Color.rgb(221, 236, 249)))
                    addState(intArrayOf(android.R.attr.state_focused), shape(if (which == AlertDialog.BUTTON_POSITIVE) Color.rgb(29, 80, 172) else Color.rgb(221, 236, 249)))
                    addState(intArrayOf(), shape(if (which == AlertDialog.BUTTON_POSITIVE) Color.rgb(38, 105, 221) else Color.rgb(242, 246, 250)))
                }
            }
        }
    }

    fun applyRestrictions() {
        if (!canInteract) {
            buttons.forEach { lockedButtons[it] = it.isEnabled; it.isEnabled = false }
            dialogs.toList().forEach { it.dismiss() }
        }
    }
    fun addView(view: View) { target.addView(view) }
    fun prependView(view: View) { target.addView(view, 0) }

    fun label(value: String, size: Float): TextView = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(Color.rgb(32,40,46))
        setPadding(0, 8.dp, 0, 8.dp)
        setLineSpacing(3.dp.toFloat(), 1f)
    }

    fun button(title: String, primary: Boolean = false, parent: LinearLayout? = null, action: () -> Unit): Button = Button(context).apply {
        text = title
        isAllCaps = false
        textSize = 20f
        maxLines = 2
        minimumHeight = 72.dp
        gravity = android.view.Gravity.CENTER
        setTextColor(android.content.res.ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Color.rgb(133, 149, 162), if (primary) Color.WHITE else Color.rgb(51, 61, 75))))
        backgroundTintList = null
        background = android.graphics.drawable.StateListDrawable().apply {
            fun shape(color: Int) = android.graphics.drawable.GradientDrawable().apply {
                setColor(color); cornerRadius = 16.dp.toFloat()
            }
            addState(intArrayOf(-android.R.attr.state_enabled), shape(Color.rgb(239,242,244)))
            addState(intArrayOf(android.R.attr.state_pressed), shape(if (primary) Color.rgb(29, 80, 172) else Color.rgb(222, 235, 245)))
            addState(intArrayOf(android.R.attr.state_focused), shape(if (primary) Color.rgb(29, 80, 172) else Color.rgb(222, 235, 245)).apply { setStroke(3.dp, Color.rgb(112, 190, 244)) })
            addState(intArrayOf(), shape(if (primary) Color.rgb(38, 105, 221) else Color.rgb(242, 244, 247)))
        }
        setPadding(16.dp, 12.dp, 16.dp, 12.dp)
        setOnClickListener { if (canInteract) action(); onAction() }
        buttons.add(this)
        (parent ?: target).addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 8.dp })
    }

    private val Int.dp: Int get() = (this * context.resources.displayMetrics.density).toInt()
}

internal fun TextView.updateText(value: String) {
    if (text.toString() != value) text = value
}
