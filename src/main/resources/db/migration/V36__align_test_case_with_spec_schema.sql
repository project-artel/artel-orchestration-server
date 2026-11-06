-- test_case를 들어오는 **명세 구조 그대로**로 맞춘다. (2026-08-11, ARTEL-329)
--
-- 명세가 CSV에서 JSON 배열로 바뀌고, 원소 하나가 이렇게 온다:
--
--   { "schema_version": "test-case.v1",
--     "spec":     { scene, precondition, step, expected_value, status },
--     "metadata": { source: {...}, generation: {...} } }
--
-- 이 배열은 봉투에 담겨 오고, 봉투에는 Agent 쪽 id·revision·시각이 붙는다. id는 저쪽 것이라
-- 우리가 쓰지 않는다. revision은 쓴다 — 아래 spec_revision 참조.
--
-- ⚠️ V33은 develop 최고 번호보다 낮아 이미 마이그레이션된 DB가 영영 적용하지 않는 죽은 번호다
-- (V35 주석 참조). 그래서 V36으로 잡는다.

--------------------------------------------------------------------------------
-- 1. 이름을 명세와 일치시킨다
--------------------------------------------------------------------------------
-- CSV 시절 이름이 실제 의미와 어긋나 있었고, 코드가 이미 두 곳에서 번역하고 있었다 —
-- ScenarioCompositionService가 Agent로 넘길 때 category→scene, title→testStep으로 바꾼다.
-- 저장 형태가 명세와 같아지면 그 번역이 사라진다. 이름만 바꾸는 것이라 값은 그대로 살아 있다.
ALTER TABLE test_case RENAME COLUMN category TO scene;
ALTER TABLE test_case RENAME COLUMN title    TO step;
ALTER TABLE test_case RENAME COLUMN expected TO expected_value;

-- step은 "`Canvas/continue` 표시 상태를 확인한다"처럼 한 문장이라 VARCHAR(255)로는 잘린다.
-- 잘린 스텝은 그럴듯해 보이면서 실행할 수 없으므로 넓힌다. scene도 씬 경로가 붙을 수 있어 넓힌다.
ALTER TABLE test_case ALTER COLUMN step  TYPE TEXT;
ALTER TABLE test_case ALTER COLUMN scene TYPE VARCHAR(200);

--------------------------------------------------------------------------------
-- 2. 명세가 주는데 담을 자리가 없던 것들
--------------------------------------------------------------------------------
-- **spec.status는 verification_status와 다른 축이다.** 이쪽은 명세를 만든 쪽이 매긴 값("ready"),
-- 저쪽은 우리 QA 런이 만든 결과(DRAFT/VERIFIED/BROKEN)다. 한 칸에 몰면 재적재가 검증 이력을
-- 덮어쓴다 — TestCaseSpecService가 이미 그 이유를 주석으로 적어 두고 있다.
--
-- **CHECK를 걸지 않는다.** 지금 관측된 값은 "ready" 하나뿐이고, 이 어휘는 우리가 소유하지 않는다.
-- verification_status에 CHECK가 있는 것은 그 값들을 우리가 정하기 때문이다. 남의 어휘에 추측으로
-- 제약을 걸면 나중에 정상 값이 400으로 거부되고, 그 실패는 적재 전체를 세운다.
-- nullable인 것은 이 컬럼이 생기기 전의 행에는 명세 상태가 실제로 없기 때문이다 — 기본값을
-- 지어내면 "ready인 적 없는 케이스"가 ready로 보인다.
ALTER TABLE test_case ADD COLUMN IF NOT EXISTS status VARCHAR(40);

-- 명세 계약의 버전. 계약이 바뀌었을 때 어떤 행이 어느 판으로 들어왔는지 알 수 있어야
-- 마이그레이션 대상을 고를 수 있다.
ALTER TABLE test_case ADD COLUMN IF NOT EXISTS schema_version VARCHAR(40);

-- metadata(source + generation)를 통째로 담는다. 컬럼으로 쪼개지 않는 이유는 이 안의 모양이
-- 생성기 쪽 사정으로 바뀌기 때문이다 — 지금 쪼개면 필드가 하나 늘 때마다 마이그레이션이 필요하고,
-- 우리는 이 값들로 질의하지 않는다(출처를 되짚을 때 읽을 뿐이다). 질의 축이 생기면 그때 승격한다.
ALTER TABLE test_case ADD COLUMN IF NOT EXISTS metadata JSONB NOT NULL DEFAULT '{}'::jsonb;

--------------------------------------------------------------------------------
-- 3. 멱등과 변경 감지
--------------------------------------------------------------------------------
-- metadata.source.spec_id를 컬럼으로 끌어올린다. 지금 적재는 (project_id, category, title)로
-- 같은 케이스인지 판단하는데, 그러면 **문구를 한 글자 고친 재전송이 새 케이스를 만든다.**
-- 명세가 문구를 다듬는 일은 흔하므로 그때마다 라이브러리가 늘고 옛 행이 유령으로 남는다.
--
-- JSONB 안에 두고 질의할 수도 있지만, 이 값은 적재 경로가 매 행마다 찾아 쓰는 키다.
-- 키는 컬럼으로 두어야 유니크 제약이 걸린다.
ALTER TABLE test_case ADD COLUMN IF NOT EXISTS spec_id VARCHAR(200);

-- 이 행이 **어느 판의 명세에서 왔는지**. 봉투의 revision을 행마다 찍는다.
--
-- 두 가지를 가능하게 한다:
--  1. 같은 revision이 다시 오면 통째로 건너뛴다(재전송이 흔하다).
--  2. 적재를 마친 뒤 `spec_revision < 이번 revision`인 행은 **이번 명세에 없던 케이스**다 —
--     명세에서 빠졌거나 이름이 바뀐 것이고, CSV 시절에는 알 방법이 없어 유령으로 남던 것들이다.
-- 지금은 그 판정을 하지 않고 값만 남긴다. 삭제는 되돌릴 수 없어서, 무엇이 사라지는지 눈으로
-- 확인한 뒤에 정책을 정한다.
ALTER TABLE test_case ADD COLUMN IF NOT EXISTS spec_revision INTEGER;

-- Agent가 이 명세를 보낸 시각(봉투의 created_at). 우리 created_at/updated_at은 **우리가 저장한
-- 시각**이라 다른 질문에 답한다. 하나로 합치면 "명세가 오래됐다"와 "우리가 늦게 받았다"를
-- 구분할 수 없어 둘 다 못 쓰게 된다.
ALTER TABLE test_case ADD COLUMN IF NOT EXISTS source_sent_at TIMESTAMP WITH TIME ZONE;

-- 부분 유니크: spec_id가 있는 행끼리만 프로젝트 안에서 유일하다.
-- 부분인 이유는 이 컬럼이 생기기 전에 들어온 행(CSV 적재분)에는 spec_id가 없기 때문이다.
-- 전체 유니크로 걸면 그 행들이 전부 NULL로 충돌하지는 않지만(NULL은 서로 다르다), 의도를
-- 인덱스에 적어 두는 편이 읽는 사람에게 분명하다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_test_case_spec_id
    ON test_case (project_id, spec_id) WHERE spec_id IS NOT NULL;

--------------------------------------------------------------------------------
-- 4. 코멘트
--------------------------------------------------------------------------------
COMMENT ON COLUMN test_case.scene IS
    '이 케이스가 검증되는 화면. 명세 spec.scene 그대로.';
COMMENT ON COLUMN test_case.step IS
    '테스트 스텝(무엇을 하는가). 명세 spec.step 그대로.';
COMMENT ON COLUMN test_case.expected_value IS
    '기대 결과. 명세 spec.expected_value 그대로.';
COMMENT ON COLUMN test_case.status IS
    '명세를 만든 쪽이 매긴 상태("ready" 등). 우리 QA 런의 결과인 verification_status와 다른 축이다.';
COMMENT ON COLUMN test_case.metadata IS
    '명세의 metadata(source + generation) 통째. 질의 대상이 아니라 출처를 되짚을 때 읽는다.';
COMMENT ON COLUMN test_case.spec_id IS
    '명세 쪽 안정 식별자(metadata.source.spec_id). 적재 멱등 키.';
COMMENT ON COLUMN test_case.spec_revision IS
    '이 행이 온 명세 봉투의 revision. 재전송 스킵과 "명세에서 빠진 케이스" 판정의 근거.';
COMMENT ON COLUMN test_case.source_sent_at IS
    'Agent가 명세를 보낸 시각(봉투 created_at). 우리가 저장한 시각인 created_at과 구분된다.';
