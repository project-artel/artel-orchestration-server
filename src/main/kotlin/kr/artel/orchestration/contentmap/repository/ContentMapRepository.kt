package kr.artel.orchestration.contentmap.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.contentmap.dto.ContentMapCallEdge
import kr.artel.orchestration.contentmap.dto.ContentMapCapabilityRow
import kr.artel.orchestration.contentmap.dto.ContentMapObservationRow
import kr.artel.orchestration.contentmap.dto.ContentMapScreenElement
import kr.artel.orchestration.contentmap.dto.CapabilityOwner
import kr.artel.orchestration.contentmap.dto.MethodArgument
import kr.artel.orchestration.contentmap.dto.SpecGapRow
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * content_map 루트 조회.
 *
 * **키가 게임 빌드 하나**인 것이 이 도메인의 전제다(ARTEL-642, `uk_content_map_build`). 근거가
 * 먼저 오든 QA 런이 먼저 돌든 같은 행에 쌓이므로, "어느 지도를 볼까" 를 고르는 자리가 없다.
 */
interface ContentMapRepository : CoroutineCrudRepository<ContentMapEntity, Long> {

    /** 유일 제약이 하나를 보장한다. 없으면 아직 이 빌드에 대해 알아낸 것이 없다는 뜻이다. */
    suspend fun findByGameBuildId(gameBuildId: Long): ContentMapEntity?

    /**
     * 이 빌드의 지도를 관측으로 세운다 (ARTEL-689). 이미 있으면 null 이다.
     *
     * ARTEL-642 가 `content_map` 의 NOT NULL 셋을 풀고 `rooted_by` 에 `observation` 을 넣어 "근거
     * 문서 없이도 지도가 선다" 를 열었는데, 그 값을 쓰는 코드가 없었다. `content_map` 행이 생기는
     * 자리가 `EvidenceDocumentService.upsertContentMap` 하나뿐이라, 문서가 한 번도 안 올라온
     * 빌드에는 지도가 없다.
     *
     * 헤더 넷(`schema_version` · `capture` · `evidence_digest` · `evidence_promises`)을 채우지
     * 않는다. 넷 다 문서가 말해 주는 것이라 관측은 말할 자격이 없고, 더미값을 넣으면 진짜 헤더와
     * 같은 칸에 앉는다(V63 4절).
     *
     * `uk_content_map_build` 가 빌드당 한 행을 강제한다. 경합에 진 쪽은 null 을 받고 이긴 쪽이
     * 쓴 행을 다시 읽어 이어간다.
     */
    @Query(
        """
        INSERT INTO content_map (game_build_id, rooted_by)
        VALUES (:gameBuildId, 'observation')
        ON CONFLICT (game_build_id) DO NOTHING
        RETURNING id
        """
    )
    suspend fun rootByObservation(gameBuildId: Long): Long?

    /**
     * **TC 생성기가 읽는 유일한 창구**(`v_content_map_capability`).
     *
     * 남기는 잣대가 둘이고 둘 다 뷰가 내주는 칸이다.
     *
     * **① 시킬 수 있거나 볼 수 있어야 한다.** 둘 다 아니면 실행하는 사람에게 시킬 말이 없다.
     * 앞서는 `status <> 'not-a-step'` 하나만 봤는데, `status` 는 세 축을 하나로 뭉갠 요약이라
     * `not-a-step` 이 맨 앞에서 이긴다 — *"누를 수는 없지만 볼 수는 있는"* 행이 통째로 잘렸다.
     * 실측(word-venture, 2026-09-01)에서 그 자리가 142 행이고, `Player.HpText` 가 줄고
     * `GameOverScene` 으로 가는 것이 거기 있었다.
     *
     * **② 누를 것이 없는 행만 `record_kind = 'candidate'` 를 요구한다.**
     *
     * SDK 는 셋을 다 만족할 때만 `candidate` 를 준다 — 결과가 있고(`outcomes > 0`), 조건을 끝까지
     * 읽었고(`composable`), 싱글턴 배관이 아니다(`!plumbing`). 하나라도 아니면 `flow` 다.
     *
     * **조작에는 이 잣대를 걸면 안 된다.** `flow` 의 첫 조건이 *"제 효과가 있나"* 인데, 조작이
     * 효과를 안 들고 있는 것은 결함이 아니라 이 게임 구조의 정상이다 — 코루틴·상태 머신에서
     * 입력을 받는 갈래와 결과를 내는 갈래가 다른 행으로 갈리고, 그 둘을 잇는 것이
     * `MapTestCaseSiblings` 다. 걸어 봤더니(2026-09-01) 진짜 조작 13 행이 잘렸다:
     * `Return` 키로 전투에 들어가는 것, `any` 키로 대사를 넘기는 것, 게임을 끝내는 버튼.
     * SDK 주석도 *"제 효과가 없는 호출이 곧 효과로 가는 경로를 따라가는 방법이므로 남겨 둔다"*
     * 고 적었다 — 버리라는 말이 아니다.
     *
     * 누를 것이 없는 행은 다르다. 거기서는 `flow` 가 곧 *"조건을 못 읽었거나 배관"* 이고,
     * 볼 시점을 못 적으면 관측 케이스가 성립하지 않는다.
     *
     * **③ 누를 것이 없는 행은 `trigger_kind = 'lifecycle'` 이어야 한다.**
     *
     * `lifecycle` 은 Unity 가 부르는 콜백이고(`Start`·`Update`·`OnTriggerEnter2D`·`OnMouseEnter`),
     * `unity-event` 는 게임이 인스펙터로 연결한 자기 메서드다(`TakeHit`·`Attack`·`CardAlignment`).
     * 앞엣것은 사람이 그 순간을 만들 수 있다 — 화면을 열고, 머무르고, 부딪히고, 마우스를 올린다.
     * 뒤엣것은 게임이 알아서 부르는 자리라 실행하는 사람이 시킬 방법이 없다.
     *
     * 실측(2026-09-01): 열어 봤더니 `CardMouseUp` 과 `CardAlignment` 이 `this.transform` 세 줄을
     * 똑같이 냈다 — 볼 대상 이름도 없고 중복이다.
     *
     * 실측: 419 행 → **153 행**. 버려지는 것은 시킬 수도 볼 수도 없는 226 행과, 누를 것이
     * 없으면서 `flow` 인 40 행이다.
     *
     * agent 쪽은 [findAllCapabilityRows] 다 — 그쪽은 전부를 원한다(ARTEL-680).
     *
     * 접힌(`merged_into`) 행은 뷰가 계속 거른다. 효과는 여기 없다 — 행이 곱해지므로
     * [CapabilityEffectRepository] 로 따로 읽는다.
     *
     * `ORDER BY` 를 씬 이름과 기능 id 로 고정하는 것은 취향이 아니다. 이 목록은 agent 프롬프트에
     * 실려 프롬프트 캐시를 타므로, 줄 순서가 조회마다 흔들리면 캐시가 통째로 깨진다.
     */
    @Query(
        """
        SELECT * FROM v_content_map_capability
        WHERE content_map_id = :contentMapId
          AND (
                actionability <> 'not-a-step'
             OR (
                    observability = 'observable'
                AND record_kind = 'candidate'
                AND trigger_kind = 'lifecycle'
                )
          )
        ORDER BY scene_name ASC, capability_id ASC
        """
    )
    fun findTestCaseRows(contentMapId: Long): Flow<ContentMapCapabilityRow>

    /**
     * **조회 화면의 "조작 단계" 목록**(`ContentMapViewService.stepsByScene`).
     *
     * 이름 그대로 *누를 수 있는 것*만 낸다. [findTestCaseRows] 와 일부러 갈린다 — 저쪽은
     * "무엇이 TC 가 되나"를 묻고 여기는 "이 씬에서 무엇을 할 수 있나"를 묻는다. 화면은
     * `steps.size == total - notAStep` 등식을 세워 두었으므로 이 필터가 흔들리면 수치가 어긋난다.
     */
    @Query(
        """
        SELECT * FROM v_content_map_capability
        WHERE content_map_id = :contentMapId AND status <> 'not-a-step'
        ORDER BY scene_name ASC, capability_id ASC
        """
    )
    fun findStepCapabilityRows(contentMapId: Long): Flow<ContentMapCapabilityRow>

    /** [findStepCapabilityRows] 와 같은 필터를 씬 하나로 좁힌 것. 둘의 필터는 함께 손댄다. */
    @Query(
        """
        SELECT * FROM v_content_map_capability
        WHERE content_map_id = :contentMapId AND scene_name = :sceneName AND status <> 'not-a-step'
        ORDER BY capability_id ASC
        """
    )
    fun findStepCapabilityRowsByScene(contentMapId: Long, sceneName: String): Flow<ContentMapCapabilityRow>

    /**
     * QA agent 가 런 시작에 받는 것. **이 빌드의 capability 전부다**(ARTEL-680).
     *
     * `not-a-step` 418 행을 [findStepCapabilityRows] 가 거르는 이유는 TC 생성기의 이유이지 agent 의
     * 이유가 아니다. 그 418 행은 `interaction = 'none'` — "적을 처치하면 보상을 받는다" 처럼 누르는
     * 것이 아니라 일어나는 일이고, 일어났는지는 `screen` 을 본 agent 가 안다(V71). 목록에서 빼면
     * agent 는 적을 대상을 모른 채 tool 만 들고 있게 된다.
     *
     * 쏟아 놓기만 하면 안 된다는 것이 [findStepCapabilityRows] 를 남긴 이유다.
     * `SceneContextService` 가 이 결과를 `status` 로 갈라 두 목록으로 낸다.
     */
    @Query(
        """
        SELECT * FROM v_content_map_capability
        WHERE content_map_id = :contentMapId
        ORDER BY scene_name ASC, capability_id ASC
        """
    )
    fun findAllCapabilityRows(contentMapId: Long): Flow<ContentMapCapabilityRow>

    /**
     * 기능마다 **그 코드가 붙어 있는 타입**([CapabilityOwner]).
     *
     * 효과의 대상이 `Component.` 로 시작하는 자리를 되돌리는 데 쓴다. 접힌 행도 낸다 — 효과를
     * 빌려 오는 자리(`borrowed`)의 주인이 접힌 행일 수 있고, 그때도 이름은 그 문서가 말한 사실이다.
     */
    @Query(
        """
        SELECT ce.capability_id, ce.method_id
        FROM capability_evidence ce
        JOIN capability c ON c.id = ce.capability_id
        JOIN scene s ON s.id = c.scene_id
        WHERE s.content_map_id = :contentMapId AND ce.method_id IS NOT NULL
        """
    )
    fun findCapabilityOwners(contentMapId: Long): Flow<CapabilityOwner>

    /**
     * 씬마다 **그 씬이 이름으로 아는 오브젝트들**.
     *
     * `GameObject.Find("이름")` 을 맞춰 보는 데 쓴다([MapTestCaseTargets.ofScene]). 인스펙터가
     * 물어 둔 참조와 조작이 겨누는 자리, 둘 다 씬이 스스로 말한 이름이다.
     */
    @Query(
        """
        SELECT scene_name, path FROM (
            SELECT s.name AS scene_name, r.target_name AS path
            FROM scene_object_ref r JOIN scene s ON s.id = r.scene_id
            WHERE s.content_map_id = :contentMapId
            UNION
            SELECT s.name AS scene_name, c.control_path AS path
            FROM capability c JOIN scene s ON s.id = c.scene_id
            WHERE c.content_map_id = :contentMapId AND c.merged_into IS NULL
              AND c.control_path IS NOT NULL
        ) named
        ORDER BY scene_name ASC, path ASC
        """
    )
    fun findSceneObjectNames(contentMapId: Long): Flow<ContentMapScreenElement>

    /**
     * **누를 것은 없고 볼 것은 있는 기능**(ARTEL-681). 위 창구가 `not-a-step` 을 거르므로 따로 낸다.
     *
     * 게임이 스스로 하는 일이다 — 화면을 열면 무엇이 보이나, 값이 이러하면 무엇이 보이나. 지금까지
     * 케이스가 하나도 없었고(지도 31에서 137개 중 0건), 대신 그 결과가 엉뚱하게 조작 케이스의
     * 기대결과로 흘러들어갔다(ARTEL-680).
     *
     * `call_path` 의 뿌리를 함께 낸다. **유니티가 정한 이름이라** 개발자가 무엇을 어떻게 부르든
     * 흔들리지 않는다 — `Start` 면 화면을 열 때, `Update` 면 머무르는 동안이다.
     */
    @Query(
        """
        SELECT cm.id AS content_map_id, cm.capture,
               s.id AS scene_id, s.name AS scene_name, s.summary AS scene_summary,
               c.id AS capability_id, c.capability_key, c.origin, c.verification,
               c.scene_presence, c.status,
               c.actionability, c.observability, c.applicability, c.summary, c.given_text,
               c.control_selector, c.control_path, c.control_label, c.interaction,
               c.input_key, c.input_phase, c.repeat_until_done,
               c.hint_action_method, c.hint_action_params,
               ce.entry_id, ce.branch_offset, ce.record_kind, ce.trigger_kind,
               ce.analysis_confidence, ce.condition_tree, ce.gaps,
               substring(ce.call_path->>0 from '::([A-Za-z0-9_]+)') AS trigger_root
        FROM capability c
        JOIN scene s ON s.id = c.scene_id
        JOIN content_map cm ON cm.id = s.content_map_id
        JOIN capability_evidence ce ON ce.capability_id = c.id
        WHERE cm.id = :contentMapId
          AND c.merged_into IS NULL
          AND c.actionability = 'not-a-step'
          AND c.observability = 'observable'
        ORDER BY s.name ASC, c.id ASC
        """
    )
    fun findObservationRows(contentMapId: Long): Flow<ContentMapObservationRow>

    /**
     * **화면에 붙어 있는 UI 요소**(ARTEL-683). 있는지 확인하는 케이스가 여기서 나온다.
     *
     * 효과에서만 케이스를 만들면 *"그 버튼이 보이는가"* 가 통째로 빠진다 — 그냥 있는 것은 바뀌는
     * 것이 아니라 효과가 없기 때문이다. 구버전(specs_v2)은 이것을 `control_check` 로 따로 냈고,
     * 신버전에는 없었다.
     *
     * 두 곳을 합친다. 코드가 필드로 들고 있는 것(`scene_object_ref`)과 클릭 핸들러만 붙은
     * 것(`capability.control_path`)이 서로를 못 덮는다 — 실측(지도 31)에서 앞은 `Canvas/Stage` 를,
     * 뒤는 `Canvas/MapSceneButton` 을 갖고 있다.
     *
     * `Canvas/` 로 좁힌다. 좁히지 않으면 코드가 참조하는 오브젝트 전부가 걸려 `TurnBattleScene`
     * 하나가 167건이 된다 — 카드·적·프리팹처럼 화면에 붙은 UI 가 아닌 것들이다.
     */
    @Query(
        """
        SELECT scene_name, path FROM (
            SELECT s.name AS scene_name, r.target_name AS path
            FROM scene_object_ref r JOIN scene s ON s.id = r.scene_id
            WHERE s.content_map_id = :contentMapId AND r.target_name LIKE 'Canvas/%'
            UNION
            SELECT s.name AS scene_name, c.control_path AS path
            FROM capability c JOIN scene s ON s.id = c.scene_id
            WHERE c.content_map_id = :contentMapId AND c.merged_into IS NULL
              AND c.control_path LIKE 'Canvas/%'
        ) ui
        ORDER BY scene_name ASC, path ASC
        """
    )
    fun findScreenElements(contentMapId: Long): Flow<ContentMapScreenElement>

    /**
     * **호출로 이어진 결과 갈래**(ARTEL-554).
     *
     * 조작 갈래가 결과를 안 들고 있을 때 그 결과가 어디 있는지 찾는다. 코루틴·상태 머신에서는
     * 입력을 받는 갈래와 결과를 내는 갈래가 다른 행이고, **공통 호출자**만이 둘을 잇는다 —
     * 실측(word-venture)에서 `StoryController.StoryTelling()` 이 `IsAdvanceKeyDown` 과
     * `LoadMapScene` · `UpdateChatStream` 을 다 부른다.
     *
     * `entry_id` 공유로 이으면 안 된다. 진입점은 갈래의 출처이지 인과가 아니라, 그렇게 이었더니
     * "`RightArrow` 를 누르면 배경이 바뀐다"는 거짓 케이스가 나왔다(배경은 씬 진입 때 정해진다).
     *
     * 한 줄이 (**부른 메서드**, 불린 기능)이다. 기능 행이 아니라 메서드로 묶는 것이 요점이다 —
     * 코루틴 하나가 갈래 16개로 쪼개지고 **각 갈래가 호출을 하나씩만 든다.** 행 단위로 보면
     * `IsAdvanceKeyDown` 을 부른 갈래가 `LoadMapScene` 은 안 부르는 것으로 보인다. 메서드로 묶어야
     * 그 코루틴이 부르는 13개가 한자리에 모인다.
     *
     * 걸러 내지 않고 전부 낸다 — 조작 갈래는 효과가 없어서, 효과로 거르면 **그 갈래를 부른 쪽을
     * 찾을 수 없다.** 무엇을 이을지는 부르는 쪽이 정한다.
     *
     * 부른 쪽의 조건도 함께 내는 것은 그 호출이 조건 아래에서만 일어나기 때문이다 — 이은 케이스의
     * 사전조건이 그것을 함께 들어야 한다.
     */
    @Query(
        """
        SELECT caller.method_id AS caller_method_id,
               caller.condition_tree AS caller_condition,
               callee_cap.id AS capability_id,
               callee.condition_tree AS condition_tree
        FROM capability_evidence caller
        JOIN capability caller_cap ON caller_cap.id = caller.capability_id
        JOIN scene caller_scene ON caller_scene.id = caller_cap.scene_id
        CROSS JOIN LATERAL jsonb_array_elements(caller.calls) AS call
        JOIN capability_evidence callee ON callee.method_id = call->>'targetId'
        JOIN capability callee_cap ON callee_cap.id = callee.capability_id
        JOIN scene callee_scene ON callee_scene.id = callee_cap.scene_id
        WHERE caller_scene.content_map_id = :contentMapId
          AND callee_scene.content_map_id = :contentMapId
          AND caller_cap.merged_into IS NULL
          AND callee_cap.merged_into IS NULL
          AND caller.method_id IS NOT NULL
          AND callee.method_id IS DISTINCT FROM caller.method_id
        """
    )
    fun findCallEdges(contentMapId: Long): Flow<ContentMapCallEdge>

    /**
     * 메서드마다 **호출자가 넘기는 인자**(ARTEL-602). 값이 하나로 정해지는 것만 낸다.
     *
     * 사전조건이 매개변수 이름을 그대로 적으면 실행하는 사람이 그 값을 찾을 수 없다. 문서의
     * `records[].calls[].args` 가 그 답을 들고 있다 — `Update → ShowBattle(MapMove.StagePosition)`.
     *
     * `args` 는 `"a, b, c"` 처럼 한 문자열이라 쉼표로 끊어 자리를 센다. 괄호 안의 쉼표까지 끊기는
     * 자리가 있을 수 있어서, **끊은 조각 수가 호출마다 다르면** 그 메서드는 통째로 뺀다 — 자리를
     * 잘못 세면 엉뚱한 값을 사전조건에 적는다.
     *
     * `HAVING count(DISTINCT ...) = 1` 이 계약이다. 부르는 곳마다 값이 다르면 하나를 골라 적는 것이
     * 곧 거짓이라, 모른다고 두는 편이 낫다.
     */
    @Query(
        """
        SELECT callee.capability_id AS capability_id, parts.position, parts.value
        FROM (
            SELECT target_id, position, min(value) AS value
            FROM (
                SELECT call->>'targetId' AS target_id,
                       arg.ordinality - 1 AS position,
                       btrim(arg.value) AS value,
                       count(*) OVER (PARTITION BY call->>'targetId', caller.capability_id, call) AS arity
                FROM capability_evidence caller
                JOIN capability caller_cap ON caller_cap.id = caller.capability_id
                JOIN scene caller_scene ON caller_scene.id = caller_cap.scene_id
                CROSS JOIN LATERAL jsonb_array_elements(caller.calls) AS call
                CROSS JOIN LATERAL string_to_table(call->>'args', ',') WITH ORDINALITY AS arg(value, ordinality)
                WHERE caller_scene.content_map_id = :contentMapId
                  AND caller_cap.merged_into IS NULL
                  AND call->>'targetId' IS NOT NULL
                  AND call->>'args' IS NOT NULL
                  AND btrim(arg.value) <> ''
            ) raw
            GROUP BY target_id, position
            HAVING count(DISTINCT value) = 1 AND count(DISTINCT arity) = 1
        ) parts
        JOIN capability_evidence callee ON callee.method_id = parts.target_id
        JOIN capability callee_cap ON callee_cap.id = callee.capability_id
        JOIN scene callee_scene ON callee_scene.id = callee_cap.scene_id
        WHERE callee_scene.content_map_id = :contentMapId AND callee_cap.merged_into IS NULL
        """
    )
    fun findSettledArguments(contentMapId: Long): Flow<MethodArgument>

    /**
     * **되돌아가는 갈래의 조건들**(ARTEL-613). 이 가드를 뒤집으면 "다 돌고 나온 자리"다.
     *
     * 루프를 도는 것은 대개 코루틴이라 조작이 없다. 그래서 이 사실은 기능이 아니라 갈래에 앉아
     * 있고(`capability_evidence.loops_back_to`), 조작은 그 갈래를 부르는 쪽에 있다 — 둘을 잇는
     * 것은 `calls` 다.
     *
     * 접힌 행도 낸다. 루프를 도는 갈래가 대표로 뽑히지 않는 일이 흔하고, 뒤집을 가드는 그래도
     * 그 문서가 말한 사실이다.
     */
    @Query(
        """
        SELECT ce.condition_tree::text
        FROM capability_evidence ce
        JOIN capability c ON c.id = ce.capability_id
        JOIN scene s ON s.id = c.scene_id
        WHERE s.content_map_id = :contentMapId
          AND ce.loops_back_to IS NOT NULL
          AND ce.condition_tree IS NOT NULL
        """
    )
    fun findLoopingConditions(contentMapId: Long): Flow<String>

    /**
     * 이 지도가 어느 프로젝트의 것인가(ARTEL-578).
     *
     * 지도는 게임 빌드에 매달려 있고 프로젝트는 그 위에 있다. 케이스를 앉히려면 `project_id` 가
     * 있어야 하는데(`test_case` 의 소유자가 프로젝트다) 지도 행은 그것을 직접 안 든다.
     */
    @Query(
        """
        SELECT game_build.project_id
        FROM content_map
        JOIN game_build ON game_build.id = content_map.game_build_id
        WHERE content_map.id = :contentMapId
        """
    )
    suspend fun findProjectId(contentMapId: Long): Long?

    /**
     * 명세가 못 된 이유(`v_spec_gap`). 사유가 없는(세 칸이 다 찬) 행은 뺀다.
     *
     * 이 분포가 다음 스프린트에 무엇을 고칠지 정한다.
     */
    @Query(
        """
        SELECT * FROM v_spec_gap
        WHERE content_map_id = :contentMapId AND reason IS NOT NULL
        ORDER BY capability_id ASC
        """
    )
    fun findSpecGaps(contentMapId: Long): Flow<SpecGapRow>
}
