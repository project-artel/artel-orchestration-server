-- 승인된 Test Case 를 MCP 로 고친 것이면 true. 이전 내용이 남지 않아 거절(삭제)할 수 없다.
ALTER TABLE test_case ADD COLUMN review_edits_approved BOOLEAN NOT NULL DEFAULT FALSE;
