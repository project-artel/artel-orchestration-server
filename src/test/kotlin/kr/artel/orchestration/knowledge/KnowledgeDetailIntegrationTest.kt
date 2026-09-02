package kr.artel.orchestration.knowledge

import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.knowledge.entity.KnowledgeEdgeEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeEntity
import kr.artel.orchestration.knowledge.repository.KnowledgeAnchorRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeEdgeRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import kr.artel.orchestration.knowledge.service.KnowledgeGraphViewService
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * knowledge 항목 단건 조회의 검증(ARTEL-753).
 *
 * 여기서 지키는 성질은 넷이다.
 *
 * 1. **정상 조회는 그래프 목록이 안 주는 값까지 낸다** — `description`/`updatedAt`/`isDocumentNode`.
 * 2. **비참여자·다른 프로젝트 항목·소프트삭제 항목·실험 스코프 항목은 전부 404다.** [KnowledgeGraphViewService.graph]
 *    가 비참여자에게 빈 그래프를 주는 것과 다른 판단이다 — 단건 조회는 있는 척할 목록이 없다.
 * 3. **운영 스코프만 읽는다.** 그래프 조회와 같은 판단이다.
 * 4. **문서 node(ARTEL-748)는 `isDocumentNode=true`로 구분되고, 그 외에는 같은 경로를 탄다.**
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class KnowledgeDetailIntegrationTest {

    @Autowired private lateinit var viewService: KnowledgeGraphViewService
    @Autowired private lateinit var knowledgeRepository: KnowledgeRepository
    @Autowired private lateinit var edgeRepository: KnowledgeEdgeRepository
    @Autowired private lateinit var anchorRepository: KnowledgeAnchorRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService

    private var projectId: Long = 0
    private var userId: Long = 0

    /** 리액티브 트랜잭션은 롤백이 안 되고 실 DB를 공유하므로 FK 순서대로 직접 비운다. */
    @AfterEach
    fun clean(): Unit = runBlocking { wipe() }

    @BeforeEach
    fun cleanAndSeed(): Unit = runBlocking {
        wipe()
        userId = signIn().userId.toLong()
        val now = Instant.now()
        projectId = projectRepository.save(
            ProjectEntity(name = "knowledge-detail", genre = "ACTION", createdAt = now, updatedAt = now)
        )!!.id!!
        projectMemberRepository.save(
            ProjectMemberEntity(
                projectId = projectId,
                appUserId = userId,
                role = ProjectRole.OWNER.name,
                createdAt = now
            )
        )
    }

    private suspend fun wipe() {
        anchorRepository.deleteAll()
        edgeRepository.deleteAll()
        knowledgeRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `정상 조회는 그래프 목록이 안 주는 값까지 낸다`(): Unit = runBlocking {
        val id = givenKnowledge("상점에서만 골드 부족 안내가 뜬다", description = "상점 화면에서 확인한 예외")

        val response = viewService.detail(projectId, userId, id)

        assertThat(response.id).isEqualTo(id.toString())
        assertThat(response.summary).isEqualTo("상점에서만 골드 부족 안내가 뜬다")
        assertThat(response.description).isEqualTo("상점 화면에서 확인한 예외")
        assertThat(response.updatedAt).isNotNull()
        assertThat(response.isDocumentNode).isFalse()
    }

    @Test
    fun `비참여자는 예외가 아니라 404를 받는다`(): Unit = runBlocking {
        val id = givenKnowledge("골드 부족 안내")
        val outsider = signIn(seed = "outsider").userId.toLong()

        assertThatThrownBy { runBlocking { viewService.detail(projectId, outsider, id) } }
            .describedAs("단건 조회는 있는 척할 목록이 없으므로 비참여자에게도 404가 맞다")
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `다른 프로젝트의 항목은 404다`(): Unit = runBlocking {
        val id = givenKnowledge("골드 부족 안내")
        val now = Instant.now()
        val otherProjectId = projectRepository.save(
            ProjectEntity(name = "other-project", genre = "ACTION", createdAt = now, updatedAt = now)
        )!!.id!!
        projectMemberRepository.save(
            ProjectMemberEntity(projectId = otherProjectId, appUserId = userId, role = ProjectRole.OWNER.name, createdAt = now)
        )

        assertThatThrownBy { runBlocking { viewService.detail(otherProjectId, userId, id) } }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `소프트삭제된 항목은 404다`(): Unit = runBlocking {
        val id = givenKnowledge("지워질 지식", deletedAt = Instant.now())

        assertThatThrownBy { runBlocking { viewService.detail(projectId, userId, id) } }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `실험 스코프 항목은 404다`(): Unit = runBlocking {
        val id = givenKnowledge("실험 arm의 지식", scopeId = EXPERIMENT_SCOPE_ID)

        assertThatThrownBy { runBlocking { viewService.detail(projectId, userId, id) } }
            .describedAs("운영 스코프만 읽으므로 실험 스코프 항목은 조회되지 않는다")
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `존재하지 않는 id는 404다`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { viewService.detail(projectId, userId, 999_999_999L) } }
            .isInstanceOf(NotFoundException::class.java)
    }

    /**
     * 문서 node(ARTEL-748)는 살아있는 `PART_OF` edge의 도착점으로 정의된다. 그 정의 하나로
     * `isDocumentNode`가 서고, `description`에는 이미 [kr.artel.orchestration.knowledge.service.KnowledgeService]
     * 가 심어 둔 구조적 표지 문장이 그대로 실린다 — 이 API는 문서 node를 다르게 저장하지 않는다.
     */
    @Test
    fun `문서 node는 isDocumentNode가 true다`(): Unit = runBlocking {
        val documentNode = givenKnowledge(
            "기획서.pdf",
            description = "이 node는 문서에서 뽑아낸 사실이 아니다.",
            source = "DOCS",
            sourceId = 42L
        )
        val item = givenKnowledge("이동 방법", source = "DOCS", sourceId = 42L)
        edgeRepository.save(
            KnowledgeEdgeEntity(
                projectId = projectId,
                fromKnowledgeId = item,
                toKnowledgeId = documentNode,
                relation = "PART_OF",
                note = "문서 추출 파이프라인이 이 항목을 문서 node 아래에 자동으로 배치했다."
            )
        )

        val documentResponse = viewService.detail(projectId, userId, documentNode)
        val itemResponse = viewService.detail(projectId, userId, item)

        assertThat(documentResponse.isDocumentNode).isTrue()
        assertThat(itemResponse.isDocumentNode)
            .describedAs("PART_OF edge의 출발점은 문서 node가 아니라 그 아래 배치된 항목이다")
            .isFalse()
    }

    // --------------------------------------------------------------- helpers

    private suspend fun givenKnowledge(
        summary: String,
        description: String = "$summary 설명",
        scopeId: Long? = null,
        deletedAt: Instant? = null,
        source: String = "DOCS",
        sourceId: Long? = null
    ): Long =
        knowledgeRepository.save(
            KnowledgeEntity(
                projectId = projectId,
                source = source,
                sourceId = sourceId,
                tag = "RULE",
                summary = summary,
                description = description,
                scopeId = scopeId,
                deletedAt = deletedAt
            )
        ).id!!

    private suspend fun signIn(seed: String = "knowledge-detail"): AuthenticatedUser =
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

    private companion object {
        /** 실험 엔티티가 아직 없으므로 스코프 id는 운영(NULL)이 아니기만 하면 된다. */
        const val EXPERIMENT_SCOPE_ID = 9_001L
    }
}
