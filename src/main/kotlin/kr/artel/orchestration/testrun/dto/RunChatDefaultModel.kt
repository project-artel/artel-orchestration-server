package kr.artel.orchestration.testrun.dto

/**
 * 챗봇에서 아무것도 고르지 않았을 때 저작이 쓰는 model(ARTEL-1427). 화면이 "기본 모델" 을 로고와 이름으로 보이는 데 쓴다.
 *
 * @property model 기능별 저작 model → 환경 변수 → 공용 key 가 유효한 provider 의 model 순으로 정한 값. 정할 수 없으면 null —
 *   그때는 agent-server 의 기본값으로 돈다.
 */
data class RunChatDefaultModel(val model: String?)
