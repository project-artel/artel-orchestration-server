package kr.artel.orchestration.testrun.dto

import java.time.Instant

/** TS 프레임 하나의 자리와 접힘. 좌표는 캔버스 좌표(px)다. */
data class RunMapFrame(
    val x: Double,
    val y: Double,
    val folded: Boolean = false,
)

/**
 * 맵 위 주석 하나. [type] 에 따라 쓰는 칸이 다르다.
 *
 * - `note` · `text`: [x] [y] [text] (메모는 [color] 도). [w] [h] 는 크기를 바꿨을 때만 온다.
 * - `rect` · `ellipse` · `section`: [x] [y] [w] [h], 안에 쓴 글은 [text]. `section` 은 왼쪽 위에 [text] 를 라벨로 단 묶음 상자다.
 * - `arrow`: [x1] [y1] [x2] [y2]. 휘었으면 조절점 [cx] [cy] 도 온다.
 * - `pen`: [points]
 *
 * [createdBy] · [createdAt] 은 서버가 채운다. 클라이언트가 보낸 값은 쓰지 않는다.
 */
data class RunMapAnnotation(
    val id: String,
    val type: String,
    val x: Double? = null,
    val y: Double? = null,
    val w: Double? = null,
    val h: Double? = null,
    val x1: Double? = null,
    val y1: Double? = null,
    val x2: Double? = null,
    val y2: Double? = null,
    val cx: Double? = null,
    val cy: Double? = null,
    val text: String? = null,
    val color: String? = null,
    val points: List<List<Double>>? = null,
    val createdBy: String? = null,
    val createdAt: Instant? = null,
)

/** `PUT .../map`. [version] 은 마지막으로 읽은 맵의 버전이다. 맵이 없을 때 읽은 값은 0. */
data class TestRunMapSaveRequest(
    val version: Int,
    val layout: Map<String, RunMapFrame> = emptyMap(),
    val annotations: List<RunMapAnnotation> = emptyList(),
)

data class TestRunMapResponse(
    val runId: String,
    val version: Int,
    val layout: Map<String, RunMapFrame>,
    val annotations: List<RunMapAnnotation>,
    val updatedAt: Instant?,
    val updatedBy: String?,
    /** 주석을 만든 사람과 마지막으로 저장한 사람의 이름. 키는 사용자 id. */
    val authors: Map<String, String>,
)
