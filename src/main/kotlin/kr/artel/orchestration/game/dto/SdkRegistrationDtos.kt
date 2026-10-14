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
 * 인증은 Authorization 헤더의 SDK 토큰이 한다. 본문에는 자격증명이 없다.
 *
 * @property projectId 사용자가 SDK 오버레이에서 고른 프로젝트. 이 사용자가 참여하는
 * 프로젝트가 아니면 404다
 * @property sdkUuid 런타임이 스스로 만들어 보관하는 설치 식별자. 같은 프로젝트 안에서
 * 이 값이 곧 인스턴스다. 처음 보는 값이면 인스턴스를 새로 만든다
 * @property instanceName 인스턴스를 처음 만들 때 붙일 이름. 사람이 알아볼 수 있도록 SDK가
 * 제품명이나 기기 이름을 보낸다. 비어 있으면 서버가 기본값을 붙인다. 이미 있는 인스턴스의
 * 이름은 덮어쓰지 않는다. 대시보드에서 고친 이름이 실행할 때마다 되돌아가면 안 된다
 * @property gameVersion Unity Player Settings의 버전 문자열
 * @property sceneScan SDK가 등록 시점에 만든 씬 스캔 JSON. 구조는 SDK가 정하고 서버는
 * 해석 없이 게임 빌드에 그대로 저장한다. 스캔이 없는 구버전 SDK는 생략한다
 * @property isEditor Unity editor 에서 실행 중인가(ARTEL-1014). true면 버전으로 빌드를 찾지 않고 프로젝트의
 * editor 행 하나에 붙어 그 행의 version 과 scene_scan 을 바꾼다. editor 는 development build 와 같은 버전을
 * 보고하므로 이 값이 없으면 둘이 한 행이 된다. 이 필드가 없는 구버전 SDK는 false 로 다룬다
 */
data class SdkRegistrationRequest(
    @field:NotBlank
    val projectId: String,

    @field:NotBlank
    @field:Size(max = 64)
    val sdkUuid: String,

    @field:Size(max = 80)
    val instanceName: String? = null,

    @field:NotBlank
    @field:Size(max = 64)
    val gameVersion: String,

    val sceneScan: JsonNode? = null,

    val isEditor: Boolean? = null
)

/**
 * SDK 등록 응답.
 *
 * instanceId는 SDK가 이후 웹소켓 핸드셰이크와 캡처 티켓 요청에 싣는 값이다. 등록이 그 값을
 * 알려주는 유일한 경로다.
 *
 * isEditor 는 gameBuildId 가 프로젝트의 editor 행이면 true 다.
 */
data class SdkRegistrationResponse(
    val instanceId: String,
    val projectId: String,
    val instanceName: String,
    val gameBuildId: String,
    val gameVersion: String,
    val isEditor: Boolean
)
