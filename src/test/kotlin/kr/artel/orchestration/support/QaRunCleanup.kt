package kr.artel.orchestration.support

import org.springframework.r2dbc.core.DatabaseClient

/**
 * `qa_run` 과 그 아래를 FK 순서로 비운다.
 *
 * **왜 한 곳에 있나.** 리액티브 트랜잭션은 롤백되지 않고 스위트 전체가 DB 하나를 공유하므로,
 * 테스트가 자기 행을 직접 지운다. 그 순서를 클래스마다 손으로 적으면 한 곳만 틀려도 **남의
 * 파일이 터진다** — 실패가 자기 파일에서 안 나므로 원인을 찾기 어렵고, 클래스 실행 순서가
 * 바뀔 때마다 피해자가 달라진다. 실제로 [kr.artel.orchestration.contentmap.ScreenObservationTest]
 * 가 `qa_try` 를 빠뜨려 develop 스위트를 16 개 깨뜨리고 있었다(ARTEL-795).
 *
 * **왜 `qa_try` 하나만 먼저 지우면 되나.** `qa_run` 을 참조하는 FK 12 개 중 11 개가
 * `ON DELETE CASCADE` 또는 `SET NULL` 이고, `qa_try.qa_run_id` 하나만 아무 절이 없어
 * `NO ACTION` 이다(`V30__create_qa_run.sql`). 막는 것은 그 하나뿐이다.
 *
 * `qa_try` 의 자식들(`issue` · `qa_log` · `qa_try_score` · `sdk_performance_*`)도 전부
 * `ON DELETE CASCADE` 라 여기서 따로 지우지 않는다.
 *
 * **`qa_try.qa_run_id` 를 CASCADE 로 바꾸지 않은 것은 의도다.** 운영에는 `qa_run` 을 지우는
 * 코드가 없어(`grep` 으로 0 건) 그 절은 지금 테스트만 건드리는데, 바꾸면 실수로 부른
 * `DELETE FROM qa_run` 이 QA 이력을 조용히 지운다. 지금은 거절당한다.
 */
fun DatabaseClient.deleteQaRuns() {
    sql("DELETE FROM qa_try").then().block()
    sql("DELETE FROM qa_run").then().block()
}
