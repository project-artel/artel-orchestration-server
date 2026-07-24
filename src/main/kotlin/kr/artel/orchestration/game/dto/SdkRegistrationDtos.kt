package kr.artel.orchestration.game.dto

import com.fasterxml.jackson.databind.JsonNode
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * SDK 등록 요청. 게임을 실행할 때마다 보낸다.
 *
 * 매 실행마다 보내는 이유는 이 호출이 곧 버전 보고이기 때문이다. 빌드를 새로 올리면
 * gameVersion이 달라지고, 서버는 그 값을 보고서야 새 게임 빌드를 만들 수 있다.
 *
 * @property instanceKey 대시보드에서 발급받은 영구 자격증명. 이 요청의 유일한 인증 수단이다
 * @property sdkUuid 런타임이 스스로 만들어 보관하는 식별자. 자격증명이 아니라 기록이다
 * @property gameVersion Unity Player Settings의 버전 문자열
 * @property sceneScan SDK가 등록 시점에 만든 씬 스캔 JSON. 구조는 SDK가 정하고 서버는
 * 해석 없이 게임 빌드에 그대로 저장한다. 스캔이 없는 구버전 SDK는 생략한다
 */
data class SdkRegistrationRequest(
    @field:NotBlank
    @field:Size(max = 32)
    val instanceKey: String,

    @field:NotBlank
    @field:Size(max = 64)
    val sdkUuid: String,

    @field:NotBlank
    @field:Size(max = 64)
    val gameVersion: String,

    val sceneScan: JsonNode? = null
)

/**
 * SDK 등록 응답.
 *
 * SDK가 당장 쓰는 값은 없지만, 어느 프로젝트의 어느 인스턴스로 붙었는지 온보딩 창에
 * 보여줄 수 있어야 잘못된 키를 붙여넣은 것을 사람이 알아챌 수 있다.
 */
data class SdkRegistrationResponse(
    val instanceId: String,
    val projectId: String,
    val instanceName: String,
    val gameBuildId: String,
    val gameVersion: String
)
