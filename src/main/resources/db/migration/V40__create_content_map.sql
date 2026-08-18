-- ARTEL-440: content_map — 게임에서 뽑은 근거(evidence)를 담는 씬 명세.
--
-- 이 스키마가 TC 생성기의 유일한 입력이다. 여기 담기지 못한 기능은 영원히 테스트되지 않는다.
--
-- 명세 한 줄의 세 칸을 given / when / then 이라 부른다. agent-server 의 TC 스키마가 쓰는 어휘와
-- 같아서 파이프라인 끝까지 한 단어로 이어진다.
--   given  ~인 상태에서   given_text / condition_tree
--   when   ~를 하면       interaction + control_selector / input_key
--   then   ~가 된다       capability_effect (observable | availability)

--------------------------------------------------------------------------------
-- 1. content_map — (게임 빌드, capture) 단위로 지금까지 알아낸 것 전체
--------------------------------------------------------------------------------
-- 스캔 한 번이 아니라 축적이다. SDK 는 씬을 재방문할 때마다 그 씬만 덮어쓰므로 갱신 단위는
-- 씬이고 이 행은 계속 살아 있다.
--
-- capture 를 키에 넣는 이유: editor 는 저장된 값이고 player 는 플레이가 지나간 뒤의 값이라
-- 같은 필드가 다른 뜻이다. 적의 label 이 authored 20 인가 남은 체력 20 인가가 갈린다.
CREATE TABLE IF NOT EXISTS content_map (
    id BIGSERIAL PRIMARY KEY,
    -- 빌드가 사라지면 그 빌드에 대해 알아낸 것도 사라진다. 이 지도는 빌드의 파생물이다.
    game_build_id BIGINT NOT NULL REFERENCES game_build (id) ON DELETE CASCADE,
    schema_version INT NOT NULL,
    capture VARCHAR(16) NOT NULL
        CHECK (capture IN ('editor', 'editor-play', 'player')),
    -- 근거 문서의 capabilities. 문서가 하는 약속이다: build-info-v1 · selector-v1 ·
    -- visual-roles-v1 · persistent-objects-v1. schema 가 세대라면 이쪽은 개별 약속이고
    -- 더하기만 한다.
    --
    -- 필드 존재로 계약을 추론하면 안 되기에 따로 있다 — build 는 label 의 뜻이 좁아지기 한
    -- 커밋 전에 들어왔고, 필드만 보고 판단하면 그 쌍을 틀리게 읽는다. selector-v1 이 없으면
    -- control_selector 를 채우면 안 되고, visual-roles-v1 이 없으면 control_label 을 컨트롤
    -- 이름으로 믿으면 안 된다.
    --
    -- 원문 이름(capabilities)을 쓰지 않는 것은 capability 테이블과 충돌하기 때문이다.
    evidence_promises JSONB NOT NULL DEFAULT '[]'::jsonb,
    -- 구워진 근거 전체의 지문. 같은 capture 인데 값이 다르면 코드가 바뀐 것이다.
    evidence_digest VARCHAR(32) NOT NULL,
    unity VARCHAR(32),
    platform VARCHAR(32),
    backend VARCHAR(16),
    development BOOLEAN,
    sdk_version VARCHAR(32),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_content_map_build_capture UNIQUE (game_build_id, capture)
);

--------------------------------------------------------------------------------
-- 2. scene — 정적. evidence 순회가 만난 씬
--------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS scene (
    id BIGSERIAL PRIMARY KEY,
    content_map_id BIGINT NOT NULL REFERENCES content_map (id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    -- 식별자를 남긴 설명. 무엇이 무엇을 판정하고 어디로 이어지는지.
    summary TEXT,
    -- 근거의 scenes[] 에 이름만 있고 순회하지 못한 씬. false 면 기능이 비어 있는 게 정상이다.
    walked BOOLEAN NOT NULL DEFAULT FALSE,
    -- 화면 구분이 없을 때만 의미 있는 대표 이미지. 보통은 screen 쪽이 진짜다.
    image_object_key VARCHAR(512),
    gaps JSONB NOT NULL DEFAULT '[]'::jsonb,
    scanned_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_scene_map_name UNIQUE (content_map_id, name)
);

--------------------------------------------------------------------------------
-- 3. screen — 런타임. QA 런이 관측한 실제 상태
--------------------------------------------------------------------------------
-- 씬 하나에 화면이 여럿일 수 있다. 오버레이·팝업·상태 분기. 정적 분석으로는 알 수 없어
-- QA 런 전에는 0행이고 그게 정상이다.
--
-- 씬으로 뭉개면 TC 가 깨진다 — 이어하기 버튼이 켜진 화면과 꺼진 화면이 같은 씬인데,
-- "그 버튼을 눌러라"는 TC 가 절반의 경우에 실패하고 agent 가 그것을 결함으로 보고한다.
--
-- name 은 표시용이고 조인 키가 아니다. 기계는 discriminator 로 판정하고 이름은 LLM 이 짓는다.
CREATE TABLE IF NOT EXISTS screen (
    id BIGSERIAL PRIMARY KEY,
    scene_id BIGINT NOT NULL REFERENCES scene (id) ON DELETE CASCADE,
    name VARCHAR(255),
    -- 이 화면임을 판정하는 pulse 관측 조건.
    -- [{"selector":"Canvas[2]/continue[2]","active":true}]
    discriminator JSONB NOT NULL,
    image_object_key VARCHAR(512),
    image_captured_at TIMESTAMP WITH TIME ZONE,
    -- 어느 런에서 처음 봤나. 출처 참조라 런이 지워져도 화면은 남는다.
    first_seen_qa_run_id BIGINT REFERENCES qa_run (id) ON DELETE SET NULL,
    observed_count INT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_screen_scene ON screen (scene_id);

--------------------------------------------------------------------------------
-- 4. capability — 이 씬에서 무엇을 할 수 있나
--------------------------------------------------------------------------------
-- 축이 둘이다.
--   origin        어디서 왔나        evidence | observed | inferred | human
--   verification  실행으로 확인됐나   unverified | confirmed | contradicted
--
-- 하나로 뭉치면 "IL 분석기가 확신함"과 "돌려봐서 됨"을 구분하지 못한다. QA agent 가
-- 플레이하며 배운 기능이 evidence 출신과 같은 통에 들어가는 순간, TC 가 근거 없는 것을
-- 근거 있는 것처럼 취급한다.
--
-- contradicted 를 지우지 않는 이유: 지우면 "우리가 틀렸다"와 "게임이 고장났다"를 구분할
-- 기록이 사라진다.
--
-- 액션 프로토콜의 어휘(button_click 따위)는 여기 두지 않는다. 그것은 SDK 의 것이고 배포마다
-- 바뀐다. 판독의 offers 가 그 오브젝트가 지금 무엇에 응답하는지 실어 주므로, 어떤 메서드로
-- 보낼지는 agent 가 런타임에 정하는 편이 더 정확하다. 여기에는 프로토콜이 바뀌어도 그대로인
-- 의도만 남긴다.
CREATE TABLE IF NOT EXISTS capability (
    id BIGSERIAL PRIMARY KEY,
    scene_id BIGINT NOT NULL REFERENCES scene (id) ON DELETE CASCADE,

    origin VARCHAR(16) NOT NULL
        CHECK (origin IN ('evidence', 'observed', 'inferred', 'human')),
    verification VARCHAR(16) NOT NULL DEFAULT 'unverified'
        CHECK (verification IN ('unverified', 'confirmed', 'contradicted')),

    -- 식별자를 남긴 설명. 모든 origin 공통.
    -- 경로·타입·메서드·필드는 원문 그대로 쓰고 사이만 말로 잇는다. MapMove.position 을
    -- "캐릭터가 옆으로 이동"으로 옮기는 것이 이 시스템에서 가장 비싼 거짓 명세다.
    summary TEXT NOT NULL,

    -- given
    given_text TEXT,

    -- when.
    -- selector 는 실행 간 유지되는 안정 식별자다. 조준에 직접 쓰지 못한다 — 현재 액션
    -- 프로토콜은 int instance id 를 받고 그 숫자는 프로세스를 넘지 못한다. 실행 시 해석은
    -- qa_run_target 이 맡는다.
    control_selector VARCHAR(512),
    control_path VARCHAR(512),
    control_label TEXT,
    -- 프로토콜 메서드가 아니라 의도다. none 은 조작 없이 일어나는 것 — 타이머·로딩 완료·
    -- 코루틴. TC 가 지시할 수 없으므로 시나리오에서 지나가는 구간으로만 쓴다.
    interaction VARCHAR(16) NOT NULL
        CHECK (interaction IN ('click', 'type', 'press', 'axis', 'none')),
    input_key VARCHAR(64),
    input_phase VARCHAR(8) CHECK (input_phase IN ('down', 'held', 'up')),

    -- 학습된 힌트. 권위 없음. agent 가 먼저 시도해 볼 값이지 따라야 하는 값이 아니다.
    -- 실패하면 agent 가 다시 정하고 여기를 갱신한다.
    hint_action_method VARCHAR(32),
    hint_action_params JSONB,
    hint_from_qa_run_id BIGINT REFERENCES qa_run (id) ON DELETE SET NULL,

    status VARCHAR(32) NOT NULL
        CHECK (status IN ('runnable', 'needs-probe', 'unreachable-precondition', 'not-a-step')),

    -- observed 로 발견한 것이 나중에 evidence 로도 확인되는 일이 있다.
    merged_into BIGINT REFERENCES capability (id) ON DELETE SET NULL,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_capability_press_needs_key
        CHECK ((interaction = 'press') = (input_key IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS idx_capability_scene
    ON capability (scene_id, origin, verification);
CREATE INDEX IF NOT EXISTS idx_capability_selector
    ON capability (control_selector);

--------------------------------------------------------------------------------
-- 5. capability_evidence — evidence 출신만 갖는 것
--------------------------------------------------------------------------------
-- 서브테이블로 뗀 이유: 이 컬럼들을 capability 에 두면 QA 가 관측으로 배운 기능이 NOT NULL 에
-- 막혀 더미값을 넣게 되고, 그 순간 두 종류가 구분 불가능해진다.
--
-- analysis_confidence 는 IL 분석기의 자기 확신도지 실행 확인이 아니다. 실행 확인은
-- capability.verification 이다. 이름이 겹쳐 혼동되던 자리다.
CREATE TABLE IF NOT EXISTS capability_evidence (
    capability_id BIGINT PRIMARY KEY REFERENCES capability (id) ON DELETE CASCADE,
    -- Assembly|타입|메서드|시그니처. 난독화에도 살아남는 안정 키.
    entry_id VARCHAR(1024) NOT NULL,
    owner_type VARCHAR(512) NOT NULL,
    method VARCHAR(255) NOT NULL,
    method_id VARCHAR(1024),
    record_kind VARCHAR(16) NOT NULL CHECK (record_kind IN ('candidate', 'flow')),
    trigger_kind VARCHAR(16) NOT NULL CHECK (trigger_kind IN ('unity-event', 'lifecycle')),
    analysis_confidence VARCHAR(16) NOT NULL
        CHECK (analysis_confidence IN ('verified', 'derived', 'partial')),
    -- 트리 원본. 평탄화 금지 — either 가 every 로 뒤집히면 "둘 중 하나"가 "둘 다"가 된다.
    condition_tree JSONB NOT NULL,
    binding_event VARCHAR(128),
    binding_receiver VARCHAR(512),
    -- 사람이 검증할 유일한 출처.
    call_path JSONB NOT NULL DEFAULT '[]'::jsonb,
    -- SDK 가 말한 것과 적재기가 판정한 것을 함께 담는다.
    -- given-subject-unknown · given-incomplete · given-unread · subject-null 등.
    gaps JSONB NOT NULL DEFAULT '[]'::jsonb
);
CREATE INDEX IF NOT EXISTS idx_capability_evidence_entry
    ON capability_evidence (entry_id);

--------------------------------------------------------------------------------
-- 6. capability_inference — inferred 출신만 갖는 것
--------------------------------------------------------------------------------
-- 관측된 것과 추론된 것을 섞지 않는다. 추론은 딛고 선 관측을 반드시 밝힌다.
CREATE TABLE IF NOT EXISTS capability_inference (
    capability_id BIGINT PRIMARY KEY REFERENCES capability (id) ON DELETE CASCADE,
    model VARCHAR(64) NOT NULL,
    prompt_version VARCHAR(64),
    rationale TEXT NOT NULL,
    -- [capability_observation.id, ...]
    based_on JSONB NOT NULL DEFAULT '[]'::jsonb
);

--------------------------------------------------------------------------------
-- 7. capability_effect — then. 무엇이 달라지나
--------------------------------------------------------------------------------
-- 근거가 말한 효과와 실제로 관측된 효과를 나란히 둔다. 어긋나는 것이 결함 신호이고,
-- 관측에만 있는 것이 근거의 구멍이다(서드파티 트윈처럼 정적으로 안 잡히는 것).
--
-- category 가 then 슬롯을 가른다. state 는 결과로 쓰지 않는다 — 이름으로 화면 변화를
-- 짐작하면 조용히 틀린다. 실측에서 47건이 이름 기반 오분류였다.
CREATE TABLE IF NOT EXISTS capability_effect (
    id BIGSERIAL PRIMARY KEY,
    capability_id BIGINT NOT NULL REFERENCES capability (id) ON DELETE CASCADE,
    origin VARCHAR(16) NOT NULL DEFAULT 'evidence'
        CHECK (origin IN ('evidence', 'observed')),
    category VARCHAR(16) NOT NULL
        CHECK (category IN ('observable', 'availability', 'state')),
    -- scene | ui-value | transform | animation | audio | instantiate | destroy |
    -- active-state | saved | write
    kind VARCHAR(32) NOT NULL,
    target VARCHAR(1024),
    -- null 이면 "값이 바뀐다"까지만 쓸 수 있다.
    detail VARCHAR(512),
    -- pulse 가 되읽을 수 있나. 판정 가능 여부의 상한이다(실측 watching 111 / 1,126).
    watchable BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_capability_effect_capability
    ON capability_effect (capability_id, category);
CREATE INDEX IF NOT EXISTS idx_capability_effect_target
    ON capability_effect (target);

--------------------------------------------------------------------------------
-- 8. capability_observation — origin 무관하게 모든 기능이 여기 쌓인다
--------------------------------------------------------------------------------
-- pulse 는 "값이 어떻게 달라졌나"까지만 말하고 무엇 때문인지는 말하지 않는다. 액션 채널과
-- 상태 채널을 판독 번호로 붙이는 것이 이 테이블이다.
--
-- 실제로 보낸 메서드와 인자를 여기 남긴다. 재현이 필요한 곳은 content_map 이 아니라 이
-- 기록과 TS 다 — agent 가 런마다 다시 정해도 무엇을 보냈는지는 남아 있어야 한다.
-- attempts > 1 이 쌓이는 자리가 힌트가 나쁜 자리이고 매핑 규칙을 고칠 지점이다.
CREATE TABLE IF NOT EXISTS capability_observation (
    id BIGSERIAL PRIMARY KEY,
    capability_id BIGINT NOT NULL REFERENCES capability (id) ON DELETE CASCADE,
    qa_run_id BIGINT NOT NULL REFERENCES qa_run (id) ON DELETE CASCADE,
    screen_id BIGINT REFERENCES screen (id) ON DELETE SET NULL,
    acted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    action_method VARCHAR(32) NOT NULL,
    action_params JSONB NOT NULL DEFAULT '{}'::jsonb,
    attempts INT NOT NULL DEFAULT 1,
    reading_before BIGINT,
    reading_after BIGINT,
    -- 눌렀는데 아무 값도 안 변했나. 결함 후보의 1차 신호.
    fired BOOLEAN NOT NULL,
    observed_effects JSONB NOT NULL DEFAULT '[]'::jsonb
);
CREATE INDEX IF NOT EXISTS idx_capability_observation_capability
    ON capability_observation (capability_id, acted_at DESC);
CREATE INDEX IF NOT EXISTS idx_capability_observation_run
    ON capability_observation (qa_run_id, acted_at);

--------------------------------------------------------------------------------
-- 9. screen_capability — 화면이 실제로 제공한 기능. 씬 목록의 부분집합
--------------------------------------------------------------------------------
-- 화면이 자기 목록을 갖지 않는 이유: 두 벌 두면 갈라진다. 정적 근거가 아는 것은 "이 타입이
-- 이 씬에 놓였다"까지고, 어느 화면 상태에서 실제로 눌리는지는 런타임만 안다.
CREATE TABLE IF NOT EXISTS screen_capability (
    screen_id BIGINT NOT NULL REFERENCES screen (id) ON DELETE CASCADE,
    capability_id BIGINT NOT NULL REFERENCES capability (id) ON DELETE CASCADE,
    observed_count INT NOT NULL DEFAULT 0,
    fired_count INT NOT NULL DEFAULT 0,
    PRIMARY KEY (screen_id, capability_id)
);

--------------------------------------------------------------------------------
-- 10. screen_transition — 화면 전이. 관측만
--------------------------------------------------------------------------------
-- 정적으로 만들지 않는다. 추측을 넣으면 "실제로 어떻게 흘렀나"가 오염된다. 같은 씬 안
-- 전이(팝업 열림)가 있어 scene_edge 로 대체할 수 없다.
--
-- capability_id 가 null 이면 자동 전이 — TC 가 지시할 수 없는 것이다.
CREATE TABLE IF NOT EXISTS screen_transition (
    id BIGSERIAL PRIMARY KEY,
    from_screen_id BIGINT NOT NULL REFERENCES screen (id) ON DELETE CASCADE,
    to_screen_id BIGINT NOT NULL REFERENCES screen (id) ON DELETE CASCADE,
    capability_id BIGINT REFERENCES capability (id) ON DELETE SET NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('action', 'state', 'auto')),
    crosses_scene BOOLEAN NOT NULL,
    observed_count INT NOT NULL DEFAULT 0,
    first_seen_qa_run_id BIGINT REFERENCES qa_run (id) ON DELETE SET NULL,
    CONSTRAINT uk_screen_transition UNIQUE (from_screen_id, to_screen_id, capability_id)
);

--------------------------------------------------------------------------------
-- 11. scene_edge — 씬 전이. 정적 후보 + 런타임 검증
--------------------------------------------------------------------------------
-- effects.kind='scene' 으로 정적으로 채워서 출발한다. 빈 테이블로 시작하지 않는다.
--
-- verified_at IS NULL 인 간선이 곧 커버리지 구멍이고, QA agent 에게 다음에 무엇을 시도할지
-- 알려주는 유일한 신호다. screen_transition 에서 파생시키면 이 신호가 사라진다 — 아직
-- 못 가본 전이는 관측에 없기 때문이다.
CREATE TABLE IF NOT EXISTS scene_edge (
    id BIGSERIAL PRIMARY KEY,
    from_scene_id BIGINT NOT NULL REFERENCES scene (id) ON DELETE CASCADE,
    -- 이름으로 둔다. 아직 순회하지 못한 씬으로 가는 전이가 있다.
    to_scene_name VARCHAR(255) NOT NULL,
    to_scene_id BIGINT REFERENCES scene (id) ON DELETE SET NULL,
    capability_id BIGINT REFERENCES capability (id) ON DELETE CASCADE,
    given_text TEXT,
    source VARCHAR(16) NOT NULL CHECK (source IN ('static', 'runtime')),
    verified_at TIMESTAMP WITH TIME ZONE,
    observed_count INT NOT NULL DEFAULT 0,
    first_observed_transition_id BIGINT REFERENCES screen_transition (id) ON DELETE SET NULL,
    CONSTRAINT uk_scene_edge UNIQUE (from_scene_id, to_scene_name, capability_id)
);

--------------------------------------------------------------------------------
-- 12. qa_run_target — 런 단위 조준 해석표
--------------------------------------------------------------------------------
-- content_map 에 두지 않는다. instance id 는 프로세스를 넘지 못하므로 런이 끝나면
-- 쓰레기다.
--
-- reading 을 같이 두는 이유: 액션 실패가 "버튼이 안 먹었다"인지 "id 가 낡았다"인지 갈라야
-- 한다. 실패 시 reading 이 최신인지 보고 아니면 재조회 후 1회 재시도.
--
-- 이 치환은 agent 가 아니라 서버가 한다. 기계적인 일이고, agent 에게 시키면 판독 전체를
-- 프롬프트에 넣어야 한다.
CREATE TABLE IF NOT EXISTS qa_run_target (
    qa_run_id BIGINT NOT NULL REFERENCES qa_run (id) ON DELETE CASCADE,
    scene_name VARCHAR(255) NOT NULL,
    selector VARCHAR(512) NOT NULL,
    instance_id INT NOT NULL,
    reading BIGINT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (qa_run_id, scene_name, selector)
);

--------------------------------------------------------------------------------
-- 13. v_content_map_capability — TC 생성기가 읽는 유일한 창구
--------------------------------------------------------------------------------
-- 효과는 여기 접지 않는다. 행이 여러 개라 조인하면 곱해진다.
CREATE OR REPLACE VIEW v_content_map_capability AS
SELECT
    cm.id AS content_map_id,
    cm.capture,
    s.id AS scene_id,
    s.name AS scene_name,
    s.summary AS scene_summary,
    c.id AS capability_id,
    c.origin,
    c.verification,
    c.status,
    c.summary,
    c.given_text,
    c.control_selector,
    c.control_path,
    c.control_label,
    c.interaction,
    c.input_key,
    c.input_phase,
    c.hint_action_method,
    c.hint_action_params,
    ce.entry_id,
    ce.record_kind,
    ce.trigger_kind,
    ce.analysis_confidence,
    ce.condition_tree,
    ce.gaps
FROM capability c
JOIN scene s ON s.id = c.scene_id
JOIN content_map cm ON cm.id = s.content_map_id
LEFT JOIN capability_evidence ce ON ce.capability_id = c.id
WHERE c.merged_into IS NULL
  AND c.status <> 'not-a-step';

--------------------------------------------------------------------------------
-- 14. v_spec_gap — 명세의 어느 칸을 못 채웠나
--------------------------------------------------------------------------------
-- 테이블이 아니라 뷰인 이유: 전부 다른 컬럼에서 계산되므로 적재 코드를 두면 낡는다.
--
-- 이것은 QA 결함이 아니라 개발 우선순위 신호다. then-missing 이 많으면 수집기(SDK)를 고칠
-- 차례이고, given-subject-unknown 이 많으면 조건 분석기의 주어 추적이 약한 것이다.
-- agent 가 메울 수 있는 것이 아니다 — 근거에 없는 것을 메우면 그럴듯한 거짓말이 된다.
CREATE OR REPLACE VIEW v_spec_gap AS
SELECT
    s.content_map_id,
    c.scene_id,
    c.id AS capability_id,
    CASE
        WHEN c.interaction = 'none' THEN 'when-missing'
        WHEN ce.gaps @> '["subject-null"]'::jsonb THEN 'given-subject-unknown'
        WHEN ce.analysis_confidence = 'partial'
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
