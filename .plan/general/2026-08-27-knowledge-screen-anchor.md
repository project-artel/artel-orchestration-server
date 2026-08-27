# 2026-08-27 — knowledge_anchor 를 만들고 쓰기·조회에 싣는다

- Date: 2026-08-27
- Jira: ARTEL-591
- Status: Draft

## Goal

지식 한 항목이 **어느 씬·화면의 것인지**를 적을 자리를 만든다. QA agent 가 지식창고에
쌓아 온 화면 지도는 `content_map` 이 가져가지만(ARTEL-590, ARTEL-594), "이 화면에서만
참인 사실"은 지식창고에 남는다. 그 사실이 자기 화면을 가리킬 수 있어야 한다.

- `knowledge_anchor` 표를 만든다.
- `KNOWLEDGE_CREATE` 프레임이 실은 `anchor` 를 저장한다.
- 검색 응답이 `anchor` 를 함께 내고, `scene_name` 으로 좁힐 수 있다.
- 스코프에 가려진 지식의 `anchor` 는 조회에 나오지 않는다.

## Non-goals

- `anchor` 수정·삭제 API. 1차는 생성 시점만이다.
- agent-server(ARTEL-592) 와 home(ARTEL-593) 쪽 변경.
- `content_map` 의 screen 행 생성·조회. 여기서는 논리참조로 id 만 든다.

## Context / Constraints

- **컬럼이 아니라 표다.** 한 지식이 여러 화면에 걸릴 수 있다("전투 중 ESC 는 아무것도
  하지 않는다"는 전투 화면 셋에 걸린다). 컬럼으로 두면 첫 화면만 남고 나머지를 잃는다.
- **`scene_name` NOT NULL, `screen_id` NULL 허용.** 화면은 씬 안에 산다. 씬 이름은 게임
  상태 프레임이 매번 실어 주므로 늘 알지만, 화면은 판정이 안 될 수 있다. 반대는 없다.
- **FK 를 걸지 않는다.** knowledge 는 project·document·run 을 전부 논리참조로 든다
  (V13, V19, V28). `content_map.screen` 에 하드 FK 를 걸면 게임 빌드 삭제가 지식 삭제로
  번진다.
- **`scene_name` 을 content map 과 대조하지 않는다.** content map 이 없는 프로젝트도 씬
  이름은 있고, 검증하면 그 프로젝트의 `anchor` 가 전부 거절된다.
- **NULL 유일성은 부분 유니크 인덱스 두 벌로 잡는다.** Postgres 는 UNIQUE 에서 NULL 을
  서로 다른 값으로 보므로 `screen_id IS NULL` 쪽이 그냥 UNIQUE 로는 안 막힌다. V40 의
  `uk_screen_transition_auto` 가 같은 처리다.
- `anchor` 가 없는 지식은 **게임 전체의 사실**이다. 그것이 기본값이고 싸야 한다.
- 읽기는 전부 `KnowledgeScopeSql.VISIBLE` 하나를 지난다. `anchor` 조회도 예외가 아니다.

### 마이그레이션 번호

develop 은 V54 까지 가 있고(V40~V46, V48, V49, V51, V54 는 content map 계열), 다른
브랜치·워크트리가 V36~V54 를 이미 잡았다. 빈 번호는 33 과 47 뿐인데 둘 다 쓰면 안 된다 —
이미 더 높은 번호를 적용한 DB 에서 out-of-order 로 걸린다. **V55 를 쓴다.**

## Approach (Checklist)

- [ ] **Step 0: Recon** — V28/V29/V40 의 주석 등록, `KnowledgeScopeSql`, 검색 경로 확인
- [ ] **Step 1: Implementation**
  - `src/main/resources/db/migration/V55__create_knowledge_anchor.sql`
  - `knowledge/entity/KnowledgeAnchorEntity.kt`
  - `knowledge/repository/KnowledgeAnchorRepository.kt` — 스코프 술어를 낀 조회
  - `knowledge/dto/KnowledgeDtos.kt` — `KnowledgeMutationRequest` 에 `scene_name`/`screen_id`
  - `knowledge/dto/KnowledgeSearchDtos.kt` — `KnowledgeAnchorView`, 히트의 `anchors`,
    요청의 `scene_name`
  - `knowledge/service/KnowledgeService.kt` — `createFromQaTry` 가 `anchor` 를 같은 트랜잭션에 저장
  - `knowledge/repository/KnowledgeVectorSearchRepository.kt` — `scene_name` EXISTS 필터
  - `knowledge/service/KnowledgeSearchService.kt` — 히트에 `anchor` 를 붙이고 필터를 내린다
  - `qa/service/QaAgentInboundRouter.kt` — 검색 프레임의 `scene_name` 을 서비스로 넘긴다
- [ ] **Step 2: Tests**
  - `anchor` 저장→조회, 한 지식에 `anchor` 여럿, 중복 `anchor` 거절, `anchor` 없는 요청은 지금과 동일
  - `scene_name` 필터, 스코프에 가려진 지식의 `anchor` 가 새지 않는지
- [ ] **Step 3: Rollout / Rollback** — 순수 추가. 되돌리기는 `DROP TABLE knowledge_anchor`

## Validation

- **Commands to run:**
  - `./mvnw test`
  - `./scripts/check-flyway-migrations.sh`
  - `./scripts/verify-flyway-upgrade.sh`
- **Expected output:** 전부 통과. Flyway 가 V55 를 develop 위에 얹고 validate 를 통과한다.

## Risks & Rollback

- **Risks:**
  - 마이그레이션 번호가 아직 안 딴 브랜치와 겹칠 수 있다. `check-flyway-migrations.sh` 가
    경고로 잡는다.
  - 스코프 런이 baseline 을 고치면 그림자는 새 행이라 baseline 의 `anchor` 를 물려받지 않는다.
    지금은 실험 엔티티가 없어 발화하지 않지만, 마이그레이션 주석에 남긴다.
  - `screen_id` 는 `content_map.screen` 이 채워지기 전까지 사실상 늘 NULL 이다. 의도된
    상태이고, 그래서 `screen_id IS NULL` 쪽 유일성이 실제로 일하는 쪽이다.
- **Rollback steps:** `git revert` + `DROP TABLE knowledge_anchor` (순수 추가라 다른 경로가
  이 표를 읽지 않는다).

## Open Questions

- `anchor` 를 하나만 받을지 목록으로 받을지 — 이슈가 "생성 시점만"이라 했으므로 프레임당
  하나로 간다. 여러 화면에 거는 것은 표가 지고, 채우는 API 는 후속이다.
