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
    -- selector 는 형제 인덱스가 붙은 위치 경로다(Canvas[2]/MapSceneButton[1]). 한 판독 안에서는
    -- 반드시 하나를 가리키지만, 계층이 정적일 때만 실행 간 유지된다 — 형제가 생기거나 사라지면
    -- 인덱스가 밀려 같은 문자열이 다른 오브젝트를 가리킨다. 런타임에 스폰되는 목록에는 채우지
    -- 않는 편이 맞다.
    --
    -- 조준에 직접 쓰지도 못한다 — 현재 액션 프로토콜은 int instance id 를 받고 그 숫자는
    -- 프로세스를 넘지 못한다. 실행 시 해석표는 판독을 받는 ARTEL-449 가 자기 마이그레이션으로
    -- 가져간다.
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
        CHECK ((interaction = 'press') = (input_key IS NOT NULL)),

    -- capability_evidence 가 복합 FK 로 이것을 참조한다. 근거 행이 evidence 출신 기능에만
    -- 붙는다는 것을 DB 가 강제하게 하려는 것이고, 그것이 없으면 관측 출신 기능에 IL 근거를
    -- 붙일 수 있어 "축이 둘"이라는 전제가 반대쪽에서 무너진다.
    CONSTRAINT uk_capability_id_origin UNIQUE (id, origin)
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
    -- 항상 'evidence' 다. 값을 담으려는 컬럼이 아니라 복합 FK 의 한쪽 다리다 — 이 행이
    -- evidence 출신 기능에만 붙는다는 것을 DB 가 강제하게 한다.
    origin VARCHAR(16) NOT NULL DEFAULT 'evidence' CHECK (origin = 'evidence'),
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
    -- v_spec_gap 이 매칭하는 토큰은 정확히 이 셋이다:
    --   subject-null                     조건의 주어를 못 정했다 (context: null)
    --   callee-condition-not-composed    호출된 쪽 조건을 못 합쳤다
    --   unread-condition                 조건을 읽지 못했다
    -- 뷰가 내는 사유 이름(given-subject-unknown 등)과 다르다. 여기 담기는 것은 입력이고
    -- 저쪽은 출력이라, 둘을 같은 말로 적으면 적재기가 출력 이름을 넣고 분기가 영영 안 걸린다.
    gaps JSONB NOT NULL DEFAULT '[]'::jsonb,

    CONSTRAINT fk_capability_evidence_origin
        FOREIGN KEY (capability_id, origin) REFERENCES capability (id, origin) ON DELETE CASCADE
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
    -- CASCADE 다. 관측은 그 런의 기록이라 런 없이 홀로 서지 못한다.
    --
    -- 대가를 적어 둔다: 런을 지우면 capability.verification='confirmed' 는 남고 그 근거였던
    -- 관측만 사라진다. capability_inference.based_on 도 FK 없는 JSON 이라 매달린 id 를 든 채
    -- 남는다. 런 삭제를 실제로 하게 되면 검증 강등을 함께 정해야 한다 — 지금은 qa_run 을
    -- 지우는 경로가 없어 열어 둔다.
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
-- 위 UNIQUE 는 capability_id 가 NULL 인 행에 걸리지 않는다 — Postgres 는 UNIQUE 에서 NULL 을
-- 서로 다른 값으로 본다. 그런데 NULL 이 곧 자동 전이이고, 자동 전이야말로 반복 관측되는 것이라
-- 이 인덱스가 없으면 observed_count 가 중복 행에 쪼개져 누적된다.
CREATE UNIQUE INDEX IF NOT EXISTS uk_screen_transition_auto
    ON screen_transition (from_screen_id, to_screen_id) WHERE capability_id IS NULL;

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
    -- SET NULL 이다. 이 행은 verified_at · observed_count 라는 런타임이 벌어 온 지식을 들고
    -- 있고, 재적재가 기능을 지웠다 넣는 경로에서 그것까지 사라지면 안 된다. 무엇으로 갔는지를
    -- 잃을지언정 갔다는 사실은 남긴다. screen_transition.capability_id 와 같은 판단이다.
    capability_id BIGINT REFERENCES capability (id) ON DELETE SET NULL,
    given_text TEXT,
    source VARCHAR(16) NOT NULL CHECK (source IN ('static', 'runtime')),
    verified_at TIMESTAMP WITH TIME ZONE,
    observed_count INT NOT NULL DEFAULT 0,
    first_observed_transition_id BIGINT REFERENCES screen_transition (id) ON DELETE SET NULL,
    CONSTRAINT uk_scene_edge UNIQUE (from_scene_id, to_scene_name, capability_id)
);
-- 같은 이유. 여기서는 더 나쁘다 — 중복 간선에 verified_at 이 흩어지면 "아직 못 가본 전이"라는
-- 신호가 틀리고, 그 신호가 이 표의 존재 이유다.
CREATE UNIQUE INDEX IF NOT EXISTS uk_scene_edge_auto
    ON scene_edge (from_scene_id, to_scene_name) WHERE capability_id IS NULL;

-- capability 를 참조하는 FK 들의 인덱스. 재적재는 evidence 기능을 대량으로 지웠다 넣으므로,
-- 인덱스가 없으면 CASCADE/SET NULL 마다 참조 테이블을 순차 스캔한다.
CREATE INDEX IF NOT EXISTS idx_screen_capability_capability
    ON screen_capability (capability_id);
CREATE INDEX IF NOT EXISTS idx_screen_transition_capability
    ON screen_transition (capability_id) WHERE capability_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_scene_edge_capability
    ON scene_edge (capability_id) WHERE capability_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_capability_merged_into
    ON capability (merged_into) WHERE merged_into IS NOT NULL;

--------------------------------------------------------------------------------
-- 12. v_content_map_capability — TC 생성기가 읽는 유일한 창구
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
-- 13. v_spec_gap — 명세의 어느 칸을 못 채웠나
--------------------------------------------------------------------------------
-- 테이블이 아니라 뷰인 이유: 전부 다른 컬럼에서 계산되므로 적재 코드를 두면 낡는다.
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
-- 그 순서의 대가: 조건이 partial 이면서 관측 가능 효과도 없는 행은 given-incomplete 로만 잡혀
-- then-missing 집계가 그만큼 적게 나온다. 두 사유를 다 세야 하면 이 뷰가 아니라 사유별
-- EXISTS 를 각각 세는 질의를 따로 써야 한다.
--
-- not-a-step 을 거르지 않는 것도 의도다. 그 행들이 when-missing 의 본체이고, 무엇이 조작을
-- 갖지 못했는지가 이 표가 답해야 할 질문이다. 대신 status 를 함께 내서, TC 생성기가 실제로
-- 받는 행(v_content_map_capability 가 내주는 것)만 세고 싶은 쪽이 걸러 쓸 수 있게 한다.
CREATE OR REPLACE VIEW v_spec_gap AS
SELECT
    s.content_map_id,
    c.scene_id,
    c.id AS capability_id,
    c.status,
    CASE
        -- 적재기 결함이다. 게임의 근거가 부족한 것이 아니라 우리가 근거를 잃은 것이라,
        -- then-missing 으로 뭉뚱그리면 SDK 를 고치러 가서 헛짚는다.
        WHEN c.origin = 'evidence' AND ce.capability_id IS NULL THEN 'evidence-missing'
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
