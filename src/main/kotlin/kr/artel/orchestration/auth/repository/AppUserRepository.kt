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

    /**
     * 같은 nickname을 쓰는 사용자를 모두 찾는다. 새 user_tag를 배정할 때 이미 나간 번호를 읽는
     * 자리다. 한 이름을 쓰는 사람 수만큼만 나오므로 전부 읽어도 된다.
     */
    fun findByNickname(nickname: String): Flow<AppUserEntity>

    /**
     * `nickname#user_tag` 한 쌍으로 사용자를 찾는다. `uk_app_user_nickname_user_tag`가 그 쌍을
     * 한 행으로 만들어 주므로 결과는 한 명이거나 없다.
     */
    suspend fun findByNicknameAndUserTag(nickname: String, userTag: String): AppUserEntity?
}
