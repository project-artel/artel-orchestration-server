package kr.artel.orchestration.testcase.generator

/**
 * 지도 TC 생성이 지금 어디쯤인지 바깥에 알리는 자리(ARTEL-1187).
 *
 * 생성기와 writer 는 이것만 안다. 누가 받아 어디로 흘리는지(SSE, 상태 레지스트리)는 서비스가 정한다. 부르는 쪽이
 * 아무것도 안 넘기면 [NONE] 이라 테스트와 내부 재생성은 지금 그대로다.
 */
fun interface GenerationProgress {
    fun report(phase: GenerationPhase, done: Int, total: Int)

    companion object {
        val NONE = GenerationProgress { _, _, _ -> }
    }
}

/** 생성 하나가 지나가는 단계. 화면은 단계마다 몫을 정해 도넛 하나로 잇는다. */
enum class GenerationPhase {
    /** 지도 단위 조회(호출 관계 · 인자 · 이름). 분모가 없다. */
    READING_MAP,

    /** 기능 행마다 케이스를 짓는다. 분모는 기능 행 수다. */
    DRAFTING,

    /** 지은 케이스를 저장한다. 분모는 케이스 수다. */
    WRITING,

    /** 앞 버전의 사용자 생성 케이스를 이어 넣는다. 분모가 없다. */
    CARRYING_OVER,
}
