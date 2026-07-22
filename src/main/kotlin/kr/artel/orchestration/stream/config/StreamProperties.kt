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
    val leaseSeconds: Long = 15,
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
     * 이 시간 안에 뷰어에게서 아무 메시지도 오지 않으면 세션을 끝낸다. 브라우저는 10초마다
     * 갱신을 보내므로, 이 값이 그보다 넉넉해야 정상 연결이 잘려나가지 않는다.
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
        /** 갱신 주기가 10초라, 그보다 짧은 임대는 정상 연결을 끊는 설정이다. */
        const val MINIMUM_LEASE_SECONDS = 11L
    }
}
