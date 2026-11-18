package app.majung.vehicle.data.model

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ApiResponseModelTest {
    @Test
    fun legacySnapshotKeepsDefaultsAndDoesNotAssumeReadinessReceiptSupport() {
        val snapshot =
            PickupSnapshotResponse(
                JSONObject(
                    """{"status":"ACTIVE","participant":{"participantId":"test-passenger","readiness":"READY"}}"""
                )
            )
        assertEquals(1L, snapshot.placeVersion)
        assertEquals("READY", snapshot.readiness)
        assertEquals(0L, snapshot.readinessSequence)
        assertFalse(snapshot.supportsReadinessReceipt)
        assertNull(snapshot.proposedPlace)
        assertNull(snapshot.quickMessageText)
    }

    @Test
    fun fullSnapshotPreservesReceiptSequencesProposalAndMessageTimestamp() {
        val snapshot =
            PickupSnapshotResponse(
                JSONObject(
                    """{"status":"ACTIVE","placeVersion":7,"placeProposal":{"proposalId":"proposal","place":{"label":"예시 장소","latitude":37,"longitude":127}},"participant":{"readinessSequence":9,"readinessReceivedSequence":8},"quickMessage":{"text":"지금 내려가요","sentAt":"2026-11-18T00:00:00Z"}}"""
                )
            )
        assertEquals(7L, snapshot.placeVersion)
        assertEquals("proposal", snapshot.proposalId)
        assertEquals("예시 장소", snapshot.proposedPlace!!.label)
        assertTrue(snapshot.supportsReadinessReceipt)
        assertEquals(9L, snapshot.readinessSequence)
        assertEquals(8L, snapshot.receivedReadinessSequence)
        assertEquals("지금 내려가요", snapshot.quickMessageText)
        assertEquals("2026-11-18T00:00:00Z", snapshot.quickMessageSentAt)
    }

    @Test
    fun nullProposalAndBlankMessageAreAbsentWhileRequiredFieldsStillFail() {
        val snapshot =
            PickupSnapshotResponse(
                JSONObject("""{"placeProposal":{"proposalId":null},"quickMessage":{"text":" "}}""")
            )
        assertNull(snapshot.proposalId)
        assertNull(snapshot.quickMessageText)
        assertTrue(runCatching { snapshot.status }.isFailure)
        assertTrue(runCatching { RideRequestResponse.fromJson(JSONObject("{}")) }.isFailure)
    }

    @Test
    fun requestAndAccountModelsKeepIdentityAndPayloadChanges() {
        val data =
            JSONObject(
                """{"requestId":"request","status":"QUEUED","nickname":"시험 탑승자","majungId":"test-id","place":{"label":"시험 장소","latitude":37,"longitude":127}}"""
            )
        val first = RideRequestResponse.fromJson(data)
        val second = RideRequestResponse.fromJson(data.put("queuePosition", 2))
        assertEquals("request", first.requestId)
        assertEquals("시험 장소", first.place.label)
        assertNotEquals(first.signature, second.signature)
        val account =
            VehicleAccountResponse.fromJson(
                JSONObject("""{"status":"LINKED","majungId":"test-driver"}""")
            )
        assertEquals("LINKED", account.status)
        assertEquals("test-driver", account.majungId)
    }
}
