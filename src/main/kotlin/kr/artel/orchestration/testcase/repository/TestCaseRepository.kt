package kr.artel.orchestration.testcase.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.testcase.dto.CapabilityCase
import kr.artel.orchestration.testcase.dto.CaseWrite
import kr.artel.orchestration.testcase.dto.CaseInputRow
import kr.artel.orchestration.testcase.dto.SceneExitRow
import kr.artel.orchestration.testcase.dto.ValueMoveRow
import kr.artel.orchestration.testcase.dto.StartingValue
import kr.artel.orchestration.testcase.dto.ValueRaiser
import kr.artel.orchestration.testcase.dto.TestCaseListItem
import kr.artel.orchestration.testcase.dto.UncoveredScene
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * TestCase 조회 리포지토리(코루틴). 조회는 프로젝트 스코프이며, 씬(scene)·검증상태로 선택 필터한다.
 * 멱등 적재(Agent 재전송 중복 방지)를 위해 spec_id 조회와 (project_id, scene, step) 조회를 함께 제공한다.
 */
interface TestCaseRepository : CoroutineCrudRepository<TestCaseEntity, Long> {

    fun findByProjectIdOrderByIdDesc(projectId: Long): Flow<TestCaseEntity>

    /**
     * 저작 세션에 실을 전량. **id 오름차순은 계약이다** — 이 목록이 프롬프트 앞 고정 블록에 실려
     * 캐시를 타므로 순서가 흔들리면 매 턴 전량을 다시 청구한다([findTestCaseListByProjectIdOrderByIdAsc]와
     * 같은 이유). 그쪽과 달리 엔티티를 통째로 읽는 것은 `metadata` 가 필요하기 때문이다 — 케이스가
     * 실행 뒤 무엇을 확정하는지(`source.state_after`)가 거기 있고, 그 값이 정규화해 보낼 상태의 절반이다.
     */
    fun findByProjectIdOrderByIdAsc(projectId: Long): Flow<TestCaseEntity>

    /**
     * 저작 Agent에 실을 전량 목록(ARTEL-318). 엔티티가 아니라 [TestCaseListItem]로 좁혀 읽는다.
     *
     * 엔티티를 그대로 읽지 않는 것은 Agent가 쓰지 않는 컬럼(`last_verified_build_id`, 타임스탬프)까지
     * 프롬프트로 흘러가지 않게 하기 위해서다. 본문(`precondition`/`expected`)은 **의도적으로 포함한다** —
     * 고른 케이스로 스텝을 쓰려면 필요하고, 빼면 다시 가져오는 왕복이 생긴다([TestCaseListItem] 참고).
     *
     * **`ORDER BY id ASC`는 취향이 아니라 계약이다.** 이 목록은 Agent 프롬프트의 앞쪽 고정 블록에
     * 실려 프롬프트 캐시를 타므로, 줄 순서가 조회마다 흔들리면 캐시가 통째로 깨져 전량을 매 턴 다시
     * 청구한다. 같은 파일의 다른 조회들이 최신순(id DESC)인 것과 다른 이유가 이것이다.
     */
    @Query(
        """
        SELECT id, scene, step, precondition, expected_value, verification_status
        FROM test_case
        WHERE project_id = :projectId
        ORDER BY id ASC
        """
    )
    fun findTestCaseListByProjectIdOrderByIdAsc(projectId: Long): Flow<TestCaseListItem>

    /**
     * 이 프로젝트의 TestCase id 전량(2단계).
     *
     * 저작 결과를 검사하는 두 기준이 이 집합이다: 판정이 전량을 덮었는지, 스텝이 지목한 번호가
     * 실재하는지. 본문은 필요 없어서 id만 읽는다 — 1000건이라도 한 컬럼이다.
     *
     * **깨진 것은 뺀다**(ARTEL-685). 지도를 다시 적재하면 더 이상 뒷받침되지 않는 케이스가
     * `BROKEN` 으로 남는다 — 지우지 않는 것은 무엇이 사라졌는지 보이게 하려는 것이다. 그런데 그것을
     * 덮으라고 요구하면 **저작이 영영 통과하지 못한다**: 실측(런 266)에서 지도가 더 이상 내지 않는
     * `Return` 케이스를 안 담았다고 저장이 막혔다. 담을 수도 없다 — 그 조작은 지도에 없다.
     */
    @Query("SELECT id FROM test_case WHERE project_id = :projectId AND verification_status <> 'BROKEN'")
    fun findIdsByProjectId(projectId: Long): Flow<Long>

    /**
     * 어떤 시나리오도 아직 건드리지 않은 케이스의 id(2단계).
     *
     * 커버 집합은 `test_scenario.steps`의 `case_id` 합집합이다 — **원장을 따로 저장하지 않는다.**
     * 값이 이미 있는데 복제하면 진실이 둘이 되고, 시나리오를 고칠 때마다 동기화가 숙제로 남는다.
     *
     * **어떤 런에도 담기지 않은 시나리오는 세지 않는다**(ARTEL-495). 커버리지가 프로젝트의 모든
     * 시나리오를 세던 때에는, 런을 지우면 남은 시나리오가 케이스를 계속 "담긴 것"으로 만들어
     * 사용자가 보기에 숫자가 그대로였다. 지금은 런에서 떨어지는 순간 커버에서 빠지고, 그 시나리오를
     * 다른 런에 다시 넣으면 그대로 돌아온다 — **시나리오는 지우지 않고 커버리지만 따라간다.**
     *
     * 뜻으로도 이쪽이 맞다: 어느 런에도 없는 시나리오는 실행될 일이 없고, 실행되지 않는 것이
     * 케이스를 검증한다고 말할 수는 없다. 대신 런에 넣지 않은 채 만든 시나리오는 미커버로 보인다.
     *
     * `case_id`가 없는 스텝(이동·준비 같은 브리지)은 자연히 빠진다 — 검증을 하지 않으므로 무엇도
     * 커버하지 않는다.
     *
     * 정렬을 `id ASC`로 고정하는 이유는 전량 목록과 같다: 이 값도 세션 프롬프트로 나가므로 순서가
     * 흔들리면 캐시 접두사가 깨진다.
     */
    @Query(
        """
        SELECT c.id FROM test_case c
        WHERE c.project_id = :projectId
          AND NOT EXISTS (
            SELECT 1 FROM test_scenario s, jsonb_array_elements(s.steps) e
            WHERE s.project_id = :projectId
              AND e->>'case_id' IS NOT NULL
              AND (e->>'case_id')::bigint = c.id
              AND EXISTS (
                SELECT 1 FROM test_run_scenario rs WHERE rs.test_scenario_id = s.id
              )
          )
        ORDER BY c.id ASC
        """
    )
    fun findUncoveredIdsByProjectId(projectId: Long): Flow<Long>

    /**
     * 미커버가 **어느 씬에 몇 건씩** 남았는지(ARTEL-403). 저작이 끝난 뒤 다음에 할 일을 권할 때 쓴다.
     *
     * id 목록만으로는 사람이 무엇이 남았는지 알 수 없다 — 번호는 화면에 내보내지도 않는 값이다.
     * 씬은 사용자가 아는 말이라 "전투 화면 12건이 남았다"가 곧 다음 요청이 된다.
     *
     * 세는 범위는 [findUncoveredIdsByProjectId] 와 같다 — 어떤 런에도 담기지 않은 시나리오는 빼고
     * 센다(ARTEL-495). 두 질의가 다른 것을 세면 화면의 숫자와 권하는 문구가 갈린다.
     *
     * 많은 순으로 낸다. 다음에 할 일을 고르는 자리라 큰 덩어리가 먼저 보이는 편이 쓸모 있다.
     */
    @Query(
        """
        SELECT c.scene AS scene, count(*) AS count
        FROM test_case c
        WHERE c.project_id = :projectId
          AND NOT EXISTS (
            SELECT 1 FROM test_scenario s, jsonb_array_elements(s.steps) e
            WHERE s.project_id = :projectId
              AND e->>'case_id' IS NOT NULL
              AND (e->>'case_id')::bigint = c.id
              AND EXISTS (
                SELECT 1 FROM test_run_scenario rs WHERE rs.test_scenario_id = s.id
              )
          )
        GROUP BY c.scene
        ORDER BY count(*) DESC, c.scene ASC
        """
    )
    fun findScenesOfUncovered(projectId: Long): Flow<UncoveredScene>

    /** 프로젝트의 전체 케이스 수(ARTEL-403). 커버리지의 분모다. */
    suspend fun countByProjectId(projectId: Long): Long

    /**
     * 검증 상태별 건수(ARTEL-403). 화면의 두 축 중 "QA 런이 실제로 무엇을 냈는가" 쪽이다.
     *
     * 상태마다 한 번씩 부른다. 한 질의로 GROUP BY 하는 편이 짧지만 그러려면 (상태, 건수) 짝을
     * 담을 타입이 필요한데, 있는 타입을 재사용하면 필드 이름이 거짓말을 하게 된다(`scene`에
     * `VERIFIED`가 들어간다). 세 번 세는 값이 세 줄일 뿐이다.
     */
    suspend fun countByProjectIdAndVerificationStatus(projectId: Long, verificationStatus: String): Long

    /**
     * 명세 적재의 **보조** 키 — `spec_id`가 아직 없는 행만 고른다(ARTEL-329).
     *
     * spec_id가 붙기 전에 만들어진 행(손으로 만든 케이스, 이 계약 이전의 적재)을 새 명세가 이어받게
     * 하려고 둔다. **이미 다른 spec_id를 가진 행은 절대 고르지 않는다** — 씬+스텝은 케이스를 유일하게
     * 가리키지 못하기 때문이다. 실제 명세에서 `Map_scene / Map_scene에 진입해 관찰한다` 하나가
     * 사전조건만 다른 6건이었고, 이 조건이 없을 때 그 6건이 한 행으로 겹쳐 5건이 조용히 사라졌다.
     *
     * `findFirst`인 이유도 같다: 같은 씬+스텝이 여럿인 것이 정상이라, 단건 반환 시그니처는 언젠가
     * "결과가 유일하지 않다"로 적재 전체를 세운다. 정렬을 고정해 어느 행을 잇는지도 결정적으로 둔다.
     */
    suspend fun findFirstByProjectIdAndSceneAndStepAndSpecIdIsNullOrderByIdAsc(
        projectId: Long,
        scene: String,
        step: String
    ): TestCaseEntity?

    /** 명세 적재의 멱등 키(ARTEL-329). spec_id가 있는 케이스는 문구가 바뀌어도 같은 행으로 이어진다. */
    suspend fun findByProjectIdAndSpecId(projectId: Long, specId: String): TestCaseEntity?

    /**
     * 이 프로젝트가 이미 그 판의 명세를 받아 뒀는가(ARTEL-329).
     *
     * SDK 재등록마다 같은 명세가 다시 오는데, 그때마다 수백 행을 upsert하고 XLSX를 새로 써서
     * S3에 올릴 이유가 없다. 한 건이라도 그 revision이면 같은 판이 이미 반영된 것이다.
     */
    suspend fun existsByProjectIdAndSpecRevision(projectId: Long, specRevision: Int): Boolean

    /**
     * 시나리오가 스텝에 번호로 들고 있는 케이스들(ARTEL-578).
     *
     * 시나리오는 `test_scenario.steps` JSONB 안에 `case_id` 를 숫자로 담는다 — 외래 키가 아니라서
     * 케이스를 지워도 DB 가 막지 않고, 시나리오에 **가리키는 것이 없는 번호**만 남는다.
     *
     * 지도에서 사라진 기능의 케이스를 지울 때 이것을 먼저 본다. 인용된 줄은 지우는 대신 `BROKEN` 으로
     * 돌려, 시나리오가 상했다는 것을 사람이 보게 한다.
     */
    @Query(
        """
        SELECT DISTINCT (step->>'case_id')::BIGINT AS id
        FROM test_scenario
        CROSS JOIN LATERAL jsonb_array_elements(steps) AS step
        WHERE project_id = :projectId
          AND jsonb_typeof(steps) = 'array'
          AND step->>'case_id' IS NOT NULL
        """
    )
    fun findCaseIdsCitedByScenarios(projectId: Long): Flow<Long>

    /**
     * 이 프로젝트의 케이스들이 **바꾸는 값**(ARTEL-581).
     *
     * 두 곳이 읽는다 — 나눔이 순서를 알 때(ARTEL-581)와, 저작에 "이 케이스를 실행하면 무엇이
     * 바뀌나"를 말할 때(ARTEL-606). 같은 사실이라 질의도 하나다.
     *
     * 나눔이 순서를 알려면 필요하다 — 앞 스텝이 바꾼 값을 뒤 스텝이 전제로 삼는 것은 모순이 아니다.
     * 케이스 메타의 `state_after` 로는 답이 안 된다. 그것은 구버전 엑셀 경로가 넣던 칸이라 지도가
     * 낸 케이스에는 없다.
     *
     * **`kind = 'write'` 만 본다.** `transform` 은 화면 위의 좌표(`character.transform.position`)라
     * 논리 값(`MapMove.position`)이 아닌데, 꼬리로 견주면 둘이 `position` 에서 만나 서로 다른 값을
     * 하나로 뭉갠다. 실제로 그 둘이 한 기능에 나란히 달려 있다.
     *
     * 지도를 못 되짚는 케이스(`capability_key` 가 `NULL` 인 구버전 행)는 여기 안 나온다. 그때는
     * 바꾸는 것을 모르는 것이고, 모르면 예전처럼 나눈다.
     */
    @Query(
        """
        SELECT tc.id AS case_id, ce.target AS target, ce.detail AS detail
        FROM test_case tc
        JOIN capability c ON c.capability_key = tc.capability_key
        JOIN scene s ON s.id = c.scene_id
        JOIN content_map cm ON cm.id = s.content_map_id
        JOIN game_build gb ON gb.id = cm.game_build_id AND gb.project_id = tc.project_id
        JOIN capability_effect ce ON ce.capability_id = c.id AND ce.kind = 'write'
        WHERE tc.project_id = :projectId
          AND tc.capability_key IS NOT NULL
          AND ce.target IS NOT NULL
          AND c.merged_into IS NULL
        """
    )
    fun findValuesChangedByCases(projectId: Long): Flow<CaseWrite>

    /**
     * **화면에서 화면으로 가는 한 걸음, 그리고 무엇을 눌러야 가는지**(ARTEL-628).
     *
     * 지도는 이미 답을 안다. 저작에 안 보내고 있었을 뿐이다:
     *
     * ```
     * Map_scene      → TurnBattleScene   Return
     * Map_scene      → TitleScene        Canvas/Button (Legacy)
     * TitleScene     → Map_scene         Canvas/MapSceneButton
     * GameClearScene → Map_scene         any
     * StoryScene     → Map_scene         (없음 — 저절로 간다)
     * ```
     *
     * **`by` 가 비는 것도 답이다.** 실측 19간선 중 12건이 `not-a-step` 이고, 그건 게임이 알아서
     * 넘기는 자리라는 뜻이다 — 누를 것을 찾아 헤맬 필요가 없다는 정보다. 못 찾은 것과 없는 것을
     * 섞지 않으려고, 기능이 매달려 있는데 조작이 없는 경우만 빈 값으로 답한다.
     *
     * 키가 먼저고 라벨·경로가 그다음이다. 실행하는 쪽이 그대로 보낼 수 있는 것이 키이기 때문이다.
     *
     * 지도를 먼저 하나로 고르고 나서 간선을 훑는다 — `test_case` 를 바깥에 두면 케이스 한 줄마다
     * 간선 전체가 딸려 와 행이 곱으로 분다([findWrittenValues] 에 실측이 있다).
     */
    @Query(
        """
        SELECT DISTINCT s.name AS from_scene,
               e.to_scene_name AS to_scene,
               coalesce(c.input_key, c.control_label, c.control_path) AS by_operation
        FROM scene s
        JOIN scene_edge e ON e.from_scene_id = s.id
        LEFT JOIN capability c ON c.id = e.capability_id
          AND c.interaction <> 'none'
          AND c.actionability NOT IN ('not-a-step', 'unreachable-precondition')
        WHERE s.content_map_id IN (
            SELECT DISTINCT s2.content_map_id
            FROM test_case tc
            JOIN capability c2 ON c2.capability_key = tc.capability_key
            JOIN scene s2 ON s2.id = c2.scene_id
            WHERE tc.project_id = :projectId
        )
        """
    )
    fun findSceneExits(projectId: Long): Flow<SceneExitRow>

    /**
     * 케이스마다 **그것이 가리키는 조작의 기계값**.
     *
     * 저작이 스텝의 `input` 칸에 그대로 넣는 값이다(`key:Return` · `click:Canvas/continue`).
     * 케이스 이름은 사람이 읽는 문장이라 거기서 되뽑을 수 없고, 되뽑는 것이 이 개편이 없애려는
     * 문자열 맞춤이다.
     *
     * 앞서 이 값은 `explain_case` 도구로만 얻을 수 있었다. 실측(저작 한 판)에서 그 도구가 준 것
     * 중 케이스 목록에 없던 것이 이 한 칸뿐이었고, 나머지(씬 · 요구 · 남기는 것)는 전부 이미
     * 실려 있었다. 한 칸을 옮기면 왕복이 사라진다.
     *
     * **누를 것이 없으면 빈 값이다.** `interaction` 을 그대로 넣으면 조작 없이 일어나는 기능이
     * `input: "none"` 으로 스텝에 박히고, 실행하는 쪽은 그것을 누르라는 뜻으로 읽는다.
     */
    @Query(
        """
        SELECT tc.id AS test_case_id,
               CASE
                   WHEN c.input_key IS NOT NULL THEN 'key:' || c.input_key
                   WHEN c.control_path IS NOT NULL THEN 'click:' || c.control_path
                   WHEN c.control_label IS NOT NULL THEN 'click:' || c.control_label
                   ELSE ''
               END AS input
        FROM test_case tc
        JOIN capability c ON c.capability_key = tc.capability_key AND c.merged_into IS NULL
        WHERE tc.project_id = :projectId AND tc.capability_key IS NOT NULL
        """
    )
    fun findCaseInputs(projectId: Long): Flow<CaseInputRow>

    /**
     * **지도 안에서 움직이는 값들**(ARTEL-625).
     *
     * [findValuesChangedByCases] 는 **케이스가 된 기능**만 본다. 그래서 게임이 스스로 움직이는 값이
     * 영영 안 바뀌는 것으로 보이고, 그 값을 두고 갈리는 갈래들이 전부 따로 잘린다 — 실측(프로젝트
     * 24)에서 맵의 `Return` 케이스가 그랬다. `MapMove.StagePosition` 을 올리는 것은 전투를 이기는
     * 일이라 어떤 케이스도 안 쓰고, 그래서 20스텝짜리 하나에서 1스텝짜리 셋이 떨어져 나왔다.
     *
     * 한 순간만 보면 `== 1` 과 `== 2` 는 함께 못 선다. 시간 위에서는 이겨서 올라가는 계단이다.
     *
     * **`active-state` 도 본다.** 무엇이 켜지고 꺼지는 것도 시간 위에서 움직이는 값이고 사전조건이
     * 실제로 그것을 건다. 보기만 하는 종류(`ui-value` · `transform` · `scene` · `audio`)는 안 본다 —
     * 그것들은 무엇이 보이나이지 걸어 둘 수 있는 값이 아니라, 꼬리만 맞으면 아무 관측이나 걸린다.
     */
    @Query(
        """
        SELECT DISTINCT ce.target
        FROM scene s
        JOIN capability c ON c.scene_id = s.id AND c.merged_into IS NULL
        JOIN capability_effect ce ON ce.capability_id = c.id AND ce.kind IN ('write', 'active-state')
        WHERE ce.target IS NOT NULL
          AND s.content_map_id IN (
              SELECT DISTINCT s2.content_map_id
              FROM test_case tc
              JOIN capability c2 ON c2.capability_key = tc.capability_key
              JOIN scene s2 ON s2.id = c2.scene_id
              WHERE tc.project_id = :projectId
          )
        """
    )
    fun findWrittenValues(projectId: Long): Flow<String>

    /**
     * **그 값이 어느 화면에서 움직이나**(ARTEL-635).
     *
     * 저작이 받는 전제는 서로 똑같이 생겼다 — `position == 0` 과 `StagePosition >= 1` 은 한 줄로는
     * 구별되지 않는다. 그런데 앞엣것은 방향키 한 번이고 뒤엣것은 **전투를 이겨야** 오른다.
     *
     * 그 차이를 지도는 안다(`TurnBattleScene` 의 `+1`). 안 보내고 있었을 뿐이고, 그래서 실측
     * (런 184)에서 저작이 스테이지를 안 깬 채로 지도를 활보하는 시나리오를 냈다 — 첫 스텝이
     * `>= 1` 을 요구하는데 그 값을 올리는 전투 진입은 **마지막 스텝**이었다. 순환이다.
     *
     * `+1` 같은 증감만 본다. 확정값(`0`)은 되돌리는 것이지 진행이 아니고, 그것까지 "여기서
     * 오른다"고 말하면 타이틀 화면이 모든 값의 출처가 된다.
     */
    @Query(
        """
        SELECT DISTINCT ce.target AS target, s.name AS scene
        FROM scene s
        JOIN capability c ON c.scene_id = s.id AND c.merged_into IS NULL
        JOIN capability_effect ce ON ce.capability_id = c.id AND ce.kind = 'write'
        WHERE ce.target IS NOT NULL
          AND ce.detail ~ '^[+-][0-9]'
          AND s.content_map_id IN (
              SELECT DISTINCT s2.content_map_id
              FROM test_case tc
              JOIN capability c2 ON c2.capability_key = tc.capability_key
              JOIN scene s2 ON s2.id = c2.scene_id
              WHERE tc.project_id = :projectId
          )
        """
    )
    fun findValueRaisers(projectId: Long): Flow<ValueRaiser>

    /**
     * **게임을 켜면 열리는 화면**(ARTEL-659). 이 프로젝트의 지도가 그렇게 적어 둔 씬이다.
     *
     * 씬 그래프는 순환이라 입구를 구조로는 알 수 없다 — 모든 씬이 서로 닿는다. 적재기가 빌드에서
     * 읽어 적어 두고(`scene.is_entry`), 저작은 그것만 읽는다.
     *
     * **지도 범위는 빌드 소속으로 좁힌다.** 케이스의 `capability_key` 로 되짚으면 안 된다 — 그 키는
     * 내용 해시라 같은 게임을 여러 번 적재한 지도가 전부 걸린다. 실측(로컬)에서 프로젝트 24 로
     * 되짚으니 지도 일곱 개가 나왔고, 같은 게임이라 내용이 같아 **틀린 줄 몰랐다.** 두 프로젝트가
     * 같은 게임을 등록하면 서로의 지도를 읽게 된다.
     *
     * 가장 최근 지도를 본다 — 경로 조회(`ScenarioPathService.contentMapIdOf`)와 같은 규칙이다.
     */
    @Query(
        """
        SELECT s.name FROM scene s
        JOIN content_map cm ON cm.id = s.content_map_id
        JOIN game_build b ON b.id = cm.game_build_id
        WHERE s.is_entry AND b.project_id = :projectId
        ORDER BY cm.id DESC
        LIMIT 1
        """
    )
    suspend fun findEntrySceneName(projectId: Long): String?

    /**
     * **게임을 켜면 값이 무엇으로 시작하나**(ARTEL-665).
     *
     * 입구 화면이 저장소에서 값을 읽을 때 **없으면 쓸 기본값**을 함께 적어 둔다:
     *
     * ```
     * PlayerPrefs.GetInt("StagePosition", -1)
     *                                     ↑ 저장 데이터가 없을 때의 값
     * ```
     *
     * 이것을 안 읽으면 흐름 계산이 **첫 상태를 모른 채** 출발한다. 그러면 어느 쪽이 진행인지도
     * 모르고, 실측(런 239)에서 지도 흐름이 진행도 5 → 4 → 3 → 2 로 **거꾸로** 놓였다 —
     * `>= 4` 는 5에서도 참이라 논리적으로 어긋나지 않지만, 실행하는 사람은 보스 앞에서 시작해
     * 뒤로 걸어 나온다.
     *
     * 기본값을 깔면 `fits` 가 알아서 막는다 — `-1` 에서 `>= 4` 는 안 맞으니 그 값을 올리는 자리를
     * 지나기 전에는 못 놓는다.
     *
     * 입구 화면의 것만 본다. 다른 화면에서 읽는 것은 그때까지 게임이 해 온 것이 반영된 값이라
     * "처음"이 아니다.
     */
    @Query(
        """
        SELECT DISTINCT regexp_replace(e.target, '^.*\.', '') AS name, e.detail AS detail
        FROM capability_effect e
        JOIN capability c ON c.id = e.capability_id AND c.merged_into IS NULL
        JOIN scene s ON s.id = c.scene_id AND s.is_entry
        JOIN content_map cm ON cm.id = s.content_map_id
        JOIN game_build b ON b.id = cm.game_build_id
        WHERE b.project_id = :projectId
          AND e.target IS NOT NULL
          AND e.detail ~ 'Get(Int|Float|String|Bool)\('
        """
    )
    fun findStartingValues(projectId: Long): Flow<StartingValue>

    /**
     * **이 값을 무엇이 어떤 조건에서 바꾸나**(ARTEL-646).
     *
     * [findValueRaisers] 는 화면 이름 하나만 답한다. 그것만으로는 `position == 0`(방향키 한 번)과
     * `StagePosition >= 1`(전투를 이겨야 함)이 저작에게 똑같이 보인다 — 실측(런 203)에서 저작이
     * 전투를 한 번도 안 끼운 채 스테이지를 훑는 시나리오를 냈다.
     *
     * 지도는 그 답을 통째로 안다. 같은 자리에 네 가지가 함께 적혀 있다:
     *
     * ```
     * StagePosition  TurnBattleScene  +1  못 시킴(not-a-step)  wave >= 전체 웨이브 수
     * position       Map_scene        +1  RightArrow           StagePosition >= 2 · position == 1
     * ```
     *
     * 두 번째 줄이 사용자가 말한 "천장과 커서"의 관계다 — **커서를 앞으로 미는 조작 자체가 천장을
     * 요구한다.** 그 관계는 지어낸 것이 아니라 조작의 조건에 적혀 있다.
     *
     * `kind = 'write'` 만 본다. `saved` 는 값을 어딘가에 적어 두는 것이지 바꾸는 것이 아니고,
     * 표시(`ui-value`)나 좌표(`transform`)는 이 질문의 답이 아니다.
     *
     * 증감만 보지 않는다 — [findValueRaisers] 와 다른 점이다. `0` 으로 되돌리는 것도 "무엇이 이 값을
     * 만드나"의 답이고, 되돌린다는 사실 자체가 저작이 알아야 할 것이다.
     *
     * 지도를 먼저 하나로 고르고 나서 훑는다. `test_case` 를 바깥에 두면 케이스 한 줄마다 효과
     * 전체가 딸려 와 행이 곱으로 분다.
     */
    @Query(
        """
        SELECT DISTINCT ce.target AS target,
               s.name AS scene,
               ce.detail AS detail,
               c.actionability AS actionability,
               coalesce(c.input_key, c.control_path, c.control_label) AS operation,
               c.interaction AS interaction,
               e.condition_tree AS condition_tree
        FROM scene s
        JOIN capability c ON c.scene_id = s.id AND c.merged_into IS NULL
        JOIN capability_effect ce ON ce.capability_id = c.id AND ce.kind = 'write'
        LEFT JOIN capability_evidence e ON e.capability_id = c.id
        WHERE ce.target IS NOT NULL
          AND s.content_map_id IN (
              SELECT DISTINCT s2.content_map_id
              FROM test_case tc
              JOIN capability c2 ON c2.capability_key = tc.capability_key
              JOIN scene s2 ON s2.id = c2.scene_id
              WHERE tc.project_id = :projectId
          )
        ORDER BY 1, 2
        """
    )
    fun findValueMoves(projectId: Long): Flow<ValueMoveRow>

    /**
     * **이 기능이 이미 케이스로 있나**(ARTEL-674).
     *
     * 빈 구간을 메울 때 쓴다. 메우는 조작은 대개 이 프로젝트가 이미 케이스로 들고 있는 것이라
     * (실측: 끼운 31개 중 24개), 이름 없는 걸음으로 다시 쓰면 커버리지는 안 오르고 스텝만 늘며
     * 그 걸음의 전제와 효과도 잃는다.
     *
     * 케이스 쪽을 프로젝트로 좁히므로, 같은 게임을 여러 번 적재한 지도의 기능이 함께 걸려도
     * 답은 이 프로젝트의 케이스다.
     */
    @Query(
        """
        SELECT c.id AS capability_id, tc.id AS test_case_id
        FROM test_case tc
        JOIN capability c ON c.capability_key = tc.capability_key AND c.merged_into IS NULL
        WHERE tc.project_id = :projectId AND tc.capability_key IS NOT NULL
        """
    )
    fun findCaseIdByCapability(projectId: Long): Flow<CapabilityCase>
}
