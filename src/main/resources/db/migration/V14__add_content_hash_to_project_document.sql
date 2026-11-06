-- 업로드 파일 중복(프로젝트 단위) 차단을 위한 파일 해시.
--
-- register(업로드 확정) 때 Orche가 S3 원본을 스트리밍하며 SHA-256을 계산해 저장한다.
-- 같은 프로젝트에 동일 hash가 이미 있으면 등록을 거부(409)하고 S3 객체를 지운다 → 중복 파일에
-- 대해 Agent /extract(요약) 요청을 애초에 막는다. 파일을 공유하는 다른 프로젝트/팀은 project_id가
-- 다르므로 허용된다(공유 허용, 프로젝트 내 중복만 차단).
--
-- 부분 유니크로 정합성 backstop을 둔다: 기존 행은 content_hash가 NULL이라 충돌하지 않는다.
-- (register가 존재 여부를 먼저 조회해 clean 409를 내고, 이 인덱스는 동시 업로드 경합의 방어선이다.)

ALTER TABLE project_document ADD COLUMN content_hash VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uk_project_document_project_hash
    ON project_document (project_id, content_hash)
    WHERE content_hash IS NOT NULL;
