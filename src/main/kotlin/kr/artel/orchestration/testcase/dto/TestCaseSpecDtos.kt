package kr.artel.orchestration.testcase.dto

import java.time.Instant

/**
 * CSV 한 장을 받아 처리한 결과 요약.
 *
 * 보낸 쪽(Agent)이 무엇이 반영됐는지 알 수 있어야 재전송 여부를 판단할 수 있다. id 계열은
 * 담지 않는다 — 이 응답은 사용자 화면까지 흘러갈 수 있고, 내부 식별자를 노출할 이유가 없다.
 *
 * @property totalRows CSV에서 읽은 데이터 행 수(헤더 제외, 빈 줄 제외)
 * @property created 새로 만든 케이스 수
 * @property updated 이미 있어서 내용만 갱신한 케이스 수
 * @property skipped 케이스로 반영되지 않은 행 수 — 필수값(제목/기대결과) 누락, 또는 같은 CSV 안에서
 *   같은 (분류, 제목)이 반복돼 한 건으로 합쳐진 경우
 */
data class TestCaseSpecIngestResult(
    val totalRows: Int,
    val created: Int,
    val updated: Int,
    val skipped: Int,
)

/** 명세 XLSX 다운로드 티켓. 요청할 때마다 새로 발급하는 단기 URL이다. */
data class TestCaseSpecDownloadResponse(
    val downloadUrl: String,
    val expiresAt: Instant,
)
