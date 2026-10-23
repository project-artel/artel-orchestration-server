package kr.artel.orchestration.demonstration

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kr.artel.orchestration.common.error.*
import kr.artel.orchestration.knowledge.dto.KnowledgeIngestItem
import kr.artel.orchestration.knowledge.entity.KnowledgeScope
import kr.artel.orchestration.knowledge.entity.KnowledgeSource
import kr.artel.orchestration.knowledge.service.KnowledgeService
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.project.storage.UrlAudience
import kr.artel.orchestration.settings.config.AgentServerProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import kotlinx.coroutines.withTimeout
import java.util.UUID

@Service
class VerificationProcedureService(private val repo: DemonstrationRepository, private val service: DemonstrationService,
    private val mapper: ObjectMapper, private val storage: DocumentStorage, private val transactions: TransactionalOperator,
    private val knowledge: KnowledgeService, private val properties: DemonstrationProperties, agent: AgentServerProperties) {
    private val client=WebClient.builder().baseUrl(agent.baseUrl.trimEnd('/')).build()
    suspend fun extract(id: UUID,userId: Long): ProcedureRevision {
        service.owned(id,userId)
        val row=service.settle(id)
        val hash=row.sourceHash ?: throw ConflictException("시연 결과와 화면 저장을 기다려 주세요.")
        val old=repo.latest(id)
        if(old?.sourceHash == hash && old.status in setOf("DRAFT","APPROVED")) return view(old)
        val events=sourceEvents(row)
        if(events.none { it.kind == "ANNOTATION" } || events.none { it.kind == "EVIDENCE" }) throw BadRequestException("판정과 화면을 먼저 기록해 주세요.")
        val extractionEvents=extractionEvents(events)
        val annotatedIds=events.filter { it.kind == "ANNOTATION" }
            .map { mapper.readValue(it.payloadJson,DemonstrationAnnotation::class.java).evidenceEventId }.toSet()
        val images=events.filter { it.kind == "EVIDENCE" }
        val selectedImages=(images.filter { it.id in annotatedIds } + images.take(1) + images.takeLast(3)).distinctBy { it.id }
        if(selectedImages.size > 12) throw BadRequestException("한 시연에서 정리할 수 있는 판정 화면 수를 초과했습니다. 시연을 나누어 기록해 주세요.")
        val request=DemonstrationExtractRequest(id.toString(),hash,mapper.readValue(row.caseJson,DemonstrationCase::class.java),
            extractionEvents,
            selectedImages.map {
                val e=mapper.readValue(it.payloadJson,DemonstrationEvidence::class.java)
                ExtractionImage(it.id,storage.presignDownload(e.objectKey,"evidence.png",audience=UrlAudience.SERVER).url)
            })
        val result=withTimeout(150000) { client.post().uri("/demonstrations/extract").bodyValue(request).retrieve().awaitBody<DemonstrationExtractResponse>() }
        if(result.sourceHash != hash) throw ConflictException("시연 원본이 일치하지 않습니다.")
        result.procedure.validate(events.map { it.id }.toSet())
        return transactions.executeAndAwait {
            val current=repo.find(id,true)!!
            if(current.sourceHash != hash) throw ConflictException("시연 원본이 변경되었습니다. 다시 정리해 주세요.")
            val latest=repo.latest(id)
            if(latest != null && latest.sourceHash == hash && latest.status in setOf("DRAFT","APPROVED")) return@executeAndAwait view(latest)
            view(repo.insertProcedure(id,(latest?.revision ?: 0)+1,hash,mapper.writeValueAsString(result.procedure)))
        }
    }
    suspend fun edit(id: UUID,userId: Long,request: ProcedureEdit): ProcedureRevision {
        service.owned(id,userId)
        if(mapper.writeValueAsString(request.procedure).length > 100000) throw BadRequestException("검증 절차가 너무 깁니다.")
        return transactions.executeAndAwait {
            val row=repo.find(id,true)!!
            val latest=latest(row,request.expectedRevision)
            request.procedure.validate(sourceEvents(row).map { it.id }.toSet())
            retireProcedures(repo,id)
            view(repo.insertProcedure(id,latest.revision+1,row.sourceHash!!,mapper.writeValueAsString(request.procedure)))
        }
    }
    suspend fun approve(id: UUID,userId: Long,version: Int): ProcedureRevision {
        val owned=service.owned(id,userId)
        return transactions.executeAndAwait {
            repo.db.sql("SELECT id FROM test_case WHERE id=:id FOR UPDATE").bind("id",owned.testCaseId).fetch().one().awaitSingle()
            val row=repo.find(id,true)!!
            val latest=latest(row,version)
            if(latest.status == "APPROVED") return@executeAndAwait view(latest)
            if(latest.status != "DRAFT") throw ConflictException("현재 초안만 승인할 수 있습니다.")
            val tc=service.caseOf(row.projectId,row.testCaseId,row.gameBuildId)
            if(sha256(mapper.writeValueAsString(tc)) != row.caseHash) throw ConflictException("TC 내용이 변경되었습니다. 새 시연이 필요합니다.")
            val procedure=view(latest).procedure
            val events=sourceEvents(row)
            procedure.validate(events.map { it.id }.toSet(),true)
            val unresolved=repo.db.sql("SELECT count(*) AS n FROM demonstration_event WHERE session_id=:id AND command_status IN ('REQUESTED','UNKNOWN')")
                .bind("id",id).map { it.long("n") }.one().awaitSingle()
            if(unresolved > 0) throw ConflictException("적용 여부를 확인하지 못한 입력이 있습니다. 새 시연을 기록해 주세요.")
            if(events.none { it.kind == "ANNOTATION" } || events.none { it.kind == "EVIDENCE" }) throw BadRequestException("화면과 판정 근거가 필요합니다.")
            for(event in events.filter { it.kind == "EVIDENCE" }) {
                val evidence=mapper.readValue(event.payloadJson,DemonstrationEvidence::class.java)
                if(storage.head(evidence.objectKey).awaitSingleOrNull() == null)
                    throw ConflictException("저장된 화면을 찾을 수 없습니다. 화면을 다시 기록해 주세요.")
            }
            // 같은 TC/build의 이전 승인본을 대체한다. 이전 실행의 snapshot은 그대로 남는다.
            val previous=repo.db.sql("""SELECT DISTINCT s.id FROM demonstration_session s JOIN verification_procedure p ON p.session_id=s.id
                WHERE s.project_id=:p AND s.test_case_id=:c AND s.game_build_id=:b AND p.status='APPROVED' AND s.id<>:id""")
                .bind("p",row.projectId).bind("c",row.testCaseId).bind("b",row.gameBuildId).bind("id",id)
                .map { it.get("id",UUID::class.java)!! }.all().collectList().awaitSingle()
            for (previousId in previous) retireProcedures(repo,previousId)
            repo.db.sql("UPDATE verification_procedure SET status='APPROVED',approved_by=:u WHERE id=:id")
                .bind("u",userId).bind("id",latest.id).fetch().rowsUpdated().awaitSingle()
            knowledge.store(row.projectId,KnowledgeScope.PRODUCTION,KnowledgeSource.DEMONSTRATION,latest.id,row.sourceHash,
                listOf(KnowledgeIngestItem("OBJECTIVE",procedure.title,"TC ${row.testCaseId}, build ${row.gameBuildId}, procedure ${latest.id}/${latest.revision}\n${latest.procedureJson}")))
            view(latest.copy(status="APPROVED"))
        }
    }
    suspend fun revoke(id: UUID,userId: Long,version: Int): ProcedureRevision {
        service.owned(id,userId)
        return transactions.executeAndAwait {
            val row=repo.find(id,true)!!
            val latest=latest(row,version)
            if(latest.status != "APPROVED") throw ConflictException("승인된 절차만 철회할 수 있습니다.")
            retireProcedures(repo,id)
            repo.db.sql("UPDATE verification_procedure SET status='REVOKED' WHERE id=:id").bind("id",latest.id).fetch().rowsUpdated().awaitSingle()
            view(latest.copy(status="REVOKED"))
        }
    }
    suspend fun forCase(projectId: Long,buildId: Long?,tc: DemonstrationCase): ProcedureSnapshot? {
        if(!properties.qaEnabled || buildId == null) return null
        val hash=sha256(mapper.writeValueAsString(tc))
        val result=repo.db.sql("""SELECT p.* FROM verification_procedure p JOIN demonstration_session s ON s.id=p.session_id
            WHERE s.project_id=:p AND s.test_case_id=:c AND s.game_build_id=:b AND s.case_hash=:hash
              AND p.status='APPROVED' AND p.source_hash=s.source_hash ORDER BY p.id DESC LIMIT 1""")
            .bind("p",projectId).bind("c",tc.id).bind("b",buildId).bind("hash",hash).map(repo::procedure).one().awaitSingleOrNull()
            ?: return null
        val value=view(result)
        return ProcedureSnapshot(value.id,value.revision,value.sourceHash,value.procedure)
    }
    suspend fun recordUsage(qaTryId: Long,snapshot: ProcedureSnapshot) {
        repo.db.sql("INSERT INTO qa_procedure_usage(qa_try_id,procedure_id,snapshot_json) VALUES(:qa,:id,:json) ON CONFLICT DO NOTHING")
            .bind("qa",qaTryId).bind("id",snapshot.id).bind("json",mapper.writeValueAsString(snapshot)).fetch().rowsUpdated().awaitSingle()
    }
    private suspend fun sourceEvents(row: DemonstrationRow) = repo.events(row.id).filter { it.id <= (row.sealedEventId ?: 0) && it.kind !in setOf("CONTROL","LATE_RESULT") }
    private suspend fun extractionEvents(events: List<DemonstrationEvent>): List<ExtractionEvent> {
        var characters=0
        return events.map { event ->
            val payload=if(event.kind == "PULSE") {
                val artifact=mapper.readValue(event.payloadJson,DemonstrationPulse::class.java)
                val bytes=storage.read(artifact.objectKey).awaitSingleOrNull()
                    ?: throw ConflictException("시연 관찰 기록을 찾을 수 없습니다.")
                bytes.toString(Charsets.UTF_8).also {
                    if(sha256(it) != artifact.sha256) throw ConflictException("시연 관찰 기록이 변경되었습니다.")
                }
            } else event.payloadJson
            characters+=payload.length
            if(characters > 2000000) throw BadRequestException("정리할 시연 기록이 너무 큽니다. TC의 검증 구간을 나누어 기록해 주세요.")
            ExtractionEvent(event.id,event.kind,payload)
        }
    }
    private suspend fun latest(row: DemonstrationRow,revision: Int): ProcedureRow {
        val latest=repo.latest(row.id) ?: throw NotFoundException("검증 절차가 없습니다.")
        if(latest.revision != revision || latest.sourceHash != row.sourceHash) throw ConflictException("검증 절차가 변경되었습니다. 다시 확인해 주세요.")
        return latest
    }
    private fun view(row: ProcedureRow) = ProcedureRevision(row.id,row.revision,row.status,row.sourceHash,mapper.readValue(row.procedureJson,VerificationProcedure::class.java))
}
