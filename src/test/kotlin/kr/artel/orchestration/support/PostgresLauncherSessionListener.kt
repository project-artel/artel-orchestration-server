package kr.artel.orchestration.support

import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener

/**
 * 테스트 스위트가 시작되기 전에 DB 컨테이너를 띄운다.
 *
 * `META-INF/services`로 등록되어 JUnit Platform이 **어떤 테스트 클래스보다 먼저** 부른다. 이 시점이
 * 중요하다 — Spring 컨텍스트가 만들어지기 전에 접속 정보가 시스템 프로퍼티에 들어가 있어야
 * `application-test.yml`의 `${DB_HOST:...}`가 컨테이너를 가리킨다.
 */
class PostgresLauncherSessionListener : LauncherSessionListener {

    override fun launcherSessionOpened(session: LauncherSession) {
        // Docker가 없으면 Testcontainers의 모호한 메시지 대신 무엇을 고쳐야 하는지 먼저 알린다.
        DockerEnvironment.verify()
        PostgresTestContainer.startAndExportProperties()
    }
}
