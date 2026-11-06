package kr.artel.orchestration.support

import kr.artel.orchestration.auth.entity.AppUserEntity
import kr.artel.orchestration.auth.entity.MAX_NICKNAME_LENGTH
import java.time.Instant
import java.util.UUID

/**
 * 이름이 검증 대상이 아닌 테스트가 쓰는 `app_user` 한 행. 필요한 것은 id 뿐인 자리에 쓴다.
 *
 * nickname에 무작위 꼬리를 붙이는 것은 `uk_app_user_nickname_user_tag` 때문이다. 스위트 전체가
 * 컨테이너 하나를 공유하므로, 여러 테스트가 같은 이름으로 행을 넣으면 user_tag까지 겹쳐 저장이
 * 막힌다. 이름을 검증하는 테스트는 이 helper를 쓰지 말고 직접 값을 정한다.
 */
fun testAppUser(displayName: String, now: Instant = Instant.now()) = AppUserEntity(
    displayName = displayName,
    nickname = "$displayName-${UUID.randomUUID()}".take(MAX_NICKNAME_LENGTH),
    userTag = "0000",
    createdAt = now,
    updatedAt = now
)
