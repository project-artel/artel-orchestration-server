package kr.artel.orchestration.stream.config

import kr.artel.orchestration.stream.dto.IceServer
import kr.artel.orchestration.stream.dto.VideoConstraints
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 화면 스트리밍 설정.
 *
 * ICE 서버 목록을 SDK에 컴파일해 넣지 않고 여기서 내려보내는 이유는, SDK가 고객사에 배포되는
 * 물건이기 때문이다. 기본값을 박아 두면 모든 고객의 게임이 제3자 STUN 호스트를 두드리게 된다.
 * 설정으로 두면 어디를 쓸지는 배포하는 쪽이 정한다.
 *
 * STUN 주소를 리스트가 아니라 쉼표 문자열로 받는 이유는 배포 방식 때문이다. 환경변수로 리스트를
 * 덮으려면 `ARTEL_..._0_URLS` 같은 인덱스 변수가 필요한데, 배포는 `.env` 한 장으로 이뤄지므로
 * 그 형태로는 실제로 바꿀 수가 없다. project/storage 설정이 빈 문자열을 "지정하지 않음"으로
 * 읽는 것과 같은 규칙을 쓴다.
 */
@ConfigurationProperties("artel.stream")
data class StreamProperties(
    val enabled: Boolean = true,
    val leaseSeconds: Long = 90,
    val stunUrls: String = "",
    val turnUrl: String = "",
    val turnUsername: String = "",
    val turnCredential: String = "",
    val maxWidth: Int = 1280,
    val maxFramerate: Int = 30
) {
    init {
        require(leaseSeconds >= MINIMUM_LEASE_SECONDS) {
            "artel.stream.lease-seconds must be at least $MINIMUM_LEASE_SECONDS"
        }
    }

    /**
     * 이 시간 안에 뷰어에게서 아무 메시지도 오지 않으면 세션을 끝낸다.
     *
     * 기준은 포그라운드 갱신 주기(10초)가 아니라 **조여진 탭의 갱신 주기**다. 브라우저는 숨겨진
     * 탭의 타이머를 조이고, 가장 나쁜 경우가 1분에 1회다. 그보다 짧은 임대는 탭을 다른 창 뒤로
     * 보낸 것만으로 정상 시청을 매 주기 잘라낸다. 기본값은 그 1분에 스케줄링 드리프트와 전송
     * 지연을 얹을 여유를 더해 잡혀 있다.
     *
     * 이것은 정지 신호가 아니라 백스톱이다. 뷰어 소켓이 닫히면 임대와 무관하게 그 자리에서
     * 스트림이 멈춘다([kr.artel.orchestration.stream.service.ViewerWebSocketHandler]). 임대가
     * 실제로 일하는 경우는 소켓이 닫히지 않고 사라졌을 때뿐이고, 그래서 이 값을 늘린 대가는
     * 정상 종료가 아니라 그 경우에 게임이 헛되이 인코딩하는 시간이다.
     */
    val lease: Duration get() = Duration.ofSeconds(leaseSeconds)

    val video: VideoConstraints get() = VideoConstraints(maxWidth, maxFramerate)

    /**
     * TURN은 대칭 NAT처럼 직접 연결이 불가능할 때만 필요하고, 그때는 영상 전체가 중계 서버를
     * 지나간다. 설정이 비어 있으면 목록에서 아예 빠진다. 자격증명 없는 TURN 항목을 남기면
     * 브라우저가 매번 실패하는 후보를 만드느라 연결만 늦어진다.
     */
    val iceServers: List<IceServer>
        get() = buildList {
            val stun = stunUrls.split(',').map(String::trim).filter(String::isNotEmpty)
            if (stun.isNotEmpty()) {
                add(IceServer(urls = stun))
            }

            if (turnUrl.isNotBlank() && turnUsername.isNotBlank() && turnCredential.isNotBlank()) {
                add(
                    IceServer(
                        urls = listOf(turnUrl.trim()),
                        username = turnUsername,
                        credential = turnCredential
                    )
                )
            }
        }

    private companion object {
        /**
         * 이 하한이 정하는 것은 "임대가 갱신 주기보다 긴가"가 아니라 **갱신을 몇 번까지 놓쳐도
         * 되는가**다.
         *
         * 그래서 기준이 포그라운드의 10초일 수 없다. 숨겨진 탭의 갱신은 1분에 1회까지 조여지고,
         * 그 주기를 못 넘는 임대는 유실 허용 폭이 0이라 정상 시청을 매번 잘라낸다. 60을 넘기는
         * 가장 작은 값이 그 폭이 존재하기 시작하는 지점이다.
         *
         * 하한이지 권고값이 아니다. 포그라운드만 도는 뷰어를 운영한다면 이 근처 값도 정당하다.
         */
        const val MINIMUM_LEASE_SECONDS = 61L
    }
}
