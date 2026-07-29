# 2026-07-29 — knowledge pgvector 색인 테이블 + 백필 워커

- Date: 2026-07-29
- Jira: ARTEL-185 (부수 범위: ARTEL-188의 `deleted_at`)
- Branch: `feat/2차-스프린트-knowledge-pgvector-백필-워커-ARTEL-185`
- Base: `origin/develop` **b6a3861** (PR #56 코루틴 전환 이후로 리베이스)
- 선행: ARTEL-184 — **머지 완료**(agent-server `ded08ea`). 모델·차원 확정.

---

## 1. 목표

`knowledge`에 벡터를 담을 곳(`knowledge_embedding`)과 그것을 채우는 백필 워커를 만든다.
벡터 생성은 ARTEL-184(Agent), 검색 질의는 ARTEL-186이 맡는다.

부수 범위: `knowledge.deleted_at`을 이 마이그레이션에서 함께 만들고, 기존 읽기 경로가
`deleted_at IS NULL`을 걸게 한다. 삭제 API 자체는 ARTEL-188.

---

## 2. 착수 전 확인한 사실

| 항목 | 이슈 본문의 전제 | 실제 |
|---|---|---|
| 테스트 DB | "r2dbc-h2를 쓴다" | **틀림.** 이미 로컬 실 PostgreSQL을 본다. H2 의존성만 pom에 남아 있었다 |
| pgvector | — | 로컬 `artel-local-pg`는 `postgres:16` 순정이라 **pgvector 없음** |
| 스케줄러 | "사용처 0건" | 맞음. 이 이슈가 첫 `@EnableScheduling`이다 |
| CI | — | Jenkinsfile은 `-DskipTests`. **CI가 테스트를 돌리지 않아** Testcontainers 도입이 파이프라인을 깨지 않는다 |
| 베이스라인 | — | 리베이스 전 151개 중 1개 실패(`ProjectDocumentIntegrationTest.assigns_distinct_versions_to_concurrent_uploads`) — 내 작업 이전부터 빨간 상태 |

### 2.1 왜 테스트 전체가 영향을 받는가

`CREATE EXTENSION vector`가 들어가면 Flyway가 **모든 `@SpringBootTest`에서** 마이그레이션 체인을
돌리므로, pgvector 없는 DB에서는 벡터 테스트만이 아니라 **기존 통합 테스트가 전부** 깨진다.
"벡터 테스트만 분리"로는 해결되지 않는다.

---

## 3. 결정

### D1. 테스트 DB → Testcontainers `pgvector/pgvector:pg16` (스위트 전체)

- JVM당 컨테이너 1개. JUnit `LauncherSessionListener`(ServiceLoader 등록)가 스위트 시작 시 띄우고
  접속 정보를 시스템 프로퍼티로 내보낸다.
- `application-test.yml`이 이미 `${DB_HOST:localhost}` 형태라 **기존 테스트 클래스를 한 줄도 안 고쳤다.**
  (`@DynamicPropertySource`였다면 클래스마다 같은 블록을 복사해야 했다.)
- H2 의존성 2개를 걷어냈다. test 프로파일이 이미 실 PostgreSQL을 보고 있어 죽은 의존성이었다.
- 부수 효과: 리베이스 전 빨갛던 동시 업로드 테스트가 **초록으로 돌아왔다.** 테스트들이 개발자의
  가변 로컬 DB를 공유하던 것이 원인이었던 것으로 보인다.

**환경 제약(보고 대상):** colima처럼 `/var/run/docker.sock`이 없는 런타임에서는 환경변수 2개가 필요하다.
JVM 안에서 환경변수를 만들 수 없어(Testcontainers의 해당 전략이 환경변수만 본다) 코드로는 못 덮는다.
그래서 `DockerEnvironment`가 소켓을 찾아 **무엇을 export하면 되는지 알려 주고 일찍 죽는다.**

```
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
export TESTCONTAINERS_RYUK_DISABLED=true
```

### D2. 스케줄러 → `@EnableScheduling` + `runBlocking`, 프로퍼티 가드

- 워커 로직은 `suspend fun runOnce(): BackfillTickResult`로 스케줄과 분리 → 테스트가 직접 부른다.
- `@Scheduled(fixedDelay)` 안에서 `runBlocking`. 별도 스코프에 `launch`하면 tick 겹침 방지가 사라져
  Agent가 느릴 때 워커가 자기 자신과 같은 행을 두고 경합한다. 막는 스레드는 스케줄러 전용이라
  WebFlux 이벤트 루프를 건드리지 않는다.
- 설정 등록(`@EnableConfigurationProperties`)은 조건부 스케줄러가 아니라 항상 켜지는
  `KnowledgeConfig`에 둔다. 스케줄러가 꺼져도 워커 빈이 설정을 주입받아야 하기 때문이다.
- 코루틴 전환(PR #56)의 교훈대로, suspend 호출을 감싸는 broad catch 앞에는 전부
  `catch (CancellationException) { throw }`를 둔다.

### D3. 스키마 — 이슈의 단일 테이블 + "대기 행" 센티널

불변식(CHECK로 강제):

- `source_text IS NULL AND embedding IS NULL` → **대기 행**. 큐의 원소이자 `attempts`/`last_error`의 주인.
- 둘 다 NOT NULL → 완성된 벡터 행.

부분 유니크 인덱스 `(knowledge_id, kind, model) WHERE source_text IS NULL`로 대기 행이
knowledge·모델당 하나임을 보장한다. 이래야 모델 교체 재색인(이슈 요구)이 model별 큐 상태를 갖는다.
큐 상태를 `knowledge`에 두면 model 축이 사라져 성립하지 않는다.

### D4. 락 — 짧은 트랜잭션으로 claim, 느린 호출은 락 밖

`FOR UPDATE SKIP LOCKED`로 대기 행을 집고 **같은 문장에서 `attempts`를 올린다**(단일 CTE UPDATE).
Agent 호출은 락 밖에서 한다.

`attempts`를 실패 시점이 아니라 **claim 시점에** 올리는 것이 핵심이다. 프로세스가 죽어도 시도가
계산되므로 워커를 죽이는 항목이 무한 재시도되지 않는다.

### D5. `/knowledge-queries`의 all-or-nothing 대응 — 배치 실패 시 항목별 재시도

실제 코드에서 확인(agent-server `app/api/knowledge_queries.py`): 배치 중 한 항목이라도 실패하면
요청 전체가 422다. 그대로 두면 멀쩡한 항목까지 같은 tick에서 함께 실패하고 `attempts`가 다 같이 오른다.

→ **배치가 깨지면 항목별로 한 번씩 다시 부른다.** 추가 호출은 실패한 tick에서만 발생하고,
문제 항목만 홀로 `attempts`를 쌓다 상한에 걸려 큐에서 빠진다. 이분탐색이 호출 수는 적지만
배치 크기가 16이라 이득이 작고 추론이 어려워 택하지 않았다.

배치 크기 16은 Agent의 `knowledge_query_batch_limit=32`보다 작고(그쪽이 더 빡빡한 상한),
항목당 질문 3개라 `/embed`는 최대 48건으로 그쪽 상한 128에 여유가 있다.

### D6. 모델 slug 불일치는 실패로 남긴다

응답 slug가 설정과 다르면 저장하지 않는다. 설정값으로 적으면 벡터의 출처를 속이고, 응답값으로
적으면 다음 tick 시딩이 같은 항목을 다시 집어 **무한 재임베딩**이 된다. 어느 쪽도 조용히 넘길 수
없어 실패로 남기고 `attempts` 상한이 낭비를 막는다.

### D7. 차원 1024 / HNSW 없음

`openai/text-embedding-3-large`, 1024로 절단(Matryoshka, 한국어 평가셋에서 원본과 동등).
자른 이유는 **pgvector 인덱스의 2000차원 상한** — 나중에 인덱스를 붙일 문을 열어 둔다.
지금은 이슈 지시대로 HNSW를 만들지 않는다.

---

## 4. 검증

- `./mvnw test` → **160/160 통과**(신규 9개 포함). 리베이스 전 빨갛던 1건도 초록.
- SKIP LOCKED 테스트는 **변이 검증**을 거쳤다: SQL에서 `SKIP LOCKED`를 빼면 그 테스트만 실패한다.
  (겹침만 보면 락 없이도 통과할 수 있어, "두 번째 claim이 첫 트랜잭션을 기다리지 않았다"까지 본다.)

## 5. 리스크

| 리스크 | 대응 |
|---|---|
| **RDS에 pgvector가 없으면 배포 시 마이그레이션 실패** | 배포 전 확인 필요. 07-28 플랜 §4.4에서 이미 미결로 남아 있던 항목이다 |
| 개발자 로컬 DB(`postgres:16`)에서 앱 부팅 시 Flyway 실패 | 로컬 컨테이너를 pgvector 이미지로 교체해야 한다. 테스트는 Testcontainers라 무관 |
| Agent 실연동 미검증 | 워커 테스트는 대역을 쓴다. 실제 `/embed`·`/knowledge-queries` 왕복은 확인하지 못했다 |
| colima 환경변수 2개 필요 | 실패 시 안내 메시지로 노출 |
