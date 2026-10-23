package app.majung.vehicle

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdkTimingRecorderTest {
    @Test fun noCallbacksMeansWholeWindowWasSilent() {
        val recorder = SdkTimingRecorder()
        recorder.begin(1000)
        val row = row(recorder.finish("passive", 121000), SdkTimingType.LOCATION)
        assertEquals(0, row.getInt("count"))
        assertEquals(120000L, row.getLong("maxSilenceMs"))
        assertTrue(row.isNull("p50Ms") && row.isNull("maxIntervalMs"))
    }

    @Test fun includesLeadingAndTrailingSilenceAndDoesNotTreatFirstValueAsChange() {
        val recorder = SdkTimingRecorder()
        recorder.begin(0)
        recorder.record(SdkTimingEvent(SdkTimingType.LOCATION, 100, null))
        recorder.record(SdkTimingEvent(SdkTimingType.LOCATION, 1100, false))
        recorder.record(SdkTimingEvent(SdkTimingType.LOCATION, 3100, true))
        recorder.record(SdkTimingEvent(SdkTimingType.LOCATION, 7100, false))
        val result = recorder.finish("polling", 12100)
        val row = row(result, SdkTimingType.LOCATION)
        assertEquals(4, row.getInt("count"))
        assertEquals(1, row.getInt("changed"))
        assertEquals(2, row.getInt("unchanged"))
        assertEquals(2000L, row.getLong("p50Ms"))
        assertEquals(4000L, row.getLong("p95Ms"))
        assertEquals(5000L, row.getLong("maxSilenceMs"))
        assertEquals(5000L, row.getLong("tailSilenceMs"))
        assertTrue(!result.toString().contains("latitude") && !result.toString().contains("token"))
    }

    private fun row(report: JSONObject, type: SdkTimingType): JSONObject {
        val rows = report.getJSONArray("events")
        return (0 until rows.length()).map { rows.getJSONObject(it) }.first { it.getString("type") == type.name }
    }
}
