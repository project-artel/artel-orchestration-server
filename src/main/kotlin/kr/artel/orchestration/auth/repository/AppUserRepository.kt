package kr.artel.orchestration.auth.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.auth.entity.AppUserEntity
import org.springframework.data.r2dbc.repository.Query
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

    /**
     * 이 주소를 확인까지 마친 계정. `uk_app_user_verified_email` 덕분에 최대 한 건이지만, 그
     * index 는 이 branch 가 만든 것이라 Flow 로 둔다 — 개수를 타입으로 주장하면 나중에 index 를
     * 손댔을 때 조용히 틀린다.
     *
     * [findByEmailIgnoreCase] 와 달리 확인되지 않은 행을 세지 않는다. 확인되지 않은 주소는 아직
     * 아무것도 주장하지 않으므로, 다른 사람이 그 주소를 확인하는 것을 막을 근거가 못 된다.
     */
    @Query(
        """
        SELECT * FROM app_user
        WHERE lower(email) = lower(:email) AND email_verified_at IS NOT NULL
        """
    )
    fun findVerifiedByEmail(email: String): Flow<AppUserEntity>
}
