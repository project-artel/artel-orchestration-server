--------------------------------------------------------------------------------
-- V46. 스폰 출처를 적고, 그것이 조준 대상으로 새지 못하게 한다
--------------------------------------------------------------------------------
-- V40 이 세운 스키마 위에 V42 · V43 · V44 · V45 와 같은 방식으로 얹는다. V40 본문을 고치지 않는
-- 이유는 번호가 한 번 붙으면 그 파일의 checksum 이 계약이 되기 때문이다.
--
-- 프리팹 위에만 사는 타입은 `objects[].components[].calls` 에 나타나지 않아 배선으로는
-- 구조적으로 0건이다. 씬에 귀속시키는 유일한 길이 "무엇이 이것을 만드는가"인데, 그 사실을 적을
-- 자리가 없어 씬만 남고 입구가 사라진다. 실측(`wv-editor-latest.json`)에서 `unplaced` 10타입 ·
-- evidence 111건이 여기 걸리고, 그 무게가 전투 씬 대부분이다.
--
-- 담는 것:
--   1. capability.spawned_by_field · spawned_by_scene_path — 만드는 쪽의 주소
--   2. CHECK 셋 — 경로는 필드를 요구하고, 스폰 출처는 근거 출신만 갖고, 그 주소는 조준 대상으로
--      새지 못한다
--   3. v_spec_gap — 스폰 행이 when-missing 을 덮지 않게 한다
--
-- capability_evidence 가 아니라 capability 에 두는 이유: 이 값이 지켜야 하는 불변식이 전부
-- capability 의 컬럼(control_path · control_selector · interaction · origin)과의 관계다. 서브테이블에
-- 두면 CHECK 가 테이블을 넘지 못해 이 저장소에 전례 없는 트리거를 들이거나 적재기 테스트에
-- 맡겨야 한다. "무엇으로 씬에 붙었나"는 근거 문서의 문장이기 이전에 이 기능 행의 출신이다.

--------------------------------------------------------------------------------
-- 1. 만드는 쪽의 주소
--------------------------------------------------------------------------------
-- `unplaced[type].createdBy` 항목 원문이다. 예: `Cards.CardManager.cardPrefab`.
-- ScriptableObject 를 거쳐 두 홉까지 추적된 결과가 이미 이 한 문자열에 담겨 있다.
--
-- 폭은 entry_id 와 같은 1024 다. 둘 다 난독화 전 풀네임을 담는다.
--
-- 스칼라인 것이 규칙을 정한다: `createdBy` 는 목록이지만 이 칸은 **씬 귀속을 실제로 결정한
-- 하나**만 담는다. 한 씬에 후보가 둘 이상이면 아무것도 적지 않고 근거의 gaps 에 사유를 남긴다 —
-- 첫 항목을 조용히 집으면 "누가 만드는지 안다"와 "여럿 중 하나를 골랐다"가 구분되지 않는다.
ALTER TABLE capability
    ADD COLUMN IF NOT EXISTS spawned_by_field VARCHAR(1024);

-- `objects[].components[].refs[].carries` 가 준 씬 경로. 예: `CardSystem/CardManager`.
-- 폭은 control_path 와 같은 512 — 같은 종류의 씬 경로 문자열이다.
--
-- **NULL 이 정상이다.** carries 는 실측에서 editor 3건 / devbuild 28건뿐이고, 나머지는
-- createdBy 의 오너 타입을 다시 배치해 씬 이름만 얻는다. 비었다고 gaps 에 사유를 남기지 않는다 —
-- 되짚기 축(method_id · call_path)과 달리 이 값은 근거가 줄 때만 있는 덤이다.
--
-- 그럼에도 따로 담는 이유: 이 값이 있으면 귀속이 **정확**(문서가 경로를 줬다)하고, 없으면
-- **유도**(오너 타입의 배치에서 씬만 얻었다)다. 그 정밀도는 귀속 단계의 capability_proof.resolution
-- 으로 들어가고, analysis_confidence 는 V44 가 정한 대로 사슬 전체의 최솟값에서 따라 나온다 —
-- 이 컬럼 하나로 직접 정하지 않는다.
ALTER TABLE capability
    ADD COLUMN IF NOT EXISTS spawned_by_scene_path VARCHAR(512);

--------------------------------------------------------------------------------
-- 2. 주소가 조준 대상으로 새지 않는다
--------------------------------------------------------------------------------
-- 경로는 필드를 설명하는 값이라 혼자 서지 못한다. 경로만 남으면 "이 오브젝트가 만든다"까지만
-- 있고 무엇으로 만드는지가 없어, 재적재 때 같은 사실인지 확인할 방법이 없다.
ALTER TABLE capability
    DROP CONSTRAINT IF EXISTS ck_capability_spawn_path_needs_field;
ALTER TABLE capability
    ADD CONSTRAINT ck_capability_spawn_path_needs_field
        CHECK (spawned_by_scene_path IS NULL OR spawned_by_field IS NOT NULL);

-- 근거 문서만이 스폰 출처를 말할 수 있다. QA 가 관측으로 배운 기능에 "무엇이 만든다"를 적으면
-- 추측이 근거와 같은 칸에 들어가고, 축이 둘이라는 전제가 무너진다.
ALTER TABLE capability
    DROP CONSTRAINT IF EXISTS ck_capability_spawn_needs_evidence;
ALTER TABLE capability
    ADD CONSTRAINT ck_capability_spawn_needs_evidence
        CHECK (spawned_by_field IS NULL OR origin = 'evidence');

-- 이 마이그레이션의 본체다.
--
-- 프리팹에는 씬 경로가 없다. 그것을 쥔 오브젝트의 경로를 control_path 나 control_selector 로
-- 내주면 TC 는 **만드는 쪽을 눌러 만들어지는 쪽을 확인했다**고 말한다 — 카드 매니저를 눌러
-- 카드가 뒤집혔다고 적는 명세다. 조작이 아닌데 조작인 척하는 이 한 줄이 근거 없는 명세 중
-- 가장 비싸다.
--
-- interaction='none' 을 함께 묶는 이유: 조준 대상만 비우고 조작 종류를 남겨 두면 실행기가
-- 누를 자리 없이 누르라는 스텝을 받는다. control_label 도 같이 묶는다 — 프롬프트 재료로 쓰이는
-- 글자라, 경로만 비우고 이름을 남기면 "만드는 쪽을 눌러라"가 문장으로 되살아난다.
--
-- actionability 를 묶는 이유는 따로 있다. 기본값이 'runnable' 이라, 축을 명시하지 않고 넣은
-- 스폰 행은 status 가 'needs-probe' 로 유도돼 v_content_map_capability 의
-- `status <> 'not-a-step'` 필터를 **통과한다.** 조작이 없는 행이 TC 생성기의 유일한 창구에
-- 나타나는 것이라, 이 한 축만은 판정이 아니라 정의다 — 만들어지는 쪽은 스텝이 될 수 없다.
-- 나머지 두 축(관측 · 적용)은 묶지 않는다. 그쪽은 나중에 ARTEL-452 · ARTEL-461 이 옮긴다.
ALTER TABLE capability
    DROP CONSTRAINT IF EXISTS ck_capability_spawn_has_no_control;
ALTER TABLE capability
    ADD CONSTRAINT ck_capability_spawn_has_no_control
        CHECK (
            spawned_by_field IS NULL
                OR (
                    control_path IS NULL
                        AND control_selector IS NULL
                        AND control_label IS NULL
                        AND interaction = 'none'
                        AND input_phase IS NULL
                        AND actionability = 'not-a-step'
                )
        );

--------------------------------------------------------------------------------
-- 3. 스폰 행이 when-missing 을 덮지 않는다
--------------------------------------------------------------------------------
-- v_spec_gap 은 "다음에 무엇을 고칠까"에 답하는 표다. when-missing 은 그 중 "조작을 갖지 못한
-- 것"을 세는 칸인데, 스폰 행은 조작이 **없어야 맞는** 행이라 그대로 두면 111건이 이 칸을 채워
-- 실제 결함을 덮는다.
--
-- 그래서 when-missing 에서만 뺀다. 맨 앞에 새 사유를 세우지 않는 것은 의도다 — 빼고 나면 스폰
-- 행도 나머지 분기를 그대로 지나가고, 조건이 흐린 행은 given-incomplete 로, 관측 가능 효과가
-- 없는 행은 then-missing 으로 각자 걸린다. 뒤쪽이 이 행들을 담은 이유에 가깝다: 적이 공격할 때
-- 무엇이 달라지는지를 판독으로 확인할 근거가 지금 명세에 아예 없다.
--
-- 아래는 V45 정의에 그 한 줄만 더한 것이다. 사유 어휘도 분기 순서도 그대로고, 왜 그 순서인지를
-- 함께 옮긴다 — 가장 새 정의만 읽어도 이유가 서게 하려는 것이다.
--
-- 이것은 QA 결함이 아니라 개발 우선순위 신호다. then-missing 이 많으면 수집기(SDK)를 고칠
-- 차례이고, given-subject-unknown 이 많으면 조건 분석기의 주어 추적이 약한 것이다.
-- agent 가 메울 수 있는 것이 아니다 — 근거에 없는 것을 메우면 그럴듯한 거짓말이 된다.
--
-- 사유가 여럿 성립하면 먼저 걸리는 것 하나만 나온다. 순서가 이런 이유:
--
--   when-missing 이 맨 앞     조작이 없으면 given 과 then 을 아무리 알아도 명세가 아니다
--   given-* 가 then-* 보다 앞  then 이 비었다는 것은 status='needs-probe' 로도 알 수 있지만,
--                             given 이 왜 불완전한지는 여기 말고 나오는 데가 없다
--
-- 그 순서의 대가: 조건이 ambiguous 이면서 관측 가능 효과도 없는 행은 given-incomplete 로만 잡혀
-- then-missing 집계가 그만큼 적게 나온다. 두 사유를 다 세야 하면 이 뷰가 아니라 사유별
-- EXISTS 를 각각 세는 질의를 따로 써야 한다.
--
-- not-a-step 을 거르지 않는 것도 의도다. 무엇이 조작을 갖지 못했는지가 이 표가 답해야 할
-- 질문이다. 대신 status 를 함께 내서, TC 생성기가 실제로 받는 행(v_content_map_capability 가
-- 내주는 것)만 세고 싶은 쪽이 걸러 쓸 수 있게 한다.
DROP VIEW IF EXISTS v_spec_gap;
CREATE VIEW v_spec_gap AS
SELECT
    s.content_map_id,
    c.scene_id,
    c.id AS capability_id,
    c.status,
    CASE
        -- 적재기 결함이다. 게임의 근거가 부족한 것이 아니라 우리가 근거를 잃은 것이라,
        -- then-missing 으로 뭉뚱그리면 SDK 를 고치러 가서 헛짚는다.
        WHEN c.origin = 'evidence' AND ce.capability_id IS NULL THEN 'evidence-missing'
        -- 스폰 행만 여기서 빠진다. 나머지는 V45 와 같다.
        WHEN c.interaction = 'none' AND c.spawned_by_field IS NULL THEN 'when-missing'
        WHEN ce.gaps @> '["subject-null"]'::jsonb THEN 'given-subject-unknown'
        -- ambiguous 는 "후보가 여럿", unresolved 는 "못 풀었다". 둘 다 조건을 단정할 수 없다는
        -- 뜻이라 같은 사유로 낸다. 어느 단계에서 그렇게 됐는지는 capability_proof 가 답한다.
        WHEN ce.analysis_confidence IN ('ambiguous', 'unresolved')
          OR ce.gaps @> '["callee-condition-not-composed"]'::jsonb THEN 'given-incomplete'
        WHEN ce.gaps @> '["unread-condition"]'::jsonb THEN 'given-unread'
        WHEN NOT EXISTS (
            SELECT 1 FROM capability_effect e
            WHERE e.capability_id = c.id
              AND e.category IN ('observable', 'availability')
        ) THEN 'then-missing'
        WHEN EXISTS (
            SELECT 1 FROM capability_effect e
            WHERE e.capability_id = c.id
              AND e.category = 'observable'
              AND e.detail IS NULL
        ) THEN 'then-detail-unknown'
        ELSE NULL
    END AS reason
FROM capability c
JOIN scene s ON s.id = c.scene_id
LEFT JOIN capability_evidence ce ON ce.capability_id = c.id
WHERE c.merged_into IS NULL;
