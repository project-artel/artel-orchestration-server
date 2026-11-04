package kr.artel.orchestration.qa.state

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID

/**
 * `POST /internal/qa-tries/{id}/state` 가 try 의 게임 인스턴스 상태를 내는지(ARTEL-1296).
 *
 * reducer 규칙은 [StateSetContractTest] 가 본다. 여기는 try → 게임 인스턴스 해석, 없는 try 의 404, 그리고
 * 서버의 `ObjectMapper` 가 내는 응답 모양(관측된 `null` 이 JSON `null` 로, 상태가 이름으로)을 본다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class StateSetApiIntegrationTest {

    @Autowired private lateinit var controller: InternalStateSetController
    @Autowired private lateinit var stateSets: StateSetRegistry
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService

    private var qaTryId: Long = 0
    private var gameInstanceId: Long = 0

    @AfterEach
    fun clean(): Unit = runBlocking { wipe() }

    @BeforeEach
    fun cleanAndSeed(): Unit = runBlocking {
        wipe()
        val userId = oauthUserService.upsert(
            OAuthIdentity(provider = "github", providerUserId = "state-set", login = "state-set", displayName = "state-set", avatarUrl = null)
        ).userId.toLong()
        val now = Instant.now()
        val projectId = projectRepository.save(ProjectEntity(name = "state-set", genre = "ACTION", createdAt = now, updatedAt = now)).id!!
        val scenarioId = testScenarioRepository.save(TestScenarioEntity(projectId = projectId)).id!!
        gameInstanceId = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = projectId, name = "instance", platform = "UNITY",
                sdkUuid = UUID.randomUUID().toString(), createdAt = now, updatedAt = now,
            )
        ).id!!
        qaTryId = qaTryRepository.save(
            QaTryEntity(
                testScenarioId = scenarioId, gameInstanceId = gameInstanceId, startedBy = userId,
                status = "RUNNING", startedAt = now,
            )
        ).id!!
    }

    private suspend fun wipe() {
        qaTryRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        testScenarioRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `try 의 게임 인스턴스에 적용된 pulse 를 고른 범위로 낸다`(): Unit = runBlocking {
        stateSets.observe(
            gameInstanceId, "session-1",
            """{"type":"PULSE","id":5,"schema":3,"reading":1,"frame":10,"scene":"Battle","whole":true,
               "active":[{"id":12,"path":"Player","by":[{"on":"Battle.Player","m":[{"member":"hp","value":80},{"member":"target","value":null}]}]}],
               "statics":[{"declaring":"Core.InteractionLock","member":"locked","value":false}]}""",
        )

        val snapshot = controller.readQaTryState(qaTryId, ObservationSpec(members = listOf("Battle.Player::target")))

        assertThat(snapshot.sync).isEqualTo(SyncState.SYNCED)
        assertThat(snapshot.entities.single().members.keys).containsExactly("Battle.Player::target#0")
        val json = objectMapper.readTree(objectMapper.writeValueAsString(snapshot))
        val target = json["entities"][0]["members"]["Battle.Player::target#0"]
        assertThat(target["status"].asText()).isEqualTo("KNOWN")
        assertThat(target.has("value")).isTrue()
        assertThat(target["value"].isNull).isTrue()
        assertThat(json["statics"][0]["observation"]["value"].asBoolean()).isFalse()
    }

    @Test
    fun `pulse 가 아직 없으면 whole 을 기다리는 빈 상태다`(): Unit = runBlocking {
        val snapshot = controller.readQaTryState(qaTryId, null)

        assertThat(snapshot.sync).isEqualTo(SyncState.AWAITING_WHOLE)
        assertThat(snapshot.entities).isEmpty()
    }

    @Test
    fun `없는 try 는 404 다`(): Unit = runBlocking {
        assertThrows<NotFoundException> { controller.readQaTryState(qaTryId + 999, null) }
    }
}
