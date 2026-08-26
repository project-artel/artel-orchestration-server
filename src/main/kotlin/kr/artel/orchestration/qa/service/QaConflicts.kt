package kr.artel.orchestration.qa.service

import kr.artel.orchestration.common.error.ConflictException

/*
 * QA 시작이 거절되는 사유들. 셋 다 409라서 상태코드로는 갈리지 않고, 클라이언트는 **`code`로**
 * 구분한다. 예전에는 FE가 message에 "sdk"가 들어 있는지를 정규식으로 봤는데, 그 message는 우리가
 * 고쳐 쓰는 산문이라 문구를 다듬는 순간 분기가 조용히 틀어진다 — 실제로 "시나리오가 없다"가
 * "이미 실행 중이다"로 표시되고 있었다. code는 계약이므로 그렇게 움직이지 않는다.
 */

/** 그 게임 인스턴스에 SDK가 붙어 있지 않다. 사용자가 게임을 켜면 풀린다. */
class SdkDisconnectedException :
    ConflictException("Game instance SDK is not connected", code = "sdk_disconnected")

/**
 * 그 게임 인스턴스에 아직 안 끝난 QA가 있다.
 *
 * 막다른 오류가 아니라 **선택지**다: 클라이언트는 이 code를 보고 "진행 중인 QA를 종료하고
 * 실행할까요?"를 물은 뒤 `force`로 같은 요청을 다시 보낼 수 있다(런 이어받기).
 */
class ActiveQaRunException(message: String = "An active QA run already exists") :
    ConflictException(message, code = "qa_run_active")

/** 실행할 시나리오가 하나도 없는 테스트 런. */
class EmptyTestRunException :
    ConflictException("Test run has no scenarios to execute", code = "test_run_empty")
