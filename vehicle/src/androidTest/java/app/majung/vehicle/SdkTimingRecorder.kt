package app.majung.vehicle

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/** All mutations and snapshots run on the main thread. No coordinates, ETA values or tokens. */
internal class SdkTimingRecorder {
    private var started = 0L
    private val events = mutableListOf<SdkTimingEvent>()

    fun begin(now: Long) { started = now; events.clear() }
    fun record(event: SdkTimingEvent) { events.add(event) }

    fun finish(name: String, ended: Long): JSONObject {
        val rows = JSONArray()
        SdkTimingType.entries.forEach { type ->
            val received = events.filter { it.type == type }
            val gaps = received.zipWithNext { a, b -> b.elapsedMs - a.elapsedMs }.sorted()
            val first = received.firstOrNull()?.let { it.elapsedMs - started }
            val tail = received.lastOrNull()?.let { ended - it.elapsedMs }
            fun percentile(fraction: Double): Any = if (gaps.isEmpty()) JSONObject.NULL
                else gaps[(ceil(gaps.size * fraction).toInt() - 1).coerceAtLeast(0)]
            rows.put(JSONObject().put("type", type.name).put("count", received.size)
                .put("changed", received.count { it.changed == true })
                .put("unchanged", received.count { it.changed == false })
                .put("intervalCount", gaps.size).put("p50Ms", percentile(0.50)).put("p95Ms", percentile(0.95))
                .put("maxIntervalMs", gaps.lastOrNull() ?: JSONObject.NULL)
                .put("firstWaitMs", first ?: JSONObject.NULL).put("tailSilenceMs", tail ?: JSONObject.NULL)
                .put("maxSilenceMs", maxOf(first ?: ended - started, tail ?: ended - started, gaps.lastOrNull() ?: 0)))
        }
        return JSONObject().put("phase", name).put("durationMs", ended - started).put("events", rows)
    }
}
