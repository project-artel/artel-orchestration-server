package kr.artel.orchestration.demonstration

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.project.storage.DocumentStorage
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.nio.ByteBuffer
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Service
class DemonstrationEvidenceService(private val repo: DemonstrationRepository,private val service: DemonstrationService,
    private val storage: DocumentStorage,private val mapper: ObjectMapper,private val transactions: TransactionalOperator) {
    suspend fun upload(id: UUID,userId: Long,request: EvidenceUpload): DemonstrationEvent {
        service.owned(id,userId)
        val bytes = decodeEvidence(request)
        val key="demonstrations/$id/${UUID.randomUUID()}.png"
        storage.put(key,bytes,"image/png").awaitSingleOrNull()
        return transactions.executeAndAwait {
            val row=repo.find(id,true)!!
            val event=repo.append(id,"EVIDENCE",mapper.writeValueAsString(DemonstrationEvidence(key,request.capturedAt,request.width,request.height)))
            invalidateAndReseal(row)
            withUrl(event)
        }
    }
    suspend fun annotate(id: UUID,userId: Long,request: DemonstrationAnnotation): DemonstrationEvent {
        service.owned(id,userId)
        validateAnnotation(request)
        return transactions.executeAndAwait {
            val row=repo.find(id,true)!!
            if(repo.events(id).none { it.id == request.evidenceEventId && it.kind == "EVIDENCE" }) throw BadRequestException("이 시연의 화면을 선택해 주세요.")
            val event=repo.append(id,"ANNOTATION",mapper.writeValueAsString(request))
            invalidateAndReseal(row)
            event
        }
    }
    suspend fun detail(row: DemonstrationRow, includeEvents: Boolean = true): DemonstrationDetail {
        val current=service.settle(row.id)
        val events=if(includeEvents) repo.events(row.id).map(::withUrl) else emptyList()
        val procedure=repo.latest(row.id)?.let { ProcedureRevision(it.id,it.revision,it.status,it.sourceHash,mapper.readValue(it.procedureJson,VerificationProcedure::class.java)) }
        return DemonstrationDetail(current.id,current.projectId,current.testCaseId,current.scenarioId,current.gameInstanceId,current.gameBuildId,
            current.status,current.controlReady,current.generation,current.sourceHash,current.createdAt,events,procedure)
    }
    fun withUrl(event: DemonstrationEvent): DemonstrationEvent {
        if(event.kind != "EVIDENCE") return event
        val evidence=mapper.readValue(event.payloadJson,DemonstrationEvidence::class.java)
        return event.copy(payloadJson=mapper.writeValueAsString(evidence.copy(viewUrl=storage.presignDownload(evidence.objectKey,"evidence.png").url)))
    }
    private suspend fun invalidateAndReseal(row: DemonstrationRow) {
        if(repo.events(row.id).size > 10000) throw ConflictException("시연 기록 한도에 도달했습니다.")
        if(row.sourceHash == null) return
        retireProcedures(repo,row.id)
        sealSource(repo,mapper,row)
    }
}

internal suspend fun retireProcedures(repo: DemonstrationRepository,id: UUID) {
    repo.db.sql("""WITH retired AS (
        UPDATE knowledge SET deleted_at=now() WHERE source='DEMONSTRATION' AND source_id IN
        (SELECT id FROM verification_procedure WHERE session_id=:id) AND deleted_at IS NULL
        RETURNING id,project_id,version)
        INSERT INTO knowledge_event(knowledge_id,project_id,event,version,created_at)
        SELECT id,project_id,'DELETE',version,now() FROM retired""")
        .bind("id",id).fetch().rowsUpdated().awaitSingle()
    repo.db.sql("UPDATE verification_procedure SET status='SUPERSEDED' WHERE session_id=:id AND status IN ('DRAFT','APPROVED')")
        .bind("id",id).fetch().rowsUpdated().awaitSingle()
}
internal suspend fun sealSource(repo: DemonstrationRepository,mapper: ObjectMapper,row: DemonstrationRow) {
    val events=repo.events(row.id).filter { it.kind !in setOf("LATE_RESULT","CONTROL") }
    val hash=sha256(row.caseHash + mapper.writeValueAsString(events))
    val last=events.lastOrNull()?.id ?: 0L
    repo.db.sql("INSERT INTO demonstration_source_revision(session_id,source_hash,sealed_event_id) VALUES(:id,:hash,:last) ON CONFLICT DO NOTHING")
        .bind("id",row.id).bind("hash",hash).bind("last",last).fetch().rowsUpdated().awaitSingle()
    repo.db.sql("""UPDATE demonstration_session SET source_hash=:hash,sealed_event_id=:last,
        status=CASE WHEN status='FINALIZING' THEN 'SEALED' ELSE status END WHERE id=:id""")
        .bind("hash",hash).bind("last",last).bind("id",row.id).fetch().rowsUpdated().awaitSingle()
}
internal fun decodeEvidence(request: EvidenceUpload): ByteArray {
    if(request.width !in 1..1920 || request.height !in 1..1080 || request.dataUrl.length > 2800000 ||
        !request.dataUrl.startsWith("data:image/png;base64,")) throw BadRequestException("PNG 화면 크기를 확인해 주세요.")
    val bytes=try { Base64.getDecoder().decode(request.dataUrl.substringAfter(',')) } catch(_: IllegalArgumentException) { throw BadRequestException("PNG 화면을 읽을 수 없습니다.") }
    val signature=byteArrayOf(137.toByte(),80,78,71,13,10,26,10)
    if(bytes.size !in 33..2097152 || !bytes.copyOfRange(0,8).contentEquals(signature) ||
        ByteBuffer.wrap(bytes,8,4).int != 13 || bytes.copyOfRange(12,16).toString(Charsets.US_ASCII) != "IHDR" ||
        ByteBuffer.wrap(bytes,16,8).let { it.int != request.width || it.int != request.height }) throw BadRequestException("PNG 화면 크기가 일치하지 않습니다.")
    val image=try { javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes)) } catch(_: java.io.IOException) { null }
    if(image == null || image.width != request.width || image.height != request.height) throw BadRequestException("PNG 화면을 읽을 수 없습니다.")
    if(request.capturedAt > Instant.now().plusSeconds(60)) throw BadRequestException("화면 시각을 확인해 주세요.")
    return bytes
}
internal fun validateAnnotation(a: DemonstrationAnnotation) {
    if(a.outcome !in setOf("PASS","FAIL","INCONCLUSIVE") || listOf(a.observed,a.expected,a.explanation).any { it.length > 10000 })
        throw BadRequestException("판정과 설명 길이를 확인해 주세요.")
    a.region?.let { r -> if(listOf(r.x,r.y,r.width,r.height).any { !it.isFinite() || it !in 0.0..1.0 } || r.width <= 0 || r.height <= 0 || r.x+r.width > 1.000001 || r.y+r.height > 1.000001)
        throw BadRequestException("화면 안의 영역을 선택해 주세요.") }
}
