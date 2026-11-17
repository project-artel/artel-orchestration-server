-- 소개 사이트(artel.kr)의 Wishlist 버튼으로 방문자가 남긴 이메일을 둔다. `POST /wishlist` 가 행을 만든다.
--
-- 개인정보 수집·이용에 동의한 요청만 행이 된다. 동의 시각(`consented_at`)은 서버가 요청을 받은 시각이다.
--
-- 방문자 IP 주소는 저장하지 않는다. 횟수 한도를 세는 데만 쓰고 메모리에만 있다(`WishlistSignupLimiter`).
--
-- 같은 주소를 다시 보내면 아무것도 바꾸지 않는다(`uk_wishlist_signup_email`). 응답도 처음과 같아서, 응답만으로는
-- 그 주소가 이미 있는지 알 수 없다.
CREATE TABLE IF NOT EXISTS wishlist_signup (
    id BIGSERIAL PRIMARY KEY,
    -- 앞뒤 공백을 지우고 소문자로 정규화해 넣는다. 254 는 SMTP 가 실제로 받는 주소 길이의 상한이다.
    email VARCHAR(254) NOT NULL,
    -- 버튼을 누른 화면의 언어. 보내지 않았으면 null 이다.
    locale VARCHAR(8),
    -- 개인정보 수집·이용 동의 시각. 같은 주소를 다시 보내도 처음 동의한 시각을 그대로 둔다.
    consented_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- 주소 주인이 확인 메일의 링크를 누른 시각. 아직 아무것도 채우지 않는다. 뒤에 double opt-in 을 붙이면 그 흐름이 채운다.
    verified_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wishlist_signup_email UNIQUE (email),
    -- 대소문자만 다른 두 행이 unique 제약을 빠져나가지 못하게 정규화를 스키마에서도 건다.
    CONSTRAINT ck_wishlist_signup_email_normalized CHECK (email = lower(btrim(email))),
    CONSTRAINT ck_wishlist_signup_locale CHECK (locale IS NULL OR locale IN ('ko', 'en'))
);
