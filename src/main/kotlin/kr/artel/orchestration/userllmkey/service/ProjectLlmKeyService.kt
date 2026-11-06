package kr.artel.orchestration.userllmkey.service

import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.llmmodel.service.userKeyProviderOf
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.qa.service.QaRunKey
import kr.artel.orchestration.qa.service.QaSessionLlmKey
import kr.artel.orchestration.settings.service.SecretCipher
import kr.artel.orchestration.userllmkey.entity.KeySource
import kr.artel.orchestration.userllmkey.entity.LlmKeyProvider
import kr.artel.orchestration.userllmkey.entity.UserLlmKeyEntity
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime

data class ProjectLlmKeySummary(val service: String, val keyHint: String, val linked: Boolean, val ownLink: Boolean)

@Service
class ProjectLlmKeyService(
    private val database: DatabaseClient,
    private val access: ProjectAccessService,
    private val userKeys: UserLlmKeyService,
    private val cipher: SecretCipher,
    private val clock: Clock
) {
    suspend fun list(projectId: Long, userId: Long): List<ProjectLlmKeySummary> {
        access.requireMember(projectId, userId)
        return database.sql(
            """
            SELECT p.service, p.owner_user_id IS NOT NULL AS linked,
                   COALESCE(p.owner_user_id = :userId, FALSE) AS own_link,
                   COALESCE(u.key_hint, p.key_hint) AS key_hint
            FROM project_llm_key p
            LEFT JOIN user_llm_key u ON u.app_user_id = p.owner_user_id AND u.service = p.service
            WHERE p.project_id = :projectId
            ORDER BY p.service
            """
        ).bind("projectId", projectId).bind("userId", userId).map { row, _ ->
            ProjectLlmKeySummary(
                row.get("service", String::class.java)!!,
                row.get("key_hint", String::class.java)!!,
                row.get("linked", Boolean::class.javaObjectType)!!,
                row.get("own_link", Boolean::class.javaObjectType)!!
            )
        }.all().collectList().awaitSingle()
    }

    suspend fun register(projectId: Long, userId: Long, service: String, apiKey: String?) {
        requireOwner(projectId, userId)
        val provider = userKeys.parseProvider(service)
        val key = userKeys.verify(provider, apiKey)
        database.sql(
            """
            INSERT INTO project_llm_key (project_id, service, encrypted_key, key_hint, verified_at)
            VALUES (:projectId, :service, :encryptedKey, :keyHint, :verifiedAt)
            ON CONFLICT (project_id, service) DO UPDATE
            SET encrypted_key = EXCLUDED.encrypted_key, key_hint = EXCLUDED.key_hint,
                verified_at = EXCLUDED.verified_at, owner_user_id = NULL
            """
        ).bind("projectId", projectId).bind("service", service)
            .bind("encryptedKey", cipher.encrypt(key, settingKey(projectId, provider)))
            .bind("keyHint", keyHintOf(key)).bind("verifiedAt", OffsetDateTime.now(clock))
            .fetch().awaitRowsUpdated()
    }

    suspend fun link(projectId: Long, userId: Long, service: String) {
        access.requireMember(projectId, userId)
        userKeys.parseProvider(service)
        val updated = database.sql(
            """
            INSERT INTO project_llm_key (project_id, service, owner_user_id)
            SELECT :projectId, service, app_user_id FROM user_llm_key
            WHERE app_user_id = :userId AND service = :service
            FOR KEY SHARE
            ON CONFLICT (project_id, service) DO UPDATE SET owner_user_id = EXCLUDED.owner_user_id
            WHERE project_llm_key.owner_user_id = EXCLUDED.owner_user_id
            """
        ).bind("projectId", projectId).bind("userId", userId).bind("service", service).fetch().awaitRowsUpdated()
        if (updated == 0L) {
            throw BadRequestException("등록된 개인 키가 있고 프로젝트에 해당 서비스 키가 없어야 연결할 수 있습니다.")
        }
    }

    suspend fun remove(projectId: Long, userId: Long, service: String, ownLinkOnly: Boolean) {
        userKeys.parseProvider(service)
        if (!ownLinkOnly) requireOwner(projectId, userId)
        val query = if (ownLinkOnly) {
            database.sql(
                "DELETE FROM project_llm_key WHERE project_id = :projectId AND service = :service AND owner_user_id = :userId"
            ).bind("userId", userId)
        } else {
            database.sql("DELETE FROM project_llm_key WHERE project_id = :projectId AND service = :service")
        }
        query.bind("projectId", projectId).bind("service", service).fetch().awaitRowsUpdated()
    }

    suspend fun resolve(projectId: Long, userId: Long, model: String?): QaRunKey {
        access.requireMember(projectId, userId)
        val provider = model?.let(::userKeyProviderOf)
            ?: throw LlmKeyMissingException("프로젝트 키로 실행할 수 없는 모델입니다.")
        val apiKey = database.sql(
            """
            SELECT p.owner_user_id, COALESCE(u.encrypted_key, p.encrypted_key) AS encrypted_key
            FROM project_llm_key p
            LEFT JOIN user_llm_key u ON u.app_user_id = p.owner_user_id AND u.service = p.service
            WHERE p.project_id = :projectId AND p.service = :service
            """
        ).bind("projectId", projectId).bind("service", provider.wire).map { row, _ ->
            val owner = row.get("owner_user_id", Long::class.javaObjectType)
            val associatedData = if (owner == null) settingKey(projectId, provider)
                else UserLlmKeyEntity.settingKey(owner, provider)
            requireNotNull(row.get("encrypted_key", String::class.java)) to associatedData
        }.one().map { (encrypted, associatedData) ->
            java.util.Optional.ofNullable(cipher.decrypt(encrypted, associatedData))
        }.defaultIfEmpty(java.util.Optional.empty()).awaitSingle().orElse(null)
            ?: throw LlmKeyMissingException("프로젝트에 사용할 수 있는 ${provider.wire} 키가 없습니다.")
        return QaRunKey(KeySource.PROJECT, QaSessionLlmKey(service = provider.wire, apiKey = apiKey))
    }

    private suspend fun requireOwner(projectId: Long, userId: Long) =
        access.requireOwner(projectId, userId, "프로젝트 소유자만 프로젝트 키를 관리할 수 있습니다.")

    private fun settingKey(projectId: Long, provider: LlmKeyProvider) =
        "project.$projectId.llm.${provider.wire}.api_key"
}
