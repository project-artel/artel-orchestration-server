package kr.artel.orchestration.contentmap.observe

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.contentmap.entity.ScreenEntity
import kr.artel.orchestration.contentmap.repository.ScreenRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 화면 행 하나를 프레임에 실을 [ScreenSelectorScreenRef] 로 만든다.
 *
 * `ScreenSelectorProposalService` 안의 private 메서드였다. 화면 판정을 싣는 프레임이 둘이 되면서
 * (`SCREEN_SELECTOR_PROPOSAL` 과 `SCREEN_SETTLED`, ARTEL-668) 밖으로 냈다 — 두 벌을 두면 한쪽만
 * 캡처를 서명하거나 한쪽만 `discriminator` 를 못 읽고 넘어가는 식으로 갈리고, 그때 agent 는 같은
 * 화면을 두 프레임에서 다르게 읽는다.
 */
@Component
class ScreenRefs(
    private val screens: ScreenRepository,
    private val storage: DocumentStorage,
    private val objectMapper: ObjectMapper,
) {

    private val logger = LoggerFactory.getLogger(ScreenRefs::class.java)

    /** [screenId] 의 화면 참조. id 가 null 이거나 그 행이 없으면 null 이다. */
    suspend fun of(screenId: Long?): ScreenSelectorScreenRef? {
        val screen = screenId?.let { screens.findById(it) } ?: return null
        val signed = screen.imageObjectKey?.let { storage.presignDownload(it, "screen-${screen.id}.jpg") }
        return ScreenSelectorScreenRef(
            screenId = screen.id.toString(),
            name = screen.name,
            discriminator = discriminatorOf(screen),
            captureUrl = signed?.url,
            captureExpiresAt = signed?.expiresAt,
        )
    }

    /**
     * 저장된 `discriminator` 를 읽는다. 못 읽으면 **빈 배열이다.**
     *
     * 화면 행 하나를 못 읽었다고 프레임을 통째로 버리지 않는다. 제안이면 후보와 캡처만으로도
     * 답할 수 있고, 통보면 어느 화면인지라도 가는 것이 아무것도 안 가는 것보다 낫다.
     */
    private fun discriminatorOf(screen: ScreenEntity): List<ScreenDiscriminatorEntry> = try {
        objectMapper.readValue(
            screen.discriminator.asString(),
            objectMapper.typeFactory.constructCollectionType(List::class.java, ScreenDiscriminatorEntry::class.java),
        )
    } catch (failure: Exception) {
        logger.warn("화면 {}의 discriminator 를 읽지 못했다: {}", screen.id, failure.message)
        emptyList()
    }
}
