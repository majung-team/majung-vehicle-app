package app.majung.vehicle

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** No controls, scroll, links or editable fields are added to the restricted surface. */
internal class DrivingPickupSection(context: Context, private val app: VehicleApplication) {
    val root = LinearLayout(context).apply {
        id = R.id.driving_summary
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(40.dp(context), 28.dp(context), 40.dp(context), 28.dp(context))
        setBackgroundColor(Color.rgb(17,38,56))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    private val phase = label(context, 40f, true).apply { id = R.id.driving_phase }
    private val readiness = label(context, 32f, false).apply { id = R.id.driving_readiness }
    private val eta = label(context, 64f, true).apply { id = R.id.driving_eta }

    init { root.addView(phase); root.addView(readiness); root.addView(eta) }

    fun render(known: Boolean, stringLimit: Int, itemLimit: Int) {
        val pickup = app.pickup
        val seconds = pickup.place?.let { app.destination.telemetry.current(it, SystemClock.elapsedRealtime())?.durationSeconds }
        val summary = DrivingPickupSummary.from(known, pickup.status, pickup.online, pickup.needsSharingConfirmation,
            app.sharingRunning, pickup.readiness, seconds)
        val rows = listOf(phase to summary.phase, readiness to summary.readiness, eta to summary.eta)
        var remaining = if (known) itemLimit.coerceIn(0, 3) else 1
        val limit = if (known) stringLimit.coerceIn(0, 40) else 16
        for ((view, text) in rows) {
            val shown = if (remaining > 0 && limit > 0 && text.isNotBlank()) text.take(limit) else ""
            view.updateText(shown)
            view.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
            if (shown.isNotEmpty()) remaining--
        }
    }
    private fun label(context: Context, size: Float, bold: Boolean) = TextView(context).apply {
        textSize = size
        setTextColor(if (bold) Color.WHITE else Color.rgb(182, 211, 231))
        setPadding(0, 12.dp(context), 0, 12.dp(context))
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun Int.dp(context: Context) = (this * context.resources.displayMetrics.density).toInt()
}
