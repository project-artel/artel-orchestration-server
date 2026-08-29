package kr.artel.orchestration.tracker

import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.issue.entity.IssueEntity
import kr.artel.orchestration.issue.repository.IssueRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import java.time.Instant
import java.util.UUID

/** 이슈 하나가 서기까지 필요한 최소 행들. 테스트마다 다시 쓰지 않도록 한곳에 모은다. */
data class TrackerSeed(val projectId: Long, val qaTryId: Long)

class TrackerSeeder(
    private val projectRepository: ProjectRepository,
    private val memberRepository: ProjectMemberRepository,
    private val scenarioRepository: TestScenarioRepository,
    private val gameInstanceRepository: GameInstanceRepository,
    private val qaTryRepository: QaTryRepository,
    private val issueRepository: IssueRepository
) {
    suspend fun seed(ownerId: Long, name: String = "tracker"): TrackerSeed {
        val now = Instant.now()
        val project = projectRepository.save(
            ProjectEntity(name = name, genre = "ACTION", createdAt = now, updatedAt = now)
        )!!
        memberRepository.save(
            ProjectMemberEntity(
                projectId = project.id!!,
                appUserId = ownerId,
                role = ProjectRole.OWNER.name,
                createdAt = now
            )
        )
        val scenario = scenarioRepository.save(TestScenarioEntity(projectId = project.id!!))!!
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = project.id!!,
                name = "instance",
                platform = "UNITY",
                sdkUuid = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now
            )
        )!!
        val qaTry = qaTryRepository.save(
            QaTryEntity(
                testScenarioId = scenario.id!!,
                gameInstanceId = instance.id!!,
                startedBy = ownerId,
                status = "COMPLETED",
                startedAt = now,
                completedAt = now
            )
        )!!
        return TrackerSeed(project.id!!, qaTry.id!!)
    }

    suspend fun join(projectId: Long, userId: Long, role: ProjectRole) {
        memberRepository.save(
            ProjectMemberEntity(
                projectId = projectId,
                appUserId = userId,
                role = role.name,
                createdAt = Instant.now()
            )
        )
    }

    suspend fun issue(qaTryId: Long, severity: String, title: String = "결함"): Long =
        issueRepository.save(
            IssueEntity(
                qaTryId = qaTryId,
                messageId = UUID.randomUUID().toString(),
                severity = severity,
                title = title,
                detail = Json.of(
                    """{"expected":"상점이 열린다","actual":"검은 화면","steps":["상점 진입","구매"]}"""
                ),
                reportedAt = Instant.parse("2026-08-28T01:02:03Z"),
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )
        )!!.id!!
}
