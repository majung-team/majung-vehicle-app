package app.majung.vehicle

/** Only changes observed during this process may produce speech. No names or places are spoken. */
internal class PickupVoicePolicy {
    enum class Notice(val text: String) {
        READY("탑승자가 준비를 마쳤어요."),
        ARRIVED("약속 장소에 도착했어요. 안전하게 정차한 뒤 탑승을 확인해주세요.")
    }

    private var pickupId: String? = null
    private var previousStatus = ""
    private var previousReadiness = ""
    private val announced = mutableSetOf<Notice>()

    fun observe(id: String?, status: String, readiness: String, eligible: Boolean): Notice? {
        if (id != pickupId) {
            pickupId = id
            previousStatus = status
            previousReadiness = readiness
            announced.clear()
            return null
        }
        val notice = when {
            status == "ARRIVED" && previousStatus == "ACTIVE" -> Notice.ARRIVED
            status == "ACTIVE" && readiness == "READY" && previousReadiness != "READY" -> Notice.READY
            else -> null
        }
        previousStatus = status
        previousReadiness = readiness
        // Even suppressed events are consumed: enabling/reconnecting must not replay old changes.
        if (notice == null || !announced.add(notice)) return null
        return notice.takeIf { eligible && id != null }
    }
}
