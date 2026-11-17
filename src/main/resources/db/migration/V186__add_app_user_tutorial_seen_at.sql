-- 처음 쓰는 사용자에게만 시작 튜토리얼을 자동으로 띄우기 위한 표시(ARTEL-1462).
--
-- 화면이 브라우저 저장소만 보면 이미 쓰던 사람도 새 브라우저 · 새 기기에서 또 튜토리얼을 받는다.
-- 그래서 "봤다"는 사실을 계정에 둔다. 끝까지 봤든 건너뛰었든 같은 값이다 — 둘 다 다시 자동으로
-- 띄우지 않는다는 뜻이고, 다시 보려면 사용자가 "튜토리얼 다시 보기"를 누른다.

-- 튜토리얼을 마치거나 건너뛴 시각. NULL 이면 아직 보지 않은 사용자다.
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS tutorial_seen_at TIMESTAMP WITH TIME ZONE;

-- 이 변경 전에 가입한 사용자는 이미 서비스를 써 본 사람이다. 다음 배포와 함께 갑자기 튜토리얼이
-- 뜨지 않도록 본 것으로 채운다. 시각은 가입 시각이 아니라 이 변경이 들어간 시각이다 — 실제로 본
-- 때가 아니라 "보지 않아도 되는 사람"으로 정한 때이기 때문이다.
UPDATE app_user SET tutorial_seen_at = CURRENT_TIMESTAMP WHERE tutorial_seen_at IS NULL;
