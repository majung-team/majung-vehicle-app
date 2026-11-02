package app.majung.vehicle

/** Only changes observed during this process may produce speech. No names or places are spoken. */
internal class PickupVoicePolicy {
    enum class Notice(val text: String) {
        READY("탑승자가 준비를 마쳤어요."),
        PROPOSED("탑승자가 만날 장소 변경을 요청했어요. 정차한 뒤 마중 화면에서 확인해주세요."),
        ARRIVED("약속 장소에 도착했어요. 안전하게 정차한 뒤 탑승을 확인해주세요.")
    }

    private var pickupId: String? = null
    private var previousStatus = ""
    private var previousReadiness = ""
    private var previousProposalId: String? = null
    private val announced = mutableSetOf<Notice>()
    private val announcedProposals = mutableSetOf<String>()

    fun observe(id: String?, status: String, readiness: String, eligible: Boolean, proposalId: String? = null): Notice? {
        if (id != pickupId) {
            pickupId = id
            previousStatus = status
            previousReadiness = readiness
            previousProposalId = proposalId
            announced.clear()
            announcedProposals.clear()
            if (!proposalId.isNullOrBlank()) announcedProposals.add(proposalId)
            return null
        }
        val proposalNotice = if (status == "ACTIVE" && !proposalId.isNullOrBlank() &&
            proposalId != previousProposalId && announcedProposals.add(proposalId)) Notice.PROPOSED else null
        val stateNotice = when {
            status == "ARRIVED" && previousStatus == "ACTIVE" -> Notice.ARRIVED
            status == "ACTIVE" && readiness == "READY" && previousReadiness != "READY" -> Notice.READY
            else -> null
        }
        previousStatus = status
        previousReadiness = readiness
        previousProposalId = proposalId
        if (stateNotice != null) {
            if (!announced.add(stateNotice)) return proposalNotice?.takeIf { eligible && id != null }
            return stateNotice.takeIf { eligible && id != null }
        }
        return proposalNotice?.takeIf { eligible && id != null }
    }
}
