package kr.artel.orchestration.support

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 테스트 DB 컨테이너를 띄우기 전에 Docker 접속 가능 여부를 확인한다.
 *
 * Testcontainers는 `/var/run/docker.sock`을 먼저 보고, 없으면 `DOCKER_HOST` **환경변수**를 본다.
 * colima·rootless Docker Desktop·Rancher Desktop은 표준 경로에 소켓을 만들지 않으므로, 그 환경변수가
 * 없으면 "Could not find a valid Docker environment"라는 원인이 드러나지 않는 메시지로 스위트 전체가
 * 죽는다.
 *
 * 환경변수는 JVM 안에서 만들 수 없다(`System.setProperty`로는 저 전략이 켜지지 않는다 —
 * `EnvironmentAndSystemPropertyClientProviderStrategy.isApplicable()`이 환경변수만 본다).
 * 그래서 여기서 할 수 있는 최선은 **소켓을 찾아 무엇을 export하면 되는지 알려 주고 일찍 죽는 것**이다.
 */
object DockerEnvironment {

    /** colima 등 요즘 데몬이 거절하지 않는 버전. 데몬이 더 높은 것은 하위 호환으로 받아 준다. */
    private const val MODERN_DOCKER_API_VERSION = "1.43"

    fun verify() {
        // DOCKER_HOST로 붙을 때 docker-java는 API 버전을 협상하지 않고 자기 기본값(1.32)을 쓰는데,
        // 요즘 데몬은 1.40 미만을 거절한다("client version 1.32 is too old"). 이 키는 환경변수와 달리
        // 시스템 프로퍼티로 먹는다. 명시된 값이 있으면 존중한다.
        if (System.getProperty("api.version").isNullOrBlank()) {
            System.setProperty("api.version", MODERN_DOCKER_API_VERSION)
        }

        if (!System.getenv("DOCKER_HOST").isNullOrBlank()) return
        if (Files.exists(Paths.get("/var/run/docker.sock"))) return

        throw IllegalStateException(buildString {
            append("테스트 DB 컨테이너를 띄울 Docker 데몬을 찾지 못했습니다.\n")
            append("  표준 소켓 경로(/var/run/docker.sock)가 없고 DOCKER_HOST 환경변수도 비어 있습니다.\n")
            val socket = findKnownSocket()
            if (socket != null) {
                append("  이 머신에서 소켓을 찾았습니다. 아래를 export하고 다시 실행하세요:\n")
                append("      export DOCKER_HOST=unix://").append(socket).append('\n')
            } else {
                append("  Docker(또는 colima)가 실행 중인지 확인하고, 표준 경로가 아니라면\n")
                append("      export DOCKER_HOST=unix://<소켓 경로>\n")
            }
            append("\n  VM 위에서 도는 런타임(colima, Rancher Desktop)이라면 하나 더 필요합니다:\n")
            append("      export TESTCONTAINERS_RYUK_DISABLED=true\n")
            append("  Ryuk(고아 컨테이너 청소기)이 호스트의 docker 소켓을 자기 컨테이너에\n")
            append("  bind-mount하는데 그 경로가 VM 안에는 없어 기동이 실패하기 때문입니다.\n")
            append("  꺼도 이 스위트는 JVM당 컨테이너 하나만 쓰고 JVM 종료 훅으로 내려가므로,\n")
            append("  잃는 것은 `kill -9`로 죽었을 때의 뒷정리뿐입니다.\n")
            append("\n  참고: ~/.testcontainers.properties에 docker.client.strategy가 고정돼 있으면\n")
            append("  그 값이 우선합니다. 설치 형태를 바꿨다면 그 줄이 오래된 값일 수 있습니다.")
        })
    }

    private fun findKnownSocket(): Path? {
        val home = System.getProperty("user.home") ?: return null
        return listOf(
            Paths.get(home, ".colima", "default", "docker.sock"),
            Paths.get(home, ".colima", "docker.sock"),
            Paths.get(home, ".docker", "run", "docker.sock"),
            Paths.get(home, ".rd", "docker.sock"),
        ).firstOrNull { Files.exists(it) }
    }
}
