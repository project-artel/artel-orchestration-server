-- 추천 TC 의 도착 화면(ARTEL-1312). 씬 전이 추천은 어디로 가는지 알고 있다 — 저장할 때 TC 의 도착 화면으로 넣는다.
ALTER TABLE case_recommendation ADD COLUMN arrives_at TEXT;
