--------------------------------------------------------------------------------
-- TC 를 케이스 정체(test_case)와 버전별 데이터(test_case_version)로 나눈다 (ARTEL-1018)
--
-- #293 이후 같은 케이스가 build 마다 별개의 test_case 행이었다. 버전을 넘어서는 metadata.case_key 로만
-- 이어져서, 시나리오 · 폴더 · QA 판정이 버전마다 다른 case_id 를 가리켰다. 이제 같은 케이스는 프로젝트
-- 안에서 한 행(정체)이고, 버전마다 달라지는 내용과 판정은 test_case_version 에 둔다.
--
-- test_case 의 내용 칸은 그 케이스가 있는 가장 최근 버전의 내용을 비춘다. 이 표를 읽는 기존 쿼리는
-- 그대로 최신 내용을 읽는다. 비춤은 코드(TestCaseVersionRepository.syncIdentities)가 한다.
--------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS test_case_version (
    id                  BIGSERIAL PRIMARY KEY,
    test_case_id        BIGINT       NOT NULL REFERENCES test_case (id) ON DELETE CASCADE,
    game_build_id       BIGINT       NOT NULL REFERENCES game_build (id) ON DELETE CASCADE,
    content_map_id      BIGINT,
    scene               VARCHAR(200) NOT NULL,
    step                TEXT         NOT NULL,
    precondition        TEXT,
    condition           JSONB,
    expected_value      TEXT         NOT NULL,
    expected_items      JSONB,
    status              VARCHAR(40),
    capability_key      TEXT,
    metadata            JSONB        NOT NULL DEFAULT '{}'::jsonb,
    verification_status VARCHAR(20)  NOT NULL DEFAULT 'DRAFT'
        CHECK (verification_status IN ('DRAFT', 'VERIFIED', 'FAILED', 'REGRESSION', 'BROKEN')),
    verified_at         TIMESTAMP WITH TIME ZONE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_test_case_version UNIQUE (test_case_id, game_build_id)
);

CREATE INDEX IF NOT EXISTS idx_test_case_version_build_status
    ON test_case_version (game_build_id, verification_status);

--------------------------------------------------------------------------------
-- 데이터: build 별 행을 case_key 당 한 정체로 합친다.
--
-- 대상은 지도가 낸 행 중 content_map 을 찾을 수 있는 것뿐이다. content map 이 지워진 행과 사람이 쓴
-- 행은 그대로 둔다(버전 없이 남는다).
--------------------------------------------------------------------------------

CREATE TEMP TABLE tc_src ON COMMIT DROP AS
SELECT tc.id,
       tc.project_id,
       tc.metadata ->> 'case_key' AS case_key,
       cm.id                      AS content_map_id,
       cm.game_build_id,
       gb.created_at              AS build_created_at,
       tc.verification_status
  FROM test_case tc
  JOIN content_map cm ON CAST(cm.id AS TEXT) = tc.metadata ->> 'content_map_id'
  JOIN game_build gb ON gb.id = cm.game_build_id
 WHERE tc.metadata ->> 'origin' = 'content-map'
   AND tc.metadata ->> 'case_key' IS NOT NULL;

-- 남길 행: BROKEN 이 아닌 것 중 가장 최근 build 의 행. 그 행의 내용이 곧 최신 내용이다.
CREATE TEMP TABLE tc_keep ON COMMIT DROP AS
SELECT DISTINCT ON (project_id, case_key) project_id, case_key, id AS keep_id
  FROM tc_src
 ORDER BY project_id, case_key, (verification_status = 'BROKEN'), build_created_at DESC, game_build_id DESC, id DESC;

CREATE TEMP TABLE tc_map ON COMMIT DROP AS
SELECT s.id AS old_id, k.keep_id
  FROM tc_src s
  JOIN tc_keep k ON k.project_id = s.project_id AND k.case_key = s.case_key;

-- 버전 행. BROKEN 행은 그 build 의 지도에서 이미 사라진 줄이라 버전으로 옮기지 않는다.
INSERT INTO test_case_version (test_case_id, game_build_id, content_map_id, scene, step, precondition,
                               condition, expected_value, expected_items, status, capability_key, metadata,
                               verification_status, verified_at, created_at, updated_at)
SELECT DISTINCT ON (m.keep_id, s.game_build_id)
       m.keep_id, s.game_build_id, s.content_map_id, tc.scene, tc.step, tc.precondition,
       tc.condition, tc.expected_value, tc.expected_items, tc.status, tc.capability_key, tc.metadata,
       tc.verification_status,
       CASE WHEN tc.last_verified_build_id = s.game_build_id THEN tc.updated_at END,
       tc.created_at, tc.updated_at
  FROM tc_src s
  JOIN tc_map m ON m.old_id = s.id
  JOIN test_case tc ON tc.id = s.id
 WHERE s.verification_status <> 'BROKEN'
 ORDER BY m.keep_id, s.game_build_id, s.id DESC;

-- 시나리오 스텝의 case_id 를 남길 행으로 옮긴다.
UPDATE test_scenario sc
   SET steps = (
           SELECT jsonb_agg(
                      CASE WHEN m.keep_id IS NOT NULL THEN jsonb_set(t.e, '{case_id}', to_jsonb(m.keep_id))
                           ELSE t.e END
                      ORDER BY t.o)
             FROM jsonb_array_elements(sc.steps) WITH ORDINALITY AS t(e, o)
             LEFT JOIN tc_map m
               ON jsonb_typeof(t.e -> 'case_id') = 'number'
              AND m.old_id = (t.e ->> 'case_id')::BIGINT
              AND m.old_id <> m.keep_id
       )
 WHERE EXISTS (
           SELECT 1
             FROM jsonb_array_elements(sc.steps) AS e
             JOIN tc_map m
               ON jsonb_typeof(e -> 'case_id') = 'number'
              AND m.old_id = (e ->> 'case_id')::BIGINT
              AND m.old_id <> m.keep_id
       );

-- 남길 행에 folder 가 없으면 묶음의 다른 행에서 가져온다.
UPDATE test_case k
   SET folder_id = sub.folder_id
  FROM (SELECT DISTINCT ON (m.keep_id) m.keep_id, tc.folder_id
          FROM tc_map m
          JOIN test_case tc ON tc.id = m.old_id
         WHERE tc.folder_id IS NOT NULL
         ORDER BY m.keep_id, tc.id DESC) sub
 WHERE k.id = sub.keep_id
   AND k.folder_id IS NULL;

-- 합쳐진 행을 지운다. 임베딩은 ON DELETE CASCADE 로 함께 지워진다(V23).
-- qa_log · qa_try_score 안의 옛 case_id 는 그때의 이력이라 그대로 둔다.
DELETE FROM test_case
 WHERE id IN (SELECT old_id FROM tc_map WHERE old_id <> keep_id);
