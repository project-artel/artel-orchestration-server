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
--   2. 그 주소가 조준 대상(control_*)으로 새지 못하게 하는 CHECK 셋
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
-- **유도**(오너 타입의 배치에서 씬만 얻었다)다. 적재기가 analysis_confidence 를 그 둘로 가른다.
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
-- 누를 자리 없이 누르라는 스텝을 받는다.
--
-- actionability 는 묶지 않는다. 적재기는 이 행을 not-a-step 으로 넣지만, 그것은 축이고 축은
-- 나중에 다른 이슈가 옮긴다(ARTEL-452 관측 축 · ARTEL-461 실행 축). 주소는 사실이고 축은 판정이다.
ALTER TABLE capability
    DROP CONSTRAINT IF EXISTS ck_capability_spawn_has_no_control;
ALTER TABLE capability
    ADD CONSTRAINT ck_capability_spawn_has_no_control
        CHECK (
            spawned_by_field IS NULL
                OR (control_path IS NULL AND control_selector IS NULL AND interaction = 'none')
        );

--------------------------------------------------------------------------------
-- 3. 스폰 행이 when-missing 을 덮지 않는다
--------------------------------------------------------------------------------
-- v_spec_gap 은 "다음에 무엇을 고칠까"에 답하는 표다. when-missing 은 그 중 "조작을 갖지 못한
-- 것"을 세는 칸인데, 스폰 행은 조작이 **없어야 맞는** 행이라 그대로 두면 111건이 이 칸을 채워
-- 실제 결함을 덮는다.
--
-- 그래서 when-missing 에서만 뺀다. 맨 앞에 새 사유를 세우지 않는 것은 의도다 — 스폰 행이 정작
-- 필요한 신호는 then-missing 이다. 적이 공격할 때 무엇이 달라지는지를 판독으로 확인할 근거가
-- 지금 명세에 아예 없고, 그것이 이 행들을 담는 이유다.
--
-- 나머지 분기와 사유 어휘는 V45 정의 그대로다.
DROP VIEW IF EXISTS v_spec_gap;
CREATE VIEW v_spec_gap AS
SELECT
    s.content_map_id,
    c.scene_id,
    c.id AS capability_id,
    c.status,
    CASE
        WHEN c.origin = 'evidence' AND ce.capability_id IS NULL THEN 'evidence-missing'
        WHEN c.interaction = 'none' AND c.spawned_by_field IS NULL THEN 'when-missing'
        WHEN ce.gaps @> '["subject-null"]'::jsonb THEN 'given-subject-unknown'
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
