package kr.artel.orchestration.contentmap

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.contentmap.evidence.EvidenceParser
import kr.artel.orchestration.contentmap.join.EvidenceJoin
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 씬 귀속에 실패한 unplaced 타입의 write 가 버려지지 않는다(V95).
 *
 * 실측 근거(run 60): `Tutorial.TutorialController` 는 스폰 출처가 안 잡혀 capability 가 못
 * 되고, 그 타입만 쓰는 `waitingForAcknowledge` 는 토글인데 얼어붙은 값으로 읽혔다. 그래서
 * 한 흐름에서 번갈아 참이 되는 케이스들(`== 0` 과 `!= 0`)이 모순으로 나뉘었다. 이 검사는
 * 실제 지도 문서에서 그 write 가 [EvidenceJoin.strayWrites] 로 살아남는 것을 못 박는다.
 */
class EvidenceJoinStrayWritesTest {

    @Test
    fun `귀속 실패한 unplaced 타입의 write 가 stray 로 남는다`() {
        val json = File("src/test/resources/contentmap/wv-play-2026-09-01.json").readText()
        val model = EvidenceParser(ObjectMapper()).parse(json)

        val strays = EvidenceJoin(model).strayWrites()

        assertThat(strays.map { it.target })
            .contains("TutorialController.waitingForAcknowledge")
        // capability 로 이미 들어간(스폰 귀속 성공) 타입은 여기 안 온다 — 두 벌이 되면 안 된다.
        assertThat(strays.map { it.type }).doesNotContain("Cards.CardManager")
    }
}
