package kr.artel.orchestration.demonstration

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

data class DemonstrationRow(val id: UUID, val projectId: Long, val testCaseId: Long, val scenarioId: Long,
    val gameInstanceId: Long, val gameBuildId: Long, val ownerId: Long, val generation: Long,
    val sdkConnectionId: String, val caseJson: String, val caseHash: String, val status: String,
    val controlReady: Boolean, val leaseExpiresAt: Instant, val sourceHash: String?, val sealedEventId: Long?,
    val createdAt: Instant, val stoppedAt: Instant?)
data class ProcedureRow(val id: Long, val sessionId: UUID, val revision: Int, val status: String, val sourceHash: String, val procedureJson: String)
data class CommandOwner(val sessionId: UUID, val eventId: Long, val commandStatus: String)

@Repository
class DemonstrationRepository(val db: DatabaseClient) {
    suspend fun lockInstance(id: Long) {
        db.sql("SELECT id FROM game_instance WHERE id=:id FOR UPDATE").bind("id", id).fetch().one().awaitSingle()
    }
    suspend fun nextRequestId(): Long = db.sql("SELECT nextval(pg_get_serial_sequence('qa_log', 'id')) AS id")
        .map { r -> r.long("id") }.one().awaitSingle()
    suspend fun find(id: UUID, lock: Boolean = false): DemonstrationRow? = db.sql(
        "SELECT * FROM demonstration_session WHERE id=:id" + if (lock) " FOR UPDATE" else "")
        .bind("id", id).map(::session).one().awaitSingleOrNull()
    suspend fun active(instanceId: Long): DemonstrationRow? = db.sql(
        "SELECT * FROM demonstration_session WHERE game_instance_id=:id AND lease_expires_at > now() ORDER BY created_at DESC LIMIT 1")
        .bind("id", instanceId).map(::session).one().awaitSingleOrNull()
    suspend fun list(projectId: Long, caseId: Long): List<DemonstrationRow> = db.sql(
        "SELECT * FROM demonstration_session WHERE project_id=:p AND test_case_id=:c ORDER BY created_at DESC LIMIT 50")
        .bind("p", projectId).bind("c", caseId).map(::session).all().collectList().awaitSingle()
    suspend fun insert(r: DemonstrationRow) {
        db.sql("""INSERT INTO demonstration_session(id,project_id,test_case_id,scenario_id,game_instance_id,game_build_id,
            owner_id,generation,sdk_connection_id,case_json,case_hash,status,lease_expires_at)
            VALUES(:id,:p,:c,:s,:i,:b,:u,:g,:conn,:json,:hash,'RECORDING',:expiry)""")
            .bind("id",r.id).bind("p",r.projectId).bind("c",r.testCaseId).bind("s",r.scenarioId)
            .bind("i",r.gameInstanceId).bind("b",r.gameBuildId).bind("u",r.ownerId).bind("g",r.generation)
            .bind("conn",r.sdkConnectionId).bind("json",r.caseJson).bind("hash",r.caseHash).bind("expiry",r.leaseExpiresAt)
            .fetch().rowsUpdated().awaitSingle()
    }
    suspend fun events(id: UUID): List<DemonstrationEvent> = db.sql(
        "SELECT * FROM demonstration_event WHERE session_id=:id ORDER BY id LIMIT 10001")
        .bind("id",id).map { r -> DemonstrationEvent(r.long("id"),r.text("kind"),r.text("payload_json"),r.instant("created_at")) }
        .all().collectList().awaitSingle()
    suspend fun append(id: UUID, kind: String, json: String, requestId: Long? = null, sequence: Long? = null): DemonstrationEvent {
        return db.sql("""INSERT INTO demonstration_event(session_id,kind,payload_json,request_id,command_sequence,command_status)
            VALUES(:id,:kind,:json,:request,:seq,:status) RETURNING *""")
            .bind("id",id).bind("kind",kind).bind("json",json)
            .nullable("request",requestId,java.lang.Long::class.java).nullable("seq",sequence,java.lang.Long::class.java)
            .nullable("status",if(requestId != null) "REQUESTED" else null,String::class.java)
            .map { r -> DemonstrationEvent(r.long("id"),r.text("kind"),r.text("payload_json"),r.instant("created_at")) }.one().awaitSingle()
    }
    suspend fun command(instanceId: Long, requestId: Long): CommandOwner? = db.sql("""SELECT e.id,e.session_id,e.command_status
        FROM demonstration_event e JOIN demonstration_session s ON s.id=e.session_id
        WHERE s.game_instance_id=:i AND e.request_id=:r""").bind("i",instanceId).bind("r",requestId)
        .map { r -> CommandOwner(r.get("session_id",UUID::class.java)!!,r.long("id"),r.text("command_status")) }.one().awaitSingleOrNull()
    suspend fun latest(id: UUID): ProcedureRow? = db.sql("SELECT * FROM verification_procedure WHERE session_id=:id ORDER BY revision DESC LIMIT 1")
        .bind("id",id).map(::procedure).one().awaitSingleOrNull()
    suspend fun insertProcedure(id: UUID, revision: Int, hash: String, json: String): ProcedureRow = db.sql("""
        INSERT INTO verification_procedure(session_id,revision,source_hash,status,procedure_json)
        VALUES(:id,:v,:hash,'DRAFT',:json) RETURNING *""").bind("id",id).bind("v",revision).bind("hash",hash).bind("json",json)
        .map(::procedure).one().awaitSingle()
    suspend fun qaActive(instanceId: Long): Boolean = db.sql("""SELECT EXISTS(
        SELECT 1 FROM qa_try WHERE game_instance_id=:id AND status IN ('STARTING','RUNNING')
        UNION ALL SELECT 1 FROM qa_run WHERE game_instance_id=:id AND status IN ('STARTING','RUNNING')) AS active""")
        .bind("id",instanceId).map { r -> r.get("active",java.lang.Boolean::class.java)!!.booleanValue() }.one().awaitSingle()
    private fun session(r: Readable) = DemonstrationRow(r.get("id",UUID::class.java)!!,r.long("project_id"),r.long("test_case_id"),
        r.long("scenario_id"),r.long("game_instance_id"),r.long("game_build_id"),r.long("owner_id"),r.long("generation"),
        r.text("sdk_connection_id"),r.text("case_json"),r.text("case_hash"),r.text("status"),r.get("control_ready",java.lang.Boolean::class.java)!!.booleanValue(),
        r.instant("lease_expires_at"),r.get("source_hash",String::class.java),r.get("sealed_event_id",java.lang.Long::class.java)?.toLong(),
        r.instant("created_at"),r.get("stopped_at",Instant::class.java))
    internal fun procedure(r: Readable) = ProcedureRow(r.long("id"),r.get("session_id",UUID::class.java)!!,
        r.get("revision",Integer::class.java)!!.toInt(),r.text("status"),r.text("source_hash"),r.text("procedure_json"))
}
internal fun Readable.long(name: String): Long = get(name,java.lang.Long::class.java)!!.toLong()
internal fun Readable.text(name: String): String = get(name,String::class.java)!!
internal fun Readable.instant(name: String): Instant = get(name,Instant::class.java)!!
internal fun DatabaseClient.GenericExecuteSpec.nullable(name: String, value: Any?, type: Class<*>): DatabaseClient.GenericExecuteSpec =
    if(value == null) bindNull(name,type) else bind(name,value)
