package kr.artel.orchestration.knowledge.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.knowledge.entity.KnowledgeUsageEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

/**
 * 검색 사용 로그의 쓰기 경로(ARTEL-255).
 *
 * 행은 덧붙이기만 하고 지우지 않는다. 부피는 런당 (검색 횟수 상한 × 결과 상한)으로 유계라
 * 정리 잡이 필요 없다.
 *
 * **다만 순수 덧붙이기는 아니게 됐다(ARTEL-293).** `cited` 한 컬럼만 나중에 갱신된다 —
 * 검색 시점에는 그 항목이 쓰일지 알 수 없고, 답은 스텝 판정과 런 종료가 가져오기 때문이다.
 * 나머지 컬럼(무엇이·어느 버전이·몇 위로·어느 경로로 나갔나)은 **불변이다**: 그것들은 검색이
 * 실제로 한 일의 기록이라 나중에 고칠 여지가 없어야 하고, 고칠 수 있게 두면 지표의 근거가
 * 사후에 바뀔 수 있게 된다. 아래 두 갱신이 `SET cited = ...`만 하는 것은 그래서다.
 */
interface KnowledgeUsageRepository : CoroutineCrudRepository<KnowledgeUsageEntity, Long> {

    /** 한 런이 검색으로 읽은 항목 전부. 검증과 운영 조회용이다. */
    fun findByQaTryIdOrderByIdAsc(qaTryId: Long): Flow<KnowledgeUsageEntity>

    /**
     * 이 런이 [knowledgeId]를 판정에 썼다고 표시한다(ARTEL-293).
     *
     * **매칭은 런 스코프다.** 같은 `qa_try_id`, 같은 `knowledge_id`, 그리고 이 판정 시점
     * ([citedAt]) **이전에** 검색된 행. `step`은 조인 키가 아니다 — 2번 스텝에서 검색한 것을
     * 3번 스텝에서 인용하는 것은 정상 동작이고, step으로 묶으면 그 인용이 어디에도 안 찍힌다.
     * `case_id`도 마찬가지로 키가 될 수 없다(스텝에 case가 없을 수 있다).
     *
     * [citedAt] 비교가 있어야 **판정 이후에 검색된 행이 소급해서 true가 되지 않는다.** 없으면
     * 스텝 3의 인용이 스텝 5의 검색 결과까지 인용된 것으로 만든다.
     *
     * 한 항목이 여러 번 검색됐으면 그 행이 **모두** true가 된다. 그래서 비율은 행 단위가 아니라
     * **(런, 항목) 단위로 distinct 해서** 세야 한다 — 묻는 것은 "이 런에서 썼나"이지 "몇 번
     * 썼나"가 아니다.
     *
     * @return 표시된 행 수. 0은 오류가 아니다 — 검색된 적 없는 id를 인용한 경우이며, 호출자가
     *   그것을 감사 로그로 남긴다.
     */
    @Modifying
    @Query(
        """
        UPDATE knowledge_usage
        SET cited = true
        WHERE qa_try_id = :qaTryId
          AND knowledge_id = :knowledgeId
          AND retrieved_at <= :citedAt
        """
    )
    suspend fun markCited(qaTryId: Long, knowledgeId: Long, citedAt: Instant): Int

    /**
     * 끝난 런의 미인용 행을 `false`로 확정한다(ARTEL-293).
     *
     * **이것이 없으면 NULL(보고 불가)과 false(미인용)가 영영 갈리지 않고, `cited`를 nullable로
     * 둔 이유 전부가 무의미해진다.** "사장된 지식"이 이 기록의 최종 목적인데 그게 안 나온다.
     *
     * 세 술어가 각각 필요하다:
     * - `qt.status IN (...)` — 아직 도는 런을 확정하면 그 뒤의 인용을 잃는다.
     * - `qt.run_config ->> 'citation_reporting' = 'true'` — **인용을 보고할 수 있었던 런인지는
     *   추측이 아니라 기록으로 가른다.** Agent가 세션 개설 응답에 실어 준 표식이며, 없는 런은
     *   NULL로 남아야 한다. 이 술어를 빼면 구버전 런 전부가 "아무것도 인용하지 않았다"가 된다.
     * - `cited IS NULL` — 이미 답이 있는 행은 건드리지 않는다. 덕분에 두 번 불러도 안전하고,
     *   종료 경로가 겹쳐 두 번 도는 경우가 실제로 있다(런 취소 → 활성 try 취소 + 런 정리).
     *
     * `knowledge_mode=off` 런은 usage 행이 아예 없어 0행을 갱신하고 정상 종료한다. 스코프 런은
     * 다른 런과 똑같이 확정된다 — 스코프는 무엇이 보이는가의 문제이지 인용 기록의 문제가 아니다.
     */
    @Modifying
    @Query(
        """
        UPDATE knowledge_usage u
        SET cited = false
        FROM qa_try qt
        WHERE u.qa_try_id = qt.id
          AND qt.id = :qaTryId
          AND u.cited IS NULL
          AND qt.status IN ('COMPLETED', 'FAILED', 'CANCELLED')
          AND qt.run_config ->> 'citation_reporting' = 'true'
        """
    )
    suspend fun finalizeUncitedByQaTryId(qaTryId: Long): Int

    /**
     * [finalizeUncitedByQaTryId]와 같되 런(qa_run) 전체를 한 문장으로 확정한다.
     *
     * 런 시작 실패와 런 취소는 시나리오 try를 **한꺼번에** 종단으로 보낸다(`failByQaRunId`).
     * 그 경로에서 try id를 다시 읽어 하나씩 도는 것은 같은 일을 두 번 하는 것이고, 그 사이
     * 새로 종단된 try를 놓칠 수 있다. 종단이 아닌 try는 위와 같은 술어로 자동으로 빠진다.
     */
    @Modifying
    @Query(
        """
        UPDATE knowledge_usage u
        SET cited = false
        FROM qa_try qt
        WHERE u.qa_try_id = qt.id
          AND qt.qa_run_id = :qaRunId
          AND u.cited IS NULL
          AND qt.status IN ('COMPLETED', 'FAILED', 'CANCELLED')
          AND qt.run_config ->> 'citation_reporting' = 'true'
        """
    )
    suspend fun finalizeUncitedByQaRunId(qaRunId: Long): Int
}
