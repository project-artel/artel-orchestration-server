package kr.artel.orchestration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles

/**
 * 애플리케이션의 Spring context 가 뜨는지만 본다. Jenkins 의 `Context Startup Check` 단계가 이 클래스
 * 하나를 실행한다.
 *
 * Jenkins 의 Build 단계는 `-DskipTests` 라, 컴파일은 되고 기동에서 죽는 결함은 배포된 컨테이너가 처음
 * 드러낸다. 같은 경로를 두 controller 가 등록한 `Ambiguous mapping`(ARTEL-1153), bean 순환 참조, 빠진
 * 설정 값이 그 예다. 전체 suite 는 이 단계의 통과 조건으로 걸지 않으므로, 기동 결함은 여기서 잡는다.
 *
 * 다른 `@SpringBootTest` 도 기동에 실패하면 같이 실패하지만, 각자 다른 것을 검사한다. 이 클래스는 기동
 * 외에 아무것도 검사하지 않아서, 이것이 실패하면 원인은 기동이다.
 */
@ActiveProfiles("test")
@SpringBootTest
class ApplicationContextStartupTest {

    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `application context starts`() {
        assertThat(context.beanDefinitionCount).isPositive()
    }
}
