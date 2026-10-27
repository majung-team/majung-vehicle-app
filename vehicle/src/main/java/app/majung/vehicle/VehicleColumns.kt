package app.majung.vehicle

import android.content.Context
import android.widget.LinearLayout

/** Use side-by-side cards only when both remain readable in the current app window. */
internal class VehicleColumns(context: Context) : LinearLayout(context) {
    val leading = LinearLayout(context).apply { orientation = VERTICAL }
    val trailing = LinearLayout(context).apply { orientation = VERTICAL }
    private var wide: Boolean? = null

    init {
        addView(leading)
        addView(trailing)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val horizontal = MeasureSpec.getSize(widthMeasureSpec) >= (960 * density).toInt()
        if (wide != horizontal) {
            wide = horizontal
            orientation = if (horizontal) HORIZONTAL else VERTICAL
            leading.layoutParams = if (horizontal) LayoutParams(0, -2, 1.1f) else LayoutParams(-1, -2)
            trailing.layoutParams = (if (horizontal) LayoutParams(0, -2, 1f) else LayoutParams(-1, -2)).apply {
                if (horizontal) marginStart = (28 * density).toInt() else topMargin = (20 * density).toInt()
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
