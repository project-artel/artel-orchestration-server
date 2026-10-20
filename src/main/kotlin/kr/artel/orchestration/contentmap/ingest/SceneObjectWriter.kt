package kr.artel.orchestration.contentmap.ingest

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.reactive.awaitSingle
import kr.artel.orchestration.contentmap.entity.SceneObjectComponent
import kr.artel.orchestration.contentmap.entity.SceneObjectEntity
import kr.artel.orchestration.contentmap.evidence.ConditionNode
import kr.artel.orchestration.contentmap.evidence.EvidenceDocumentModel
import kr.artel.orchestration.contentmap.evidence.flatten
import kr.artel.orchestration.contentmap.repository.SceneObjectRepository
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component

/**
 * `evidence` 의 오브젝트를 `scene_object` 로 앉힌다(ARTEL-1059). 적재와 백필이 같은 규칙을 쓰도록 둘 다
 * 이것을 부른다.
 *
 * **호출자의 트랜잭션 안에서 불러야 한다.** 두 쓰기 모두 첫 문장에서 지도 단위 `pg_advisory_xact_lock` 을
 * 잡는다. 백필이 "아직 행이 없다"를 본 뒤 넣는 사이에 같은 지도의 적재가 지우고 넣으면, 둘 다 커밋되어
 * 오브젝트가 두 벌이 된다. 잠금이 그 둘을 한 줄로 세운다. 트랜잭션 밖에서 잡으면 그 문장 하나 동안만
 * 잡혀 아무것도 막지 못한다(`TestCaseFolderService.lockProjectFolders` 와 같은 이유).
 */
@Component
class SceneObjectWriter(
    private val sceneObjects: SceneObjectRepository,
    private val databaseClient: DatabaseClient,
    private val objectMapper: ObjectMapper,
) {

    /** 지도의 오브젝트를 지우고 [model] 의 것으로 다시 넣는다. 적재가 부른다. 넣은 행 수를 돌려준다. */
    suspend fun rewrite(contentMapId: Long, model: EvidenceDocumentModel, sceneIds: Map<String, Long>): Int {
        lockContentMap(contentMapId)
        sceneObjects.deleteByContentMapId(contentMapId)
        return insert(contentMapId, model, sceneIds)
    }

    /**
     * 지도에 오브젝트가 하나도 없을 때만 넣는다. 백필이 부른다. 이미 있으면 null 이다 — 그 사이에 적재가
     * 지금 문서로 채웠다는 뜻이고, 백필이 읽은 문서는 그보다 오래됐다.
     */
    suspend fun fillIfEmpty(contentMapId: Long, model: EvidenceDocumentModel, sceneIds: Map<String, Long>): Int? {
        lockContentMap(contentMapId)
        if (sceneObjects.existsByContentMapId(contentMapId)) return null
        return insert(contentMapId, model, sceneIds)
    }

    private suspend fun insert(contentMapId: Long, model: EvidenceDocumentModel, sceneIds: Map<String, Long>): Int {
        val rows = rowsOf(contentMapId, model, sceneIds)
        rows.forEach { sceneObjects.save(it) }
        return rows.size
    }

    private fun rowsOf(
        contentMapId: Long,
        model: EvidenceDocumentModel,
        sceneIds: Map<String, Long>,
    ): List<SceneObjectEntity> {
        val fieldsByType = fieldsByType(model)
        return model.allObjects.mapNotNull { sceneObject ->
            val sceneId = sceneIds[sceneObject.scene] ?: return@mapNotNull null
            val components = sceneObject.components.map { component ->
                val shortType = component.type.substringAfterLast('.')
                SceneObjectComponent(type = shortType, fields = fieldsByType[shortType].orEmpty())
            }
            SceneObjectEntity(
                contentMapId = contentMapId,
                sceneId = sceneId,
                selector = (sceneObject.selector ?: sceneObject.path).take(SELECTOR_WIDTH),
                active = sceneObject.active,
                components = Json.of(objectMapper.writeValueAsString(components)),
            )
        }
    }

    private suspend fun lockContentMap(contentMapId: Long) {
        databaseClient.sql("SELECT pg_advisory_xact_lock(hashtextextended('scene_object:' || :contentMapId, 0))")
            .bind("contentMapId", contentMapId)
            .fetch().rowsUpdated().awaitSingle()
    }

    companion object {
        /** `scene_object.selector` 의 폭(V114). 넘치면 INSERT 가 거절되고 문서 하나가 통째로 되돌아간다. */
        private const val SELECTOR_WIDTH = 512

        /**
         * 짧은 타입 이름 → 문서 `types` 의 코드가 그 타입에서 읽거나 쓰는 필드, 이름 오름차순.
         *
         * PoC 의 `_fields_by_type` 과 같은 규칙이다. 조건 트리의 `left` 와 효과의 `target` 중
         * `Owner.field...` 모양만 본다 — `(` 가 있으면 호출 결과라 필드가 아니고, `Owner` 가 대문자로
         * 시작하지 않으면 지역변수다. `Player.HpText.text` 는 `Player` 의 `HpText` 다.
         *
         * `unplaced` 는 보지 않는다. PoC 가 `types` 만 보았고, 이 값을 쓰는 planner 는 그 PoC 로 측정됐다.
         */
        internal fun fieldsByType(model: EvidenceDocumentModel): Map<String, List<String>> {
            val found = mutableMapOf<String, MutableSet<String>>()
            fun note(expression: String?) {
                if (expression == null || '.' !in expression || '(' in expression) return
                val owner = expression.substringBefore('.')
                val field = expression.substringAfter('.').substringBefore('.')
                if (owner.isEmpty() || field.isEmpty() || !owner.first().isUpperCase()) return
                found.getOrPut(owner) { mutableSetOf() }.add(field)
            }
            model.types.values.flatten().forEach { record ->
                record.condition.flatten().filterIsInstance<ConditionNode.Test>().forEach { note(it.left) }
                record.effects.forEach { note(it.target) }
            }
            return found.mapValues { (_, fields) -> fields.sorted() }
        }
    }
}
