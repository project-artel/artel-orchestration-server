package kr.artel.orchestration.project.repository

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * 초대 대상 후보 검색.
 *
 * `CoroutineCrudRepository` 가 아니라 [DatabaseClient] 를 직접 쓴다. 결과가 어떤 테이블의 행도
 * 아니라 `app_user` 와 `oauth_identity` 를 합친 모양이고, `LIMIT` 을 파라미터로 받아야 하기
 * 때문이다. `@Query` 인터페이스 메서드로 두면 엔티티가 아닌 타입에 매핑하는 규칙을 별도로
 * 맞춰야 해서, 여기서는 읽는 코드를 그대로 두는 편이 짧다.
 */
@Repository
class InvitationSuggestionRepository(
    private val databaseClient: DatabaseClient
) {
    /**
     * 이 프로젝트에 부를 수 있는 사람을 [SuggestionMatch] 가 정한 방식으로 찾는다.
     *
     * 한 줄을 만드는 데 필요한 것 — 사용자 행, 가장 최근 신원의 `login` 과 `avatar_url`, 그리고
     * 제외 규칙 셋 — 을 SQL 한 문장이 모두 읽는다. 행마다 신원이나 멤버십을 따로 읽으면 후보
     * 열 명에 스물한 번의 왕복이 되고, 그것이 `ProjectInvitationService.inviterNamesFor` 가
     * 초대 목록에서 피한 것과 같은 N+1 이다.
     *
     * 빠지는 사람이 둘이다. 이미 멤버인 사람과, 이미 답을 기다리는 초대가 나간 사람이다(ARTEL-774).
     * 이메일이 없거나 확인하지 않은 사람도 이제 후보에 남는다 — 초대는 웹 초대함으로 배달되고,
     * 계정을 대상으로 삼는 초대는 주소를 필요로 하지 않기 때문이다.
     *
     * `login` 은 모든 신원에서 찾지만 응답에는 가장 최근에 로그인한 신원의 것을 낸다. 옛 login 으로
     * 찾은 사람에게 지금 쓰는 이름을 보여 주는 편이, 찾히지 않는 것보다 낫다. 신원이 하나도 없는
     * 계정도 있을 수 있어 `LEFT JOIN LATERAL` 이고, 그때 `login` 과 `avatar_url` 은 null 이다.
     *
     * 정렬은 `match_rank` 가 먼저다 — 정확히 일치한 것을 앞에 둔다. 그 다음은 `nickname` 의
     * 사전순, 그 다음이 `user_tag`, 마지막이 `id` 다. 마지막 열쇠가 없으면 같은 이름을 가진
     * 사람들의 순서가 실행마다 달라져 목록을 다시 열 때마다 흔들린다.
     *
     * @param query 다듬고 소문자로 내린 검색어. 정확 일치 판정과 이메일 전체 일치에 함께 쓴다
     */
    suspend fun search(
        projectId: Long,
        query: String,
        match: SuggestionMatch,
        now: Instant,
        limit: Int
    ): List<SuggestionRow> {
        var statement = databaseClient.sql(searchSql(match))
            .bind("projectId", projectId)
            .bind("query", query)
            .bind("now", now)
            .bind("limit", limit)

        when (match) {
            is SuggestionMatch.Handle -> {
                statement = statement
                    .bind("handleNickname", match.nickname.lowercase())
                    .bind("handleUserTag", match.userTag)
            }

            is SuggestionMatch.Prefix -> statement = statement.bind("prefix", match.pattern)

            SuggestionMatch.EmailOnly -> Unit
        }

        return statement.map(::toRow).all().collectList().awaitSingle()
    }

    private fun toRow(row: Readable, metadata: Any?) = SuggestionRow(
        appUserId = requireNotNull(row.get("app_user_id", java.lang.Long::class.java)).toLong(),
        nickname = requireNotNull(row.get("nickname", String::class.java)),
        userTag = requireNotNull(row.get("user_tag", String::class.java)),
        displayName = requireNotNull(row.get("display_name", String::class.java)),
        login = row.get("login", String::class.java),
        avatarUrl = row.get("avatar_url", String::class.java)
    )
}

/**
 * 이름으로 사람을 고르는 방식. 어느 쪽이든 이메일 전체 일치는 함께 걸린다 —
 * [InvitationSuggestionRepository.search] 의 `WHERE` 가 그 조건을 밖에 두기 때문이다.
 */
sealed interface SuggestionMatch {
    /** `nickname#userTag` 를 붙여 넣었을 때. 두 값을 함께, 정확히 맞춘다. */
    data class Handle(val nickname: String, val userTag: String) : SuggestionMatch

    /** 검색어에 `#` 이 없을 때. `%` 를 escape 한 `LIKE` 패턴을 받는다. */
    data class Prefix(val pattern: String) : SuggestionMatch

    /**
     * `#` 이 있지만 `UserHandle` 이 가르지 못한 형식일 때. 이름으로는 아무것도 맞추지 않고
     * 이메일 전체 일치만 남긴다 — `#` 이 있는 검색어는 접두사 검색을 하지 않기 때문이다.
     */
    data object EmailOnly : SuggestionMatch
}

/** [InvitationSuggestionRepository.search] 한 줄. 어떤 테이블의 행도 아니다. */
data class SuggestionRow(
    val appUserId: Long,
    val nickname: String,
    val userTag: String,
    val displayName: String,
    val login: String?,
    val avatarUrl: String?
)

/**
 * 이름 조건만 갈아 끼운다.
 *
 * **이메일은 통째로 적었을 때만 맞춘다.** 접두사로 열면 소유자가 `a`, `ab`, `abc` 로 글자를 늘려
 * 가며 남의 주소를 훑을 수 있다. 주소 전체를 이미 아는 사람은 그 사람을 그대로 찾을 수 있으므로
 * 잃는 것도 없다. 이름 조건이 접두사인데 이메일만 전체 일치인 것은 그래서이고, 맞춰야 할
 * 불일치가 아니다.
 *
 * **그 전체 일치도 확인을 마친 주소로만 건다(ARTEL-774).** 이름 조건과 달리 email 은 이제
 * nullable 이고 확인 전 주소도 있을 수 있는데, 확인 전 주소까지 전체 일치로 열면 그 주소를
 * 통째로 적어 넣어 "이 주소의 주인이 여기 있는가" 를 확인하는 통로가 된다. 확인을 마친 주소는
 * 이미 그 계정의 소유임이 검증돼 있으므로 같은 문제가 없다.
 */
private fun searchSql(match: SuggestionMatch): String {
    val nameCondition = when (match) {
        is SuggestionMatch.Handle ->
            "(lower(u.nickname) = :handleNickname AND u.user_tag = :handleUserTag)"

        is SuggestionMatch.Prefix -> """(
                lower(u.nickname) LIKE :prefix ESCAPE '\'
             OR lower(u.display_name) LIKE :prefix ESCAPE '\'
             OR EXISTS (
                    SELECT 1 FROM oauth_identity oi
                    WHERE oi.app_user_id = u.id AND lower(oi.login) LIKE :prefix ESCAPE '\'
                )
           )"""

        SuggestionMatch.EmailOnly -> "FALSE"
    }

    return """
        SELECT u.id AS app_user_id,
               u.nickname AS nickname,
               u.user_tag AS user_tag,
               u.display_name AS display_name,
               i.login AS login,
               i.avatar_url AS avatar_url,
               CASE WHEN lower(u.nickname) = :query
                      OR lower(u.display_name) = :query
                      OR (lower(u.email) = :query AND u.email_verified_at IS NOT NULL)
                      OR lower(u.nickname) || '#' || u.user_tag = :query
                      OR EXISTS (
                             SELECT 1 FROM oauth_identity oi
                             WHERE oi.app_user_id = u.id AND lower(oi.login) = :query
                         )
                    THEN 0 ELSE 1 END AS match_rank
        FROM app_user u
        LEFT JOIN LATERAL (
            SELECT login, avatar_url
            FROM oauth_identity
            WHERE app_user_id = u.id
            ORDER BY last_login_at DESC, id DESC
            LIMIT 1
        ) i ON TRUE
        WHERE (
                (lower(u.email) = :query AND u.email_verified_at IS NOT NULL)
                OR $nameCondition
              )
          AND NOT EXISTS (
                SELECT 1 FROM project_member m
                WHERE m.project_id = :projectId AND m.app_user_id = u.id
          )
          -- 대기 중 초대가 이 사람을 이미 가리키면 후보에서 뺀다. 이메일로 나간 초대는
          -- lower(v.email) = lower(u.email) 로, 계정으로 나간 초대는 v.app_user_id = u.id 로
          -- 잡는다. u.email 이 NULL 이면 앞 비교는 NULL 이 되어 걸리지 않으므로 뒤 비교만
          -- 남는데, 그것으로 충분하다.
          AND NOT EXISTS (
                SELECT 1 FROM project_invitation v
                WHERE v.project_id = :projectId
                  AND v.status = 'PENDING'
                  AND v.expires_at > :now
                  AND (lower(v.email) = lower(u.email) OR v.app_user_id = u.id)
          )
        ORDER BY match_rank, lower(u.nickname), u.user_tag, u.id
        LIMIT :limit
    """.trimIndent()
}
