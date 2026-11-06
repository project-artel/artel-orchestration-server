-- SDK가 등록 시 보고한 씬 스캔 결과. 로드된 씬들의 UI 트리와 Build Settings의 씬 이름
-- 목록을 SDK가 만든 JSON 그대로 담는다.
--
-- 별도 테이블이 아니라 game_build의 컬럼이다. 스캔은 빌드당 하나의 최신본만 의미가 있고
-- (같은 빌드는 같은 씬 구성을 가진다), 이력을 쌓을 이유가 없기 때문이다. 같은 빌드가
-- 다시 등록하면 덮어쓴다.
--
-- NULL은 "아직 스캔을 보고한 적 없다"다. 스캔 없이 등록만 한 구버전 SDK도 있다.
ALTER TABLE game_build
    ADD COLUMN IF NOT EXISTS scene_scan JSONB;
