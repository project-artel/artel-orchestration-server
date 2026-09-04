-- 한 qa_run 이 어느 실험에 속하는지. (ARTEL-813 후속)
--
-- arm 은 여기 적지 않는다. 어떤 arm 인지는 run_config 가 이미 말한다(content_map_mode ·
-- knowledge_mode). 같은 사실을 두 곳에 적으면 언젠가 어긋나고, 그때 어느 쪽이 진실인지가 질문이
-- 된다. 빠져 있던 것은 arm 이 아니라 묶음이다 — 같은 설정으로 다음 달에 다시 돌리면 run_config 는
-- 같은데 다른 실험이고, 그 둘을 가를 것이 지금 없다.
--
-- nullable 이고 기본값이 없다. NULL 은 "어느 실험에도 안 묶인 런" 이고, 이 컬럼이 생기기 전의 모든
-- 행이 그것이다. 기본값을 두면 그 행들이 실험 하나에 소급해 묶인다.
--
-- 길이는 test_run.name 과 같은 255 다. 두 값이 화면의 같은 필터 줄에서 나란히 선택기로 서므로,
-- 한쪽 선택기에 들어가는 이름은 다른 쪽에도 들어가야 한다.
ALTER TABLE qa_run ADD COLUMN IF NOT EXISTS label VARCHAR(255);

-- 목록 조회(GET /api/qa-stats/labels)와 label 필터가 둘 다 이 컬럼을 술어로 쓴다. 대부분의 행이
-- NULL 이라 부분 인덱스로 둔다 — 실험에 묶인 런만 인덱스에 실린다.
CREATE INDEX IF NOT EXISTS idx_qa_run_label ON qa_run (label) WHERE label IS NOT NULL;
