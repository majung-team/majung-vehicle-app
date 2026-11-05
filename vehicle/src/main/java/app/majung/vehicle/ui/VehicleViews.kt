package app.majung.vehicle.ui


import app.majung.vehicle.*
import app.majung.vehicle.ui.*
import app.majung.vehicle.ui.sections.*
import app.majung.vehicle.navi.*
import app.majung.vehicle.domain.*
import app.majung.vehicle.data.api.*
import app.majung.vehicle.data.storage.*
import app.majung.vehicle.util.*
import app.majung.vehicle.service.*

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
                view.minimumHeight = 80.dp
                view.textSize = 28f
                view.setTextColor(Color.rgb(25, 31, 40))
                view.setHintTextColor(Color.rgb(107, 118, 132))
            }
            if (view is TextView && view !is Button && view !is android.widget.EditText) {
                val metrics = context.resources.displayMetrics
                val size = if (android.os.Build.VERSION.SDK_INT >= 34)
                    android.util.TypedValue.deriveDimension(android.util.TypedValue.COMPLEX_UNIT_SP, view.textSize, metrics)
                else view.textSize / metrics.scaledDensity
                view.textSize = maxOf(24f, size)
                view.setTextColor(Color.rgb(41, 53, 65))
                view.setLineSpacing(4.dp.toFloat(), 1f)
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) readable(view.getChildAt(index))
        }
        readable(window.decorView)
        dialog.findViewById<TextView>(context.resources.getIdentifier("alertTitle", "id", "android"))?.apply {
            textSize = 32f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(25, 31, 40))
        }
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            setPadding(paddingLeft, paddingTop + 8.dp, paddingRight, paddingBottom + 16.dp)
        }
        styleDialogList(dialog)
        // The platform ButtonBarLayout baseline can place taller custom buttons above its bounds.
        (dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.parent as? LinearLayout)?.apply {
            isBaselineAligned = false
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            setPadding(24.dp, 12.dp, 24.dp, 20.dp)
            minimumHeight = 96.dp
        }
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { which ->
            dialog.getButton(which)?.apply {
                textSize = 28f
                isAllCaps = false
                flattenButtonSurface()
                minimumHeight = 80.dp
                backgroundTintList = null
                (layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                    it.marginStart = 8.dp
                    it.topMargin = 0
                    it.bottomMargin = 0
                    it.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    layoutParams = it
                }
                minWidth = 144.dp
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

    private fun styleDialogList(dialog: AlertDialog) {
        val listView = dialog.listView ?: return
        val baseAdapter = listView.adapter ?: return
        listView.divider = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        listView.dividerHeight = 12.dp
        listView.setPadding(24.dp, 12.dp, 24.dp, 16.dp)
        listView.clipToPadding = false
        listView.overScrollMode = View.OVER_SCROLL_NEVER
        listView.selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        listView.adapter = object : android.widget.BaseAdapter() {
            override fun getCount(): Int = baseAdapter.count
            override fun getItem(position: Int): Any? = baseAdapter.getItem(position)
            override fun getItemId(position: Int): Long = baseAdapter.getItemId(position)
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val raw = baseAdapter.getItem(position)?.toString().orEmpty()
                return (convertView as? TextView ?: TextView(context)).apply {
                    text = formatDialogChoice(raw)
                    textSize = 28f
                    minHeight = 88.dp
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                    setTextColor(Color.rgb(25, 31, 40))
                    setLineSpacing(4.dp.toFloat(), 1f)
                    setPadding(24.dp, 18.dp, 24.dp, 18.dp)
                    compoundDrawablePadding = 16.dp
                    setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, choiceChevron(), null)
                    background = android.graphics.drawable.StateListDrawable().apply {
                        fun card(fill: Int, strokeColor: Int, strokeWidth: Int = 1.dp) =
                            android.graphics.drawable.GradientDrawable().apply {
                                setColor(fill)
                                cornerRadius = 16.dp.toFloat()
                                setStroke(strokeWidth, strokeColor)
                            }
                        addState(intArrayOf(android.R.attr.state_pressed), card(Color.rgb(216, 233, 252), Color.rgb(38, 105, 221), 2.dp))
                        addState(intArrayOf(android.R.attr.state_focused), card(Color.rgb(216, 233, 252), Color.rgb(38, 105, 221), 3.dp))
                        addState(intArrayOf(android.R.attr.state_selected), card(Color.rgb(216, 233, 252), Color.rgb(38, 105, 221), 2.dp))
                        addState(intArrayOf(), card(Color.rgb(240, 246, 253), Color.rgb(190, 210, 234), 1.dp))
                    }
                }
            }
        }
    }

    private fun formatDialogChoice(raw: String): CharSequence {
        val span = android.text.SpannableString(raw)
        val newline = raw.indexOf('\n')
        val reselect = raw.indexOf(" · 위치 다시 선택")
        if (newline > 0) {
            span.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, newline, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(android.text.style.AbsoluteSizeSpan(22, true), newline + 1, raw.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(71, 87, 104)), newline + 1, raw.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else if (reselect > 0) {
            span.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, raw.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(android.text.style.AbsoluteSizeSpan(24, true), reselect, raw.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(38, 105, 221)), reselect, raw.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else if (raw.isNotEmpty()) {
            span.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, raw.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return span
    }

    private fun choiceChevron(): android.graphics.drawable.Drawable = object : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(38, 105, 221)
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3.dp.toFloat()
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        override fun getIntrinsicWidth(): Int = 20.dp
        override fun getIntrinsicHeight(): Int = 20.dp
        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val d = 5.dp.toFloat()
            val path = android.graphics.Path().apply {
                moveTo(cx - d * 0.5f, cy - d)
                lineTo(cx + d * 0.5f, cy)
                lineTo(cx - d * 0.5f, cy + d)
            }
            canvas.drawPath(path, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    fun applyRestrictions() {
        if (!canInteract) {
            buttons.forEach { lockedButtons[it] = it.isEnabled; it.isEnabled = false }
            dialogs.toList().forEach { it.dismiss() }
        }
    }
    fun addView(view: View) { target.addView(view) }
    fun prependView(view: View) { target.addView(view, 0) }

    // Legacy section sizes map to a shared vehicle scale; sp still follows system font scaling.
    private fun readableTextSize(size: Float): Float = when {
        size < 20f -> 24f
        size < 24f -> 28f
        size < 30f -> 32f
        size < 40f -> 40f
        else -> maxOf(56f, size)
    }

    fun label(value: String, size: Float): TextView = TextView(context).apply {
        text = value
        textSize = readableTextSize(size)
        setTextColor(Color.rgb(32,40,46))
        setPadding(0, 8.dp, 0, 8.dp)
        setLineSpacing(3.dp.toFloat(), 1f)
    }

    fun styleButton(button: Button, primary: Boolean) {
        if (button.tag == primary) return
        button.tag = primary
        button.setTextColor(android.content.res.ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Color.rgb(132, 144, 158), if (primary) Color.WHITE else Color.rgb(32, 45, 62))))
        button.backgroundTintList = null
        button.background = android.graphics.drawable.StateListDrawable().apply {
            fun shape(color: Int, strokeColor: Int = Color.TRANSPARENT, strokeWidth: Int = 0) =
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(color); cornerRadius = 16.dp.toFloat()
                    if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
                }
            addState(intArrayOf(-android.R.attr.state_enabled), shape(Color.rgb(242, 244, 247)))
            addState(intArrayOf(android.R.attr.state_pressed), shape(if (primary) Color.rgb(29, 80, 172) else Color.rgb(218, 232, 248), if (primary) Color.TRANSPARENT else Color.rgb(154, 184, 222), if (primary) 0 else 1.dp))
            addState(intArrayOf(android.R.attr.state_focused), shape(if (primary) Color.rgb(29, 80, 172) else Color.rgb(218, 232, 248), Color.rgb(112, 190, 244), 3.dp))
            addState(intArrayOf(), shape(if (primary) Color.rgb(38, 105, 221) else Color.rgb(238, 243, 249), if (primary) Color.TRANSPARENT else Color.rgb(198, 212, 228), if (primary) 0 else 1.dp))
        }
        button.setPadding(16.dp, 12.dp, 16.dp, 12.dp)
    }

    fun button(title: String, primary: Boolean = false, parent: LinearLayout? = null, refreshAfterAction: Boolean = true, compact: Boolean = false, action: () -> Unit): Button = Button(context).apply {
        text = title
        isAllCaps = false
        flattenButtonSurface()
        textSize = if (compact) 24f else 28f
        maxLines = 2
        minimumHeight = if (compact) 64.dp else 80.dp
        gravity = android.view.Gravity.CENTER
        styleButton(this, primary)
        setOnClickListener { if (canInteract) action(); if (refreshAfterAction) onAction() }
        buttons.add(this)
        (parent ?: target).addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = if (compact) 12.dp else 16.dp })
    }

    private val Int.dp: Int get() = (this * context.resources.displayMetrics.density).toInt()
}

internal fun TextView.updateText(value: String) {
    if (text.toString() != value) text = value
}

/** Custom rounded backgrounds must not inherit the platform rectangular elevation animator. */
internal fun Button.flattenButtonSurface() {
    stateListAnimator = null
    elevation = 0f
    translationZ = 0f
    clipToOutline = true
    includeFontPadding = false
}
