package kr.artel.orchestration.auth

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.entity.PlatformRole
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.auth.service.PlatformAccessService
import kr.artel.orchestration.common.error.ForbiddenException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.dto.ProjectScope
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.service.ProjectService
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaTryService
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import kr.artel.orchestration.testscenario.service.TestScenarioService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID

/**
 * `app_user.platform_role`이 무엇을 열고 무엇을 열지 않는지(ARTEL-742).
 *
 * 이 테스트가 지키는 주장은 하나다: **DEVELOPER 는 남의 프로젝트를 읽고, 쓰지 못한다.** 그래서
 * 읽기와 쓰기를 같은 사용자·같은 프로젝트에 대해 나란히 확인한다. 둘을 다른 테스트로 나누면
 * 접근 함수를 잘못 바꿨을 때 한쪽만 빨개져 원인이 흐려진다.
 *
 * `USER` 쪽 확인이 함께 있는 것도 의도다. 넓히는 변경은 넓히지 말아야 할 것까지 넓혔는지가 진짜
 * 위험이고, 그것은 `DEVELOPER` 를 아무리 봐도 드러나지 않는다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PlatformRoleAccessIntegrationTest {

    @Autowired private lateinit var platformAccessService: PlatformAccessService
    @Autowired private lateinit var projectService: ProjectService
    @Autowired private lateinit var scenarioService: TestScenarioService
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var qaTryService: QaTryService
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var databaseClient: DatabaseClient

    /** 프로젝트를 가진 사람. 이 사람만 참여자다. */
    private var ownerId: Long = 0

    /** 참여하지 않은 개발자. */
    private var developerId: Long = 0

    /** 참여하지 않은 일반 사용자. 회귀를 지키는 쪽이다. */
    private var outsiderId: Long = 0

    private var projectId: Long = 0
    private var scenarioId: Long = 0

    @AfterEach
    fun clean(): Unit = runBlocking { wipe() }

    @BeforeEach
    fun cleanAndSeed(): Unit = runBlocking {
        wipe()
        val now = Instant.now()

        ownerId = signIn("owner").userId.toLong()
        developerId = signIn("developer").userId.toLong()
        outsiderId = signIn("outsider").userId.toLong()
        promote(developerId)

        val project = projectRepository.save(
            ProjectEntity(name = "platform-role", genre = "ACTION", createdAt = now, updatedAt = now)
        )!!
        projectId = project.id!!
        projectMemberRepository.save(
            ProjectMemberEntity(
                projectId = projectId,
                appUserId = ownerId,
                role = ProjectRole.OWNER.name,
                createdAt = now
            )
        )
        scenarioId = testScenarioRepository.save(TestScenarioEntity(projectId = projectId))!!.id!!
    }

    // ------------------------------------------------ 등급 자체

    @Test
    fun `등급을 올린 사람만 전체를 본다`(): Unit = runBlocking {
        assertThat(platformAccessService.seesAllProjects(developerId)).isTrue()
        assertThat(platformAccessService.seesAllProjects(outsiderId)).isFalse()
        assertThat(platformAccessService.seesAllProjects(ownerId)).isFalse()
    }

    /** 세션이 가리키는 사용자가 지워진 경우다. 예외가 아니라 false여야 401이 500이 되지 않는다. */
    @Test
    fun `없는 사용자는 전체를 보지 못한다`(): Unit = runBlocking {
        assertThat(platformAccessService.seesAllProjects(userId = -1)).isFalse()
    }

    @Test
    fun `기본값은 USER 다`(): Unit = runBlocking {
        assertThat(appUserRepository.findById(ownerId)!!.platformRole)
            .isEqualTo(PlatformRole.USER.name)
    }

    /**
     * `GET /api/auth/me`가 등급을 싣는다.
     *
     * 컨트롤러는 이 프로필을 필드째 옮기기만 하므로 여기서 확인한다. admin-page 가 이 값 하나로
     * `scope=ALL`을 붙일지 고르므로(ARTEL-743), 빠지면 화면이 등급을 알 방법이 없다.
     */
    @Test
    fun `프로필이 등급을 싣는다`(): Unit = runBlocking {
        assertThat(oauthUserService.findProfile(developerId)!!.platformRole)
            .isEqualTo(PlatformRole.DEVELOPER.name)
        assertThat(oauthUserService.findProfile(outsiderId)!!.platformRole)
            .isEqualTo(PlatformRole.USER.name)
    }

    // ------------------------------------------------ QA 실행 목록

    /**
     * `GET /api/qa-tries`의 두 방향.
     *
     * 이 질의도 `JOIN project_member`를 `EXISTS`와 `:seesAllProjects`로 바꿨는데, 다른 넷과 달리
     * 이쪽은 원래 어느 테스트도 지나지 않던 자리였다. 조건을 잘못 옮기면 참여하지 않은 사람에게
     * 남의 QA 실행 목록이 그대로 나간다.
     */
    @Test
    fun `개발자는 남의 QA 실행 목록을 보고 일반 사용자는 보지 못한다`(): Unit = runBlocking {
        val qaTryId = seedQaTry()

        assertThat(qaTryService.listByProject(projectId, developerId, size = 20).map { it.id })
            .containsExactly(qaTryId.toString())
        assertThat(qaTryService.listByProject(projectId, ownerId, size = 20)).hasSize(1)
        assertThat(qaTryService.listByProject(projectId, outsiderId, size = 20)).isEmpty()
    }

    // ------------------------------------------------ 프로젝트 목록

    @Test
    fun `개발자는 참여하지 않은 프로젝트를 scope ALL 로 본다`(): Unit = runBlocking {
        val all = projectService.list(developerId, page = 0, size = 20, scope = ProjectScope.ALL)
        assertThat(all.items.map { it.id }).contains(projectId.toString())
        assertThat(all.total).isEqualTo(1)
    }

    @Test
    fun `개발자의 기본 목록은 여전히 참여 중인 것뿐이다`(): Unit = runBlocking {
        val mine = projectService.list(developerId, page = 0, size = 20)
        assertThat(mine.items).isEmpty()
        assertThat(mine.total).isZero()
    }

    @Test
    fun `일반 사용자가 scope ALL 을 주면 거절한다`(): Unit = runBlocking {
        assertThatThrownBy {
            runBlocking {
                projectService.list(outsiderId, page = 0, size = 20, scope = ProjectScope.ALL)
            }
        }.isInstanceOf(ForbiddenException::class.java)
    }

    /** 넓히는 변경이 넓히지 말아야 할 것까지 넓혔는지. 이 테스트가 회귀를 지킨다. */
    @Test
    fun `일반 사용자의 목록은 이 변경 전과 같다`(): Unit = runBlocking {
        assertThat(projectService.list(outsiderId, page = 0, size = 20).items).isEmpty()
        assertThat(projectService.list(ownerId, page = 0, size = 20).items).hasSize(1)
    }

    // ------------------------------------------------ 시나리오 읽기와 쓰기

    @Test
    fun `개발자는 남의 시나리오를 읽는다`(): Unit = runBlocking {
        assertThat(scenarioService.listScenarios(projectId, developerId).items).hasSize(1)
        assertThat(scenarioService.getScenarioInProject(projectId, scenarioId, developerId))
            .isNotNull()
    }

    /**
     * 읽기를 연 것이 쓰기를 열지 않았다는 확인. 기대 판정 라벨은 QA 화면의 미탐·오탐 숫자가
     * 대조하는 정답지라, 참여하지 않은 사람이 남의 벤치마크 기준을 고칠 수 있으면 안 된다.
     */
    @Test
    fun `개발자도 남의 기대 판정 라벨은 고치지 못한다`(): Unit = runBlocking {
        assertThatThrownBy {
            runBlocking {
                scenarioService.updateExpectedLabels(developerId, scenarioId, mapOf(1 to true))
            }
        }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `개발자도 남의 시나리오를 지우지 못한다`(): Unit = runBlocking {
        assertThatThrownBy {
            runBlocking { scenarioService.delete(developerId, scenarioId) }
        }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `일반 사용자는 남의 시나리오를 읽지도 못한다`(): Unit = runBlocking {
        assertThatThrownBy {
            runBlocking { scenarioService.listScenarios(projectId, outsiderId) }
        }.isInstanceOf(NotFoundException::class.java)
        assertThat(scenarioService.getScenarioInProject(projectId, scenarioId, outsiderId)).isNull()
    }

    // ------------------------------------------------ fixtures

    /**
     * 끝난 QA 실행 하나. 인스턴스를 함께 만드는 것은 `qa_try.game_instance_id`가 NOT NULL이기
     * 때문이고, 목록 질의가 보는 것은 시나리오를 통해 이어지는 `project_id` 하나다.
     */
    private suspend fun seedQaTry(): Long {
        val now = Instant.now()
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = projectId,
                name = "platform-role-instance",
                platform = "UNITY",
                sdkUuid = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now
            )
        )!!
        return qaTryRepository.save(
            QaTryEntity(
                testScenarioId = scenarioId,
                gameInstanceId = instance.id!!,
                startedBy = ownerId,
                status = "COMPLETED",
                startedAt = now.minusSeconds(60),
                completedAt = now
            )
        )!!.id!!
    }

    private suspend fun promote(userId: Long) {
        val user = appUserRepository.findById(userId)!!
        appUserRepository.save(
            user.copy(platformRole = PlatformRole.DEVELOPER.name, updatedAt = Instant.now())
        )
    }

    /**
     * 리액티브 트랜잭션은 테스트 롤백이 안 되고 실 DB를 공유하므로 FK 순서대로 직접 비운다.
     *
     * `qa_log`부터 `game_instance`까지가 함께 들어 있는 것은 이 테스트가 그 행을 만들기 때문이 아니다.
     * 앞선 테스트가 남긴 `qa_run` 행이 있으면 `DELETE FROM project`가 `qa_run_game_instance_id_fkey`에
     * 걸려 이 클래스가 통째로 죽는다. 단독으로 돌리면 통과하고 전체 스위트에서만 깨지는 모양이라,
     * 이 순서가 없으면 원인을 찾는 데 시간이 든다.
     */
    private suspend fun wipe() {
        execute("DELETE FROM qa_log")
        execute("DELETE FROM issue")
        execute("DELETE FROM qa_try")
        execute("DELETE FROM qa_run")
        execute("DELETE FROM game_instance")
        testScenarioRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    private suspend fun execute(sql: String) {
        databaseClient.sql(sql).fetch().rowsUpdated().awaitFirstOrNull()
    }

    private suspend fun signIn(seed: String): AuthenticatedUser =
        oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = seed,
                login = "user-$seed",
                displayName = "user-$seed",
                avatarUrl = null,
                email = "$seed@example.com"
            )
        )!!
}
