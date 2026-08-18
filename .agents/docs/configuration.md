# Configuration

## Why

설정값은 `@ConfigurationProperties` 데이터 클래스로 받는다. **`@Value`를 새로 쓰지 않는다.**

`@Value`는 설정을 문자열 리터럴로 흩는다.

- **키 오타가 기동 시점에 안 잡힌다.** 기본값이 붙어 있으면(`@Value("\${a.b.c:30}")`) 오타 난
  키는 조용히 기본값으로 떨어져, 운영에서 설정을 바꿔도 아무 일이 일어나지 않는다.
- **설정 표면이 한곳에 없다.** 그 기능의 설정이 무엇무엇인지 알려면 생성자 파라미터를 훑어야
  하고, 여러 클래스가 같은 키를 읽으면 기본값이 갈린다.
- **검증할 자리가 없다.** "batch-size는 1 이상", "max-rows-per-tick은 batch-size 이상" 같은
  제약을 걸 곳이 없어, 잘못된 값이 런타임 한참 뒤에 이상 동작으로만 드러난다.
- **테스트에서 값을 갈아끼우기 어렵다.** 생성자에 객체 하나를 넘기면 되는 것이, 컨텍스트를
  띄우거나 문자열 프로퍼티를 흉내내는 일이 된다.

`coding-style.md`의 `Data Shapes`가 경계를 넘는 페이로드에 대해 말하는 것과 같은 규칙이다.
설정도 프로세스 경계를 넘어 들어오는 페이로드이고, 타입 하나가 그 계약을 적어 두는 자리다.

## How

`<기능>Properties` 데이터 클래스를 그 기능의 `config` 패키지에 두고, 쓰는 쪽 설정 클래스에서
`@EnableConfigurationProperties`로 등록한다.

```kotlin
@ConfigurationProperties(prefix = "artel.sdk-performance.retention")
data class SdkPerformanceRetentionProperties(
    /** 각 프로퍼티에 KDoc으로 의미와 기본값의 근거를 남긴다. */
    val days: Long = 30,
    val batchSize: Int = 5_000,
    val maxRowsPerTick: Int = 500_000
) {
    init {
        require(batchSize > 0) { "artel.sdk-performance.retention.batch-size는 1 이상이어야 합니다." }
        require(maxRowsPerTick >= batchSize) {
            "artel.sdk-performance.retention.max-rows-per-tick은 batch-size 이상이어야 합니다."
        }
    }
}
```

```kotlin
@Configuration
@EnableConfigurationProperties(SdkPerformanceRetentionProperties::class)
class SdkPerformanceRetentionScheduler(
    private val properties: SdkPerformanceRetentionProperties
)
```

- 기본값은 **데이터 클래스의 기본 인자**로 준다. `application.yml`은 환경별로 덮어쓰는 자리다.
- 제약은 `init { require(...) }`로 건다. **기동 시점에 깨지는 것이 운영 중에 이상 동작하는
  것보다 낫다.**
- 참고 구현: `knowledge/config/KnowledgeBackfillProperties.kt`, `config/InternalApiProperties.kt`,
  `stream/config/StreamProperties.kt`.

## 예외 — 빈 주입보다 먼저 필요한 값

`@Scheduled(fixedDelayString = ...)`과 `@ConditionalOnProperty`는 빈을 읽을 수 없어 문자열
placeholder를 쓸 수밖에 없다. 이때도 **같은 키를 properties 클래스에도 선언하고**, 양쪽
기본값이 같이 움직여야 한다는 것을 주석으로 남긴다. 설정 표면이 한곳에 모여 있어야 한다는
목적은 그대로다.

```kotlin
@ConditionalOnProperty(prefix = "artel.sdk-performance.retention", name = ["enabled"], havingValue = "true")
@Scheduled(fixedDelayString = "\${artel.sdk-performance.retention.interval-millis:3600000}")
```

## 기존 `@Value`

남아 있는 사용처를 이 규약이 생겼다는 이유로 일괄 교체하지 않는다. 무관한 정리는 diff를 넓혀
리뷰를 어렵게 한다. **새 코드는 이 규약을 따르고**, 기존 `@Value`는 그 파일을 다른 이유로
손댈 때 함께 옮긴다.
