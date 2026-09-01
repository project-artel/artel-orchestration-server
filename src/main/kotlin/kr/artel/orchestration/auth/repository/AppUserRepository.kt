package kr.artel.orchestration.auth.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.auth.entity.AppUserEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface AppUserRepository : CoroutineCrudRepository<AppUserEntity, Long> {

    /**
     * 이메일로 사용자를 찾는다. `app_user.email`에 unique 제약이 없어 **여러 행이 나올 수 있다**.
     *
     * 인증에는 쓰지 않는다. 이메일로 사용자를 찾아 그 사람의 것으로 삼는 방향은, 같은 이메일을 가진
     * 행이 여럿일 때 남의 것을 가져가는 길이 된다. 초대 수락은 반대 방향이다 — 로그인한 사용자의
     * 이메일이 초대의 이메일과 맞는지 본다.
     *
     * 지금 쓰이는 곳은 "이 이메일을 가진 사람이 이미 이 프로젝트의 멤버인가"를 초대 전에 확인하는
     * 한 자리뿐이고, 그 확인은 최선을 다하는 것일 뿐 보장이 아니다.
     */
    fun findByEmailIgnoreCase(email: String): Flow<AppUserEntity>
}
