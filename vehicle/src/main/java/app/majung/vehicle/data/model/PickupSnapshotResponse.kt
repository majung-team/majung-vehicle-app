package app.majung.vehicle.data.model

import app.majung.vehicle.navi.PickupPlace
import org.json.JSONObject

/** Typed view of one response; JSON access and compatibility defaults stay at this boundary. */
internal class PickupSnapshotResponse(private val data: JSONObject) {
    val placeVersion
        get() = data.optLong("placeVersion", 1)

    val status
        get() = data.getString("status")

    private val proposal
        get() = data.optJSONObject("placeProposal")

    val proposalId
        get() = proposal?.optString("proposalId")?.takeIf { it.isNotBlank() && it != "null" }

    val proposedPlace
        get() = proposal?.optJSONObject("place")?.toPlace()

    val place
        get() = data.optJSONObject("place")?.toPlace()

    private val participant
        get() = data.optJSONObject("participant")

    val participantId
        get() = participant?.optString("participantId")

    val participantName
        get() = participant?.optString("displayName")

    val readiness
        get() = participant?.optString("readiness") ?: ""

    val readinessSequence
        get() = participant?.optLong("readinessSequence", 0) ?: 0

    val receivedReadinessSequence
        get() = participant?.optLong("readinessReceivedSequence", 0) ?: 0

    val supportsReadinessReceipt
        get() = participant?.has("readinessReceivedSequence") == true

    val quickMessageText
        get() = data.optJSONObject("quickMessage")?.optString("text")?.takeIf { it.isNotBlank() }

    val quickMessageSentAt
        get() = data.optJSONObject("quickMessage")?.optString("sentAt")

    private fun JSONObject.toPlace() =
        PickupPlace(getString("label"), getDouble("latitude"), getDouble("longitude"))
}
