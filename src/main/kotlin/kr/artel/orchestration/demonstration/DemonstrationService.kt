package kr.artel.orchestration.demonstration

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kr.artel.orchestration.common.error.*
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.sdk.dto.ActionItemDto
import kr.artel.orchestration.sdk.dto.ActionResponseDto
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.testcase.service.CaseScope
import kr.artel.orchestration.testcase.service.TestCaseReader
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import kr.artel.orchestration.testscenario.entity.toDraft
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@Service
class DemonstrationService(
    private val repo: DemonstrationRepository,
    private val access: ProjectAccessService,
    private val instances: GameInstanceRepository,
    private val cases: TestCaseReader,
    private val scenarios: TestScenarioRepository,
    private val sessions: SessionManager,
    private val runtime: DemonstrationRuntime,
    private val mapper: ObjectMapper,
    private val properties: DemonstrationProperties,
    private val transactions: TransactionalOperator,
    private val storage: kr.artel.orchestration.project.storage.DocumentStorage,
) {
    suspend fun instances(projectId: Long, userId: Long): List<DemonstrationInstance> {
        access.requireMember(projectId,userId)
        if (!properties.enabled) return emptyList()
        return instances.findAllForMember(projectId,userId).toList().map {
            val id = requireNotNull(it.id)
            val connection = sessions.getSession(id.toString())
            DemonstrationInstance(id,it.name,it.lastGameBuildId,connection != null,
                runtime.connection(id.toString(),connection?.id)?.capabilities?.protocolVersion)
        }
    }
    suspend fun create(projectId: Long, userId: Long, request: DemonstrationStart): DemonstrationRow {
        if (!properties.enabled) throw ConflictException("시연 기능이 꺼져 있습니다.")
        access.requireMember(projectId,userId)
        val instance = instances.findAccessibleById(request.gameInstanceId,projectId,userId) ?: throw NotFoundException("게임을 찾을 수 없습니다.")
        val build = instance.lastGameBuildId ?: throw ConflictException("게임 build가 필요합니다.")
        val tc = caseOf(projectId,request.testCaseId,build)
        val scenario = scenarios.findById(request.scenarioId)?.takeIf { it.projectId == projectId } ?: throw NotFoundException("Scenario를 찾을 수 없습니다.")
        if (scenario.toDraft(mapper).steps.none { it.caseId == request.testCaseId }) throw BadRequestException("Scenario에 포함된 TC를 선택해 주세요.")
        val connection = connected(instance.id!!)
        val caseJson = mapper.writeValueAsString(tc)
        val now = Instant.now()
        val row = transactions.executeAndAwait {
            repo.lockInstance(instance.id)
            if (repo.active(instance.id) != null || repo.qaActive(instance.id)) throw ConflictException("게임을 다른 실행에서 사용하고 있습니다.")
            DemonstrationRow(UUID.randomUUID(),projectId,tc.id,request.scenarioId,instance.id,build,userId,repo.nextRequestId(),
                connection.connectionId,caseJson,sha256(caseJson),"RECORDING",false,now.plusSeconds(8),null,null,now,null).also { repo.insert(it) }
        }
        return row
    }
    suspend fun readable(projectId: Long, userId: Long, id: UUID): DemonstrationRow {
        access.requireMember(projectId,userId)
        return repo.find(id)?.takeIf { it.projectId == projectId } ?: throw NotFoundException("시연을 찾을 수 없습니다.")
    }
    suspend fun owned(id: UUID, userId: Long): DemonstrationRow {
        val row = repo.find(id) ?: throw NotFoundException("시연을 찾을 수 없습니다.")
        access.requireMember(row.projectId,userId)
        if (row.ownerId != userId) throw ForbiddenException("시연을 시작한 사용자만 변경할 수 있습니다.")
        return row
    }
    suspend fun list(projectId: Long,userId: Long,caseId: Long): List<DemonstrationRow> {
        access.requireMember(projectId,userId)
        return repo.list(projectId,caseId)
    }
    suspend fun caseOf(projectId: Long,caseId: Long,buildId: Long): DemonstrationCase {
        val tc = cases.byIds(listOf(caseId),CaseScope.Version(buildId))[caseId]?.takeIf { it.projectId == projectId }
            ?: throw NotFoundException("이 build에 TC가 없습니다.")
        return DemonstrationCase(caseId,tc.scene,tc.precondition,tc.step,tc.expectedValue)
    }
    fun connected(instanceId: Long): ConnectedDemonstrationSdk = runtime.connection(instanceId.toString(),sessions.getSession(instanceId.toString())?.id)
        ?: throw ConflictException("시연 입력을 지원하는 SDK 연결이 필요합니다.")
    suspend fun acquire(id: UUID,userId: Long) {
        if (!properties.enabled) throw ConflictException("시연 기능이 꺼져 있습니다.")
        val row = owned(id,userId)
        ensureLive(row)
        sessions.send(row.gameInstanceId.toString(),DemonstrationControl(id.toString(),row.generation,"ACQUIRE"))
    }
    suspend fun renew(id: UUID,userId: Long) {
        val row = owned(id,userId)
        ensureLive(row)
        if (!properties.enabled) { stop(id,userId,true); throw ConflictException("시연 기능이 꺼졌습니다.") }
        repo.db.sql("UPDATE demonstration_session SET lease_expires_at=now()+interval '8 seconds' WHERE id=:id AND status='RECORDING' AND lease_expires_at>now()")
            .bind("id",id).fetch().rowsUpdated().awaitSingle().also { if(it != 1L) throw ConflictException("시연 제어권이 만료되었습니다.") }
        sessions.send(row.gameInstanceId.toString(),DemonstrationControl(id.toString(),row.generation,"RENEW"))
    }
    suspend fun controlResult(instanceId: Long,connectionId: String,result: DemonstrationControlResult) {
        val id = runCatching { UUID.fromString(result.sessionId) }.getOrNull() ?: return
        val row = repo.find(id) ?: return
        if (row.gameInstanceId != instanceId || row.sdkConnectionId != connectionId || row.generation != result.generation) return
        if (result.operation == "ACQUIRE") {
            repo.db.sql("UPDATE demonstration_session SET control_ready=:ready WHERE id=:id AND status='RECORDING' AND lease_expires_at>now()")
                .bind("ready",result.accepted).bind("id",id).fetch().rowsUpdated().awaitSingle()
            if (!result.accepted) stop(id,row.ownerId,true)
        }
        repo.append(id,"CONTROL",mapper.writeValueAsString(result))
    }
    suspend fun input(id: UUID,userId: Long,input: DemonstrationInput): DemonstrationEvent {
        val row = owned(id,userId)
        ensureLive(row)
        if (!row.controlReady || !properties.enabled) throw ConflictException("게임의 제어권 응답을 기다려 주세요.")
        val method = input.method ?: throw BadRequestException("입력 method가 필요합니다.")
        val sequence = input.sequence?.takeIf { it > 0 } ?: throw BadRequestException("입력 sequence가 필요합니다.")
        validateInput(method,input.params,connected(row.gameInstanceId).capabilities.methods)
        val requestId = repo.nextRequestId()
        val event = transactions.executeAndAwait {
            repo.lockInstance(row.gameInstanceId)
            val current = repo.find(id,true)!!
            ensureLive(current)
            val count = repo.db.sql("SELECT count(*) AS n FROM demonstration_event WHERE session_id=:id").bind("id",id).map { it.long("n") }.one().awaitSingle()
            if(count >= 10000) throw ConflictException("시연 기록 한도에 도달했습니다. 기록을 종료해 주세요.")
            val previous = repo.db.sql("SELECT coalesce(max(command_sequence),0) AS n FROM demonstration_event WHERE session_id=:id")
                .bind("id",id).map { it.long("n") }.one().awaitSingle()
            if (sequence != previous+1) throw ConflictException("입력 순서가 맞지 않습니다. 시연을 다시 시작해 주세요.")
            val pending = repo.db.sql("SELECT count(*) AS n FROM demonstration_event WHERE session_id=:id AND command_status='REQUESTED'")
                .bind("id",id).map { it.long("n") }.one().awaitSingle()
            if(pending >= 32) throw TooManyRequestsException("게임의 입력 처리를 기다려 주세요.",1)
            repo.append(id,"INPUT",mapper.writeValueAsString(DemonstrationCommandRecord(requestId,sequence,method,input.params)),requestId,sequence)
        }
        // 소유권을 먼저 commit해야 즉시 도착한 SDK 응답도 시연 기록으로 찾을 수 있다.
        try {
            sessions.sendAction(row.gameInstanceId.toString(),ActionResponseDto(id=requestId,
                actions=listOf(ActionItemDto(id=requestId,method=method,params=input.params)),
                demonstration=DemonstrationIdentity(id.toString(),row.generation)))
        } catch (e: Exception) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                repo.db.sql("UPDATE demonstration_event SET command_status='UNKNOWN' WHERE id=:id AND command_status='REQUESTED'")
                    .bind("id",event.id).fetch().rowsUpdated().awaitSingle()
            }
            throw e
        }
        return event
    }
    suspend fun awaitInputCompletion(id: UUID, userId: Long, eventId: Long) {
        owned(id,userId)
        // 저장된 결과를 읽으므로 SDK 응답이 대기 등록보다 빨라도 놓치지 않는다.
        val completed = withTimeoutOrNull(2500) {
            while (true) {
                val row = repo.find(id) ?: return@withTimeoutOrNull false
                if (!properties.enabled || row.status != "RECORDING" || !row.controlReady ||
                    row.leaseExpiresAt <= Instant.now() || sessions.getSession(row.gameInstanceId.toString())?.id != row.sdkConnectionId)
                    return@withTimeoutOrNull false
                val status = repo.db.sql("SELECT command_status FROM demonstration_event WHERE id=:event AND session_id=:session AND kind='INPUT'")
                    .bind("event",eventId).bind("session",id).map { it.text("command_status") }.one().awaitSingleOrNull()
                when (status) {
                    "APPLIED", "FAILED" -> return@withTimeoutOrNull true
                    "REQUESTED" -> delay(40)
                    else -> return@withTimeoutOrNull false
                }
            }
            @Suppress("UNREACHABLE_CODE") false
        } ?: false
        if (completed) return
        withContext(NonCancellable) {
            transactions.executeAndAwait {
                repo.find(id,true)
                repo.db.sql("UPDATE demonstration_event SET command_status='UNKNOWN' WHERE id=:event AND session_id=:session AND command_status='REQUESTED'")
                    .bind("event",eventId).bind("session",id).fetch().rowsUpdated().awaitSingle()
            }
            stop(id,userId,true)
        }
        throw ConflictException("게임의 입력 결과를 확인하지 못해 시연 연결이 종료되었습니다.")
    }
    suspend fun actionResult(instanceId: Long,payload: String): Boolean {
        val envelope = runCatching { mapper.readValue(payload,DemonstrationResultEnvelope::class.java) }.getOrNull() ?: return false
        val requestId = envelope.requestId ?: return false
        val owner = repo.command(instanceId,requestId) ?: return false
        transactions.executeAndAwait {
            val row = repo.find(owner.sessionId,true) ?: return@executeAndAwait
            val current = repo.command(instanceId,requestId) ?: return@executeAndAwait
            if (current.commandStatus != "REQUESTED") {
                if(current.commandStatus == "UNKNOWN") repo.append(row.id,"LATE_RESULT",payload)
                return@executeAndAwait
            }
            val status = demonstrationResultStatus(mapper.readTree(payload),requestId)
            repo.db.sql("UPDATE demonstration_event SET command_status=:status WHERE id=:id AND command_status='REQUESTED'")
                .bind("status",status).bind("id",owner.eventId).fetch().rowsUpdated().awaitSingle()
            repo.append(row.id,if(row.sourceHash == null) "ACTION_RESULT" else "LATE_RESULT",payload)
        }
        return true
    }
    suspend fun pulse(instanceId: Long,payload: String) {
        val row = repo.active(instanceId) ?: return
        val bytes=payload.toByteArray(Charsets.UTF_8)
        if(bytes.size > 2097152) {
            stop(row.id,row.ownerId,true)
            return
        }
        val objectKey="demonstrations/${row.id}/pulse/${UUID.randomUUID()}.json"
        storage.put(objectKey,bytes,"application/json").awaitSingleOrNull()
        transactions.executeAndAwait {
            val current = repo.find(row.id,true) ?: return@executeAndAwait
            if(current.status != "RECORDING") return@executeAndAwait
            // 원본 `pulse`는 object storage에 보존하고 DB에는 위치와 digest를 남긴다.
            val n = repo.db.sql("SELECT count(*) AS n FROM demonstration_event WHERE session_id=:id").bind("id",row.id).map { it.long("n") }.one().awaitSingle()
            if(n < 10000) repo.append(row.id,"PULSE",mapper.writeValueAsString(DemonstrationPulse(objectKey,sha256(payload),bytes.size)))
        }
    }
    suspend fun stop(id: UUID,userId: Long,interrupted: Boolean = false): DemonstrationRow {
        val row = owned(id,userId)
        transactions.executeAndAwait {
            repo.lockInstance(row.gameInstanceId)
            repo.db.sql("""UPDATE demonstration_session SET status=:status,control_ready=false,stopped_at=now()
                WHERE id=:id AND status='RECORDING'""")
                .bind("id",id).bind("status",if(interrupted) "INTERRUPTED" else "FINALIZING").fetch().rowsUpdated().awaitSingle()
        }
        // SDK RELEASE가 유실되더라도 8초 lease 만료 전에 다른 실행이 시작되지 않도록 DB 만료 시각을 유지한다.
        if(sessions.getSession(row.gameInstanceId.toString())?.id == row.sdkConnectionId) {
            try { sessions.send(row.gameInstanceId.toString(),DemonstrationControl(id.toString(),row.generation,"RELEASE")) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { /* SDK의 monotonic lease가 입력을 해제한다. */ }
        }
        return repo.find(id)!!
    }
    suspend fun settle(id: UUID): DemonstrationRow = transactions.executeAndAwait {
        var row = repo.find(id,true) ?: throw NotFoundException("시연을 찾을 수 없습니다.")
        if(row.status == "RECORDING" && (row.leaseExpiresAt <= Instant.now() || sessions.getSession(row.gameInstanceId.toString())?.id != row.sdkConnectionId)) {
            repo.db.sql("UPDATE demonstration_session SET status='INTERRUPTED',control_ready=false,stopped_at=now() WHERE id=:id")
                .bind("id",id).fetch().rowsUpdated().awaitSingle()
            row=repo.find(id)!!
        }
        if(row.status in setOf("FINALIZING","INTERRUPTED") && row.sourceHash == null && row.stoppedAt!!.plusSeconds(10) <= Instant.now()) {
            repo.db.sql("UPDATE demonstration_event SET command_status='UNKNOWN' WHERE session_id=:id AND command_status='REQUESTED'")
                .bind("id",id).fetch().rowsUpdated().awaitSingle()
            sealSource(repo,mapper,row)
        }
        repo.find(id)!!
    }
    private fun ensureLive(row: DemonstrationRow) {
        if(row.status != "RECORDING" || row.leaseExpiresAt <= Instant.now() || connected(row.gameInstanceId).connectionId != row.sdkConnectionId)
            throw ConflictException("시연 연결이 종료되었습니다. 새 시연을 시작해 주세요.")
    }
}

internal fun demonstrationResultStatus(tree: com.fasterxml.jackson.databind.JsonNode,requestId: Long): String {
    val results=tree.path("results")
    if(!results.isArray || results.size() != 1) return "UNKNOWN"
    val result=results[0]
    if(!result.path("id").isIntegralNumber || result.path("id").asLong() != requestId || !result.path("success").isBoolean) return "UNKNOWN"
    return if(result.path("success").asBoolean() && result.path("error").asText("").isEmpty()) "APPLIED" else "FAILED"
}

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
internal fun validateInput(method: String,params: List<com.fasterxml.jackson.databind.JsonNode>,supported: List<String>) {
    if(method !in supported) throw BadRequestException("지원하지 않는 입력입니다.")
    val valid = when(method) {
        "move_mouse" -> params.size == 2 && params.all { it.isNumber && it.asDouble().isFinite() && it.asDouble() in 0.0..1.0 }
        "mouse_down","mouse_up" -> params.size == 1 && params[0].isInt && params[0].asInt() in 0..2
        "key_down","key_up" -> params.size == 1 && params[0].isTextual && params[0].asText().matches(Regex("[A-Za-z][A-Za-z0-9]{0,30}"))
        "enter_text" -> params.size == 2 && params[0].isIntegralNumber && params[1].isTextual && params[1].asText().length <= 2000
        else -> false
    }
    if(!valid) throw BadRequestException("입력 인자를 확인해 주세요.")
}
