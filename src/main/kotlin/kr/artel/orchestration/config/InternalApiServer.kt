package kr.artel.orchestration.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.context.ApplicationContext
import org.springframework.context.SmartLifecycle
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter
import org.springframework.web.server.adapter.WebHttpHandlerBuilder
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer

/**
 * `/internal/` 하위만 서빙하는 두 번째 Netty 서버(ARTEL-266).
 *
 * WebFlux에는 서블릿 스택의 `management.server.port` 같은 것이 없고, reactor-netty의
 * `HttpServer` 하나는 소켓 하나만 바인딩한다. 두 번째 포트를 열려면 두 번째 서버가 반드시
 * 필요하다. 다만 **컨텍스트는 하나**다 — 핸들러 체인만 [InternalApiConfig]의 설명대로
 * 따로 조립한다. DB 커넥션 풀도, Security 체인도, 컨트롤러도 전부 공유된다.
 *
 * 외부 차단의 실질 근거는 이 클래스가 아니라 배포 토폴로지다. `Jenkinsfile`의 `docker run`에
 * `-p`가 없어 이 포트는 호스트에 게시되지 않고, 컨테이너는 `app-net`에만 붙는다. 이 클래스는
 * 그 경계를 코드에 새겨 리뷰 대상으로 만든다. 근거는 `docs/deployment.md`.
 */
class InternalApiServer(
    private val applicationContext: ApplicationContext,
    private val properties: InternalApiProperties
) : SmartLifecycle, DisposableBean {

    private val logger = LoggerFactory.getLogger(InternalApiServer::class.java)

    @Volatile
    private var server: DisposableServer? = null

    /**
     * 실제로 바인딩된 포트. 설정이 0이면 OS가 고른 값이라 여기서만 알 수 있다(테스트가 읽는다).
     * 서버가 없으면 조용히 -1을 주는 대신 던진다 — 0을 포트로 오해해 엉뚱한 곳을 찌르는 것보다 낫다.
     */
    val port: Int
        get() = server?.port() ?: error("내부 API 서버가 기동되지 않았다")

    override fun start() {
        if (server != null) return

        val handler = WebHttpHandlerBuilder.applicationContext(applicationContext)
            .filters { it.add(0, prefixGate(blockWhenInternal = false)) }
            .build()

        server = HttpServer.create()
            // 모든 인터페이스에 바인딩해야 `app-net`의 다른 컨테이너가 닿는다. 외부 차단은
            // 여기가 아니라 "호스트에 포트를 게시하지 않는다"가 맡는다.
            .host(ALL_INTERFACES)
            .port(properties.port)
            .handle(ReactorHttpHandlerAdapter(handler))
            .bindNow()

        logger.info("내부 API 서버 기동 — 포트 {}, /internal/** 전용", port)
    }

    override fun stop() {
        server?.disposeNow()
        server = null
    }

    /**
     * 컨텍스트 기동이 도중에 실패하면 `refresh()`는 `destroyBeans()`로 가고, 그 경로는
     * `Lifecycle.stop()`을 부르지 않는다. 이 서버는 Boot의 웹 서버와 같은 phase라 어느 쪽이
     * 먼저 뜨는지가 정해져 있지 않으므로, 8080 바인딩 실패처럼 늦게 터지는 오류에서 이미
     * 열린 소켓이 JVM이 죽을 때까지 남는다. 테스트 스위트처럼 컨텍스트를 여럿 띄우는
     * 곳에서 그 누수가 포트 고갈로 나타난다.
     */
    override fun destroy() = stop()

    override fun isRunning(): Boolean = server != null

    /**
     * Boot의 `WebServerStartStopLifecycle.getPhase()`와 **같은 값**이다(3.3.1 바이트코드로
     * 확인: `2147481599` = `Integer.MAX_VALUE - 2048`). 같은 단계에 두어야 두 서버가 함께
     * 뜨고 함께 내려간다 — 한쪽만 살아 있는 구간을 만들지 않는다.
     */
    override fun getPhase(): Int = WEB_SERVER_PHASE

    private companion object {
        const val ALL_INTERFACES = "0.0.0.0"
        const val WEB_SERVER_PHASE = Int.MAX_VALUE - 2048
    }
}
