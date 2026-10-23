package kr.artel.orchestration.demonstration

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.awaitSingle
import kr.artel.orchestration.auth.web.CurrentUserId
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/projects/{projectId}/demonstrations")
class DemonstrationController(private val service: DemonstrationService,private val evidence: DemonstrationEvidenceService,
    private val procedures: VerificationProcedureService,private val mapper: ObjectMapper) {
    @GetMapping("/instances")
    suspend fun instances(@CurrentUserId userId: Long,@PathVariable projectId: Long) = service.instances(projectId,userId)
    @GetMapping
    suspend fun list(@CurrentUserId userId: Long,@PathVariable projectId: Long,@RequestParam testCaseId: Long) =
        service.list(projectId,userId,testCaseId).map { evidence.detail(it,includeEvents=false) }
    @PostMapping
    suspend fun create(@CurrentUserId userId: Long,@PathVariable projectId: Long,@RequestBody request: DemonstrationStart) =
        evidence.detail(service.create(projectId,userId,request))
    @GetMapping("/{id}")
    suspend fun detail(@CurrentUserId userId: Long,@PathVariable projectId: Long,@PathVariable id: UUID) =
        evidence.detail(service.readable(projectId,userId,id))
    @PostMapping("/{id}/stop")
    suspend fun stop(@CurrentUserId userId: Long,@PathVariable projectId: Long,@PathVariable id: UUID): DemonstrationDetail {
        service.readable(projectId,userId,id)
        return evidence.detail(service.stop(id,userId))
    }
    @PostMapping("/{id}/evidence")
    suspend fun upload(@CurrentUserId userId: Long,@PathVariable projectId: Long,@PathVariable id: UUID,request: ServerHttpRequest): DemonstrationEvent {
        service.readable(projectId,userId,id)
        service.owned(id,userId)
        // 이 endpoint만 PNG data URL 크기만큼 읽는다. 공통 JSON decoder 상한은 유지한다.
        val buffer=DataBufferUtils.join(request.body,3000000).awaitSingle()
        val body=try { ByteArray(buffer.readableByteCount()).also { buffer.read(it) } } finally { DataBufferUtils.release(buffer) }
        return evidence.upload(id,userId,mapper.readValue(body,EvidenceUpload::class.java))
    }
    @PostMapping("/{id}/annotations")
    suspend fun annotate(@CurrentUserId userId: Long,@PathVariable projectId: Long,@PathVariable id: UUID,@RequestBody request: DemonstrationAnnotation): DemonstrationEvent {
        service.readable(projectId,userId,id)
        return evidence.annotate(id,userId,request)
    }
    @PostMapping("/{id}/extract")
    suspend fun extract(@CurrentUserId userId: Long,@PathVariable projectId: Long,@PathVariable id: UUID): ProcedureRevision {
        service.readable(projectId,userId,id)
        return procedures.extract(id,userId)
    }
    @PutMapping("/{id}/procedure")
    suspend fun edit(@CurrentUserId userId: Long,@PathVariable projectId: Long,@PathVariable id: UUID,@RequestBody request: ProcedureEdit): ProcedureRevision {
        service.readable(projectId,userId,id)
        return procedures.edit(id,userId,request)
    }
    @PostMapping("/{id}/approve")
    suspend fun approve(@CurrentUserId userId: Long,@PathVariable projectId: Long,@PathVariable id: UUID,@RequestBody request: ProcedureVersionRequest): ProcedureRevision {
        service.readable(projectId,userId,id)
        return procedures.approve(id,userId,request.expectedRevision)
    }
    @PostMapping("/{id}/revoke")
    suspend fun revoke(@CurrentUserId userId: Long,@PathVariable projectId: Long,@PathVariable id: UUID,@RequestBody request: ProcedureVersionRequest): ProcedureRevision {
        service.readable(projectId,userId,id)
        return procedures.revoke(id,userId,request.expectedRevision)
    }
}
