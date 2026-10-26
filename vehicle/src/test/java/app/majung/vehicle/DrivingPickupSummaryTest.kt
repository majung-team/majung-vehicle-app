package app.majung.vehicle

import org.junit.Assert.*
import org.junit.Test

class DrivingPickupSummaryTest {
    private fun active(known: Boolean = true, online: Boolean = true, confirmation: Boolean = false,
                       sharing: Boolean = true, seconds: Long? = 420) =
        DrivingPickupSummary.from(known,"ACTIVE",online,confirmation,sharing,"READY",seconds)
    @Test fun onlyFreshSharingShowsEtaAndZeroNeverClaimsArrival() {
        assertEquals("약 7분 남음",active().eta)
        assertEquals("곧 도착",active(seconds=0).eta)
        assertEquals("남은 시간 확인 중",active(seconds=null).eta)
        assertEquals("남은 시간 확인 중",active(seconds=-1).eta)
        assertFalse(active(seconds=0).phase.contains("도착"))
    }
    @Test fun unavailableUnconfirmedAndStoppedSharingNeverShowPreviousEtaOrReadiness() {
        for (summary in listOf(active(known=false),active(online=false),active(confirmation=true))) {
            assertEquals("",summary.readiness)
            assertFalse(summary.eta.contains("7분"))
        }
        assertEquals("위치 공유가 멈춰 있어요",active(sharing=false).eta)
    }
    @Test fun arrivalAndTerminationHaveDifferentMessages() {
        val arrived=DrivingPickupSummary.from(true,"ARRIVED",true,false,true,"PREPARING",0)
        assertEquals("약속 장소에 도착했어요",arrived.phase)
        assertEquals("정차 후 탑승을 확인해주세요",arrived.eta)
        for (state in listOf("COMPLETED","CANCELLED","EXPIRED")) {
            val ended=DrivingPickupSummary.from(true,state,true,false,true,"READY",420)
            assertEquals("픽업 공유 종료",ended.phase); assertEquals("",ended.readiness); assertEquals("",ended.eta)
        }
    }
}
