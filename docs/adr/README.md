# Architecture Decision Records

- 이 저장소의 모양을 정한 결정 일곱입니다.
- 나머지 결정은 아래 「그 밖의 결정」 이 가리키는 `.plan/general/` 문서에 있습니다.
- 프로토콜 계약 셋은 ADR 이 아니라 `docs/` 아래 제 문서로 삽니다. 아래 「문서가 결정을 이미 담은 곳」 을
  보십시오.

| ADR | 제목 | 상태 |
| --- | --- | --- |
| [0001](0001-internal-port.md) | `/internal` 을 별도 포트와 두 번째 `HttpHandler` 체인으로 연다 | 확정 |
| [0002](0002-content-map-per-build.md) | content_map 을 게임 빌드당 하나로 모은다 | 뒤집힘 |
| [0003](0003-capability-readiness-axes.md) | capability 준비도를 세 축으로 가르고 `status` 를 생성 컬럼으로 둔다 | 확정 |
| [0004](0004-scenario-cases-as-json.md) | TestScenario 가 case 를 junction 이 아니라 `steps` JSONB 안 참조로 든다 | 뒤집힘 |
| [0005](0005-knowledge-relations.md) | knowledge 관계 어휘를 닫고 `LEADS_TO` 를 읽기 전용으로 동결한다 | 뒤집힘 |
| [0006](0006-websocket-frame-limit.md) | WebSocket 메시지 상한을 256 KB 로 두고 `WebFluxConfigurer` 로 적용한다 | 확정 |
| [0007](0007-qa-run-rollup.md) | `qa_run` 을 자식이 모두 끝날 때 롤업해 닫는다 | 확정 |

## 문서가 결정을 이미 담은 곳

- 이 셋은 계약 문서이면서 그 계약을 그렇게 정한 이유를 함께 적습니다. ADR 로 옮기지 않았습니다.

| 문서 | 그 안에 있는 결정 |
| --- | --- |
| [`../streaming-protocol.md`](../streaming-protocol.md) | `streamId` 를 모든 signalling 에 싣는 이유, 뷰어를 newest-wins 로 받는 이유, `4009` 가 terminal 인 이유, lease 를 90 초로 잡은 계산 |
| [`../capability-write-frames.md`](../capability-write-frames.md) | frame 셋의 모양이 강제하는 규칙 넷, 거절 사유 목록, agent 가 지금 고칠 수 있는 것과 없는 것 |
| [`../screen-selector-frames.md`](../screen-selector-frames.md) | screen 을 `discriminator` 로 식별하는 이유, whitelist 를 두는 이유, 빈 `discriminator` 가 메시지인 이유 |

- **스트리밍 프로토콜의 이유를 찾고 있다면 ADR 이 아니라 `streaming-protocol.md` 입니다.**

## 그 밖의 결정

- `.plan/general/` 에 문서 110 개와 첨부 셋, `.plan/issues/` 에 4 개가 있습니다.
- ADR 로 옮기지 않은 결정의 이유는 전부 거기 있습니다. 아래는 그것을 주제별로 묶은 것입니다.

### content_map 과 evidence

| 문서 | 무엇에 답하나 |
| --- | --- |
| [`2026-08-18-create-content-map-schema.md`](../../.plan/general/2026-08-18-create-content-map-schema.md) | 스키마 전체의 모양, `capability_evidence` 를 뗀 이유 |
| [`2026-08-18-receive-evidence-document.md`](../../.plan/general/2026-08-18-receive-evidence-document.md) | SDK 가 올리는 evidence 문서를 받는 경로 |
| [`2026-08-19-ingest-evidence-into-content-map.md`](../../.plan/general/2026-08-19-ingest-evidence-into-content-map.md) | 문서를 지도 행으로 옮기는 규칙 |
| [`2026-08-19-join-evidence-halves-into-scene-candidates.md`](../../.plan/general/2026-08-19-join-evidence-halves-into-scene-candidates.md) | 반쪽 둘을 씬 후보로 잇는 법 |
| [`2026-08-19-carry-il-offset-and-stable-capability-key.md`](../../.plan/general/2026-08-19-carry-il-offset-and-stable-capability-key.md) | 재스캔에도 안 변하는 capability 키 |
| [`2026-08-28-one-content-map-per-build.md`](../../.plan/general/2026-08-28-one-content-map-per-build.md) | ADR 0002 의 실행 계획과 Risks |
| [`2026-08-29-attribute-persistent-objects-to-real-scenes.md`](../../.plan/general/2026-08-29-attribute-persistent-objects-to-real-scenes.md) | `DontDestroyOnLoad` 객체를 어느 씬으로 치나 |
| [`2026-09-01-make-a-scene-the-evidence-never-named.md`](../../.plan/general/2026-09-01-make-a-scene-the-evidence-never-named.md) | evidence 가 이름 붙인 적 없는 씬을 만드는 이유 |
| [`2026-09-01-read-the-maps-new-shape-and-rebuild-cases.md`](../../.plan/general/2026-09-01-read-the-maps-new-shape-and-rebuild-cases.md) | 새 모양을 읽어 케이스를 다시 세우는 절차 |
| [`2026-08-18-render-evidence-to-pseudo-csharp.md`](../../.plan/general/2026-08-18-render-evidence-to-pseudo-csharp.md) | evidence 를 사람이 읽을 C# 비슷한 글로 그리는 이유 |
| [`2026-08-21-trigger-remote-evidence-scan.md`](../../.plan/general/2026-08-21-trigger-remote-evidence-scan.md) | 스캔을 원격으로 거는 경로 |
| [`2026-09-03-content-map-scan-ingest-sse.md`](../../.plan/general/2026-09-03-content-map-scan-ingest-sse.md) | 스캔 진행을 SSE 로 내보내는 이유 |

### capability 와 screen

| 문서 | 무엇에 답하나 |
| --- | --- |
| [`2026-08-19-split-status-into-three-readiness-axes.md`](../../.plan/general/2026-08-19-split-status-into-three-readiness-axes.md) | ADR 0003 의 실행 계획 |
| [`2026-08-19-mark-repeat-until-done-interactions.md`](../../.plan/general/2026-08-19-mark-repeat-until-done-interactions.md) | 될 때까지 반복하는 조작을 표시하는 이유 |
| [`2026-08-19-record-proof-chain-and-step-resolution.md`](../../.plan/general/2026-08-19-record-proof-chain-and-step-resolution.md) | 어떤 근거로 그 스텝이 나왔는지 남기는 법 |
| [`2026-08-19-record-spawn-origin-on-evidence-rows.md`](../../.plan/general/2026-08-19-record-spawn-origin-on-evidence-rows.md) | 객체를 만든 자리를 적는 이유 |
| [`2026-08-28-whitelist-screen-defining-selectors.md`](../../.plan/general/2026-08-28-whitelist-screen-defining-selectors.md) | screen 을 정하는 selector 를 whitelist 로 받는 이유 |
| [`2026-08-28-ask-about-unknown-screen-selectors.md`](../../.plan/general/2026-08-28-ask-about-unknown-screen-selectors.md) | 모르는 selector 를 agent 에게 묻는 흐름 |
| [`2026-08-28-tell-the-agent-which-screen-settled.md`](../../.plan/general/2026-08-28-tell-the-agent-which-screen-settled.md) | 어느 screen 에 앉았는지 알려 주는 이유 |
| [`2026-08-27-split-screens-from-readings.md`](../../.plan/general/2026-08-27-split-screens-from-readings.md) | screen 을 `pulse` 에서 떼어 낸 이유 |
| [`2026-08-28-carry-screen-capabilities.md`](../../.plan/general/2026-08-28-carry-screen-capabilities.md) | screen 마다 무엇을 할 수 있는지 싣는 법 |
| [`2026-08-29-let-the-agent-see-every-capability.md`](../../.plan/general/2026-08-29-let-the-agent-see-every-capability.md) | 전부 보여 줄지 걸러 줄지 |
| [`2026-08-29-record-what-the-agent-saw-on-capabilities.md`](../../.plan/general/2026-08-29-record-what-the-agent-saw-on-capabilities.md) | 런이 배운 것을 지도에 다시 적는 경로 |
| [`2026-08-21-derive-scene-edges-from-scene-effects.md`](../../.plan/general/2026-08-21-derive-scene-edges-from-scene-effects.md) | 씬 사이 edge 를 효과에서 끌어내는 법 |

### knowledge

| 문서 | 무엇에 답하나 |
| --- | --- |
| [`2026-07-24-knowledge-fact-domain-design.md`](../../.plan/general/2026-07-24-knowledge-fact-domain-design.md) | 첫 설계 |
| [`2026-07-26-knowledge-domain-hybrid-search-redesign.md`](../../.plan/general/2026-07-26-knowledge-domain-hybrid-search-redesign.md) | 벡터와 키워드를 섞는 이유 |
| [`2026-07-29-knowledge-pgvector-backfill-worker.md`](../../.plan/general/2026-07-29-knowledge-pgvector-backfill-worker.md) | 임베딩을 저장 경로가 아니라 worker 로 뺀 이유 |
| [`2026-08-06-knowledge-edge-graph.md`](../../.plan/general/2026-08-06-knowledge-edge-graph.md) | ADR 0005 의 어휘를 닫은 문서 |
| [`2026-08-27-freeze-leads-to-writes.md`](../../.plan/general/2026-08-27-freeze-leads-to-writes.md) | ADR 0005 의 동결 문서 |
| [`2026-08-05-isolate-knowledge-scope-per-qa-run.md`](../../.plan/general/2026-08-05-isolate-knowledge-scope-per-qa-run.md) | 런마다 knowledge 범위를 가르는 이유 |
| [`2026-08-11-knowledge-citation-recording.md`](../../.plan/general/2026-08-11-knowledge-citation-recording.md) | 무엇을 인용했는지 남기는 법 |
| [`2026-08-27-knowledge-screen-anchor.md`](../../.plan/general/2026-08-27-knowledge-screen-anchor.md) | knowledge 를 screen 에 `anchor` 로 묶는 이유 |
| [`2026-09-02-document-node-and-part-of-edge-on-knowledge-ingest.md`](../../.plan/general/2026-09-02-document-node-and-part-of-edge-on-knowledge-ingest.md) | `PART_OF` 를 적재 때만 쓰는 이유 |
| [`2026-09-01-delete-planning-document-and-its-knowledge.md`](../../.plan/general/2026-09-01-delete-planning-document-and-its-knowledge.md) | 문서를 지울 때 그 knowledge 를 어떻게 하나 |

### QA 런

| 문서 | 무엇에 답하나 |
| --- | --- |
| [`2026-08-05-persist-qa-run-config.md`](../../.plan/general/2026-08-05-persist-qa-run-config.md) | 런 설정을 스냅샷으로 남기는 이유 |
| [`2026-08-05-qa-run-config-stats-endpoint.md`](../../.plan/general/2026-08-05-qa-run-config-stats-endpoint.md) | 설정 4-튜플로 접어 보는 집계 |
| [`2026-08-11-promote-qa-verdict-to-qa-try.md`](../../.plan/general/2026-08-11-promote-qa-verdict-to-qa-try.md) | 판정을 `qa_try` 컬럼으로 올린 이유 |
| [`2026-08-11-expected-step-labels-and-grading.md`](../../.plan/general/2026-08-11-expected-step-labels-and-grading.md) | 기대 라벨과 채점 |
| [`2026-08-24-start-readings-when-a-run-starts.md`](../../.plan/general/2026-08-24-start-readings-when-a-run-starts.md) | `pulse` 를 연결이 아니라 런에서 시작하는 이유 |
| [`2026-08-27-scene-context-for-agent-run-start.md`](../../.plan/general/2026-08-27-scene-context-for-agent-run-start.md) | 런을 열 때 주는 문맥 |
| [`2026-07-28-qa-capture-upload-signing.md`](../../.plan/general/2026-07-28-qa-capture-upload-signing.md) | screen capture 업로드를 서명 URL 로 받는 이유 |
| [`2026-07-30-cache-qa-model-catalog.md`](../../.plan/general/2026-07-30-cache-qa-model-catalog.md) | 모델 카탈로그를 캐시하는 이유 |
| [`2026-08-27-accept-tool-log-types.md`](../../.plan/general/2026-08-27-accept-tool-log-types.md) | tool 로그 종류를 받는 범위 |
| [`2026-09-01-qa-run-id-on-try-issue-knowledge-responses.md`](../../.plan/general/2026-09-01-qa-run-id-on-try-issue-knowledge-responses.md) | 응답마다 런 id 를 싣는 이유 |

### 시나리오·케이스 생성

| 문서 | 무엇에 답하나 |
| --- | --- |
| [`2026-07-28-testcase-scenario-run-redesign-overview.md`](../../.plan/general/2026-07-28-testcase-scenario-run-redesign-overview.md) | 세 단계 재설계의 전체 그림과 교차 결정 |
| [`2026-07-28-run-scenario-case-hierarchy-and-schema.md`](../../.plan/general/2026-07-28-run-scenario-case-hierarchy-and-schema.md) | ADR 0004 의 열린 질문이 적힌 곳 |
| [`2026-08-07-qa-step-model-redesign-followups.md`](../../.plan/general/2026-08-07-qa-step-model-redesign-followups.md) | step 모델의 현재 계약 |
| [`2026-08-11-promote-scenario-payload-to-columns.md`](../../.plan/general/2026-08-11-promote-scenario-payload-to-columns.md) | `payload` 를 컬럼 셋으로 나눈 이유 |
| [`2026-07-21-testscenario-chatbot-pipeline.md`](../../.plan/general/2026-07-21-testscenario-chatbot-pipeline.md) | 시나리오를 만드는 챗봇의 첫 파이프라인 |
| [`2026-08-04-run-scoped-authoring-chatbot-add-edit-card-commit.md`](../../.plan/general/2026-08-04-run-scoped-authoring-chatbot-add-edit-card-commit.md) | 대화의 주체가 시나리오가 아니라 런인 이유 |
| [`2026-08-13-authoring-coverage-trust-and-recommendation.md`](../../.plan/general/2026-08-13-authoring-coverage-trust-and-recommendation.md) | 커버리지와 추천 |
| [`2026-08-29-first-pass-authoring-accuracy.md`](../../.plan/general/2026-08-29-first-pass-authoring-accuracy.md) | 첫 응답의 정확도를 올리는 방법 |
| [`2026-08-31-split-action-and-observation-test-cases.md`](../../.plan/general/2026-08-31-split-action-and-observation-test-cases.md) | 조작 케이스와 관측 케이스를 가르는 이유 |
| [`2026-09-04-remove-the-pairwise-flow-matrix.md`](../../.plan/general/2026-09-04-remove-the-pairwise-flow-matrix.md) | 쌍별 matrix 를 걷어낸 이유 |
| [`2026-07-31-testcase-vector-and-multi-scenario-authoring.md`](../../.plan/general/2026-07-31-testcase-vector-and-multi-scenario-authoring.md) | 케이스 벡터 검색과 여러 시나리오 생성 |

### 인증과 접근

| 문서 | 무엇에 답하나 |
| --- | --- |
| [`2026-07-31-move-sdk-login-code-store-to-redis.md`](../../.plan/general/2026-07-31-move-sdk-login-code-store-to-redis.md) | SDK 로그인 code 저장소를 Redis 로 옮긴 이유 |
| [`2026-09-03-mint-an-sdk-token-for-the-signed-in-user.md`](../../.plan/general/2026-09-03-mint-an-sdk-token-for-the-signed-in-user.md) | 로그인한 사용자에게 SDK 토큰을 발급하는 경로 |
| [`2026-09-03-cli-token-issue-list-revoke-api.md`](../../.plan/general/2026-09-03-cli-token-issue-list-revoke-api.md) | CLI token 의 발급·목록·폐기 |
| [`2026-09-01-developer-platform-role-widens-stats-reads.md`](../../.plan/general/2026-09-01-developer-platform-role-widens-stats-reads.md) | `DEVELOPER` 등급이 조회만 여는 이유 |
| [`2026-08-11-consolidate-requireuser-into-argument-resolver.md`](../../.plan/general/2026-08-11-consolidate-requireuser-into-argument-resolver.md) | 사용자 해석을 argument resolver 하나로 모은 이유 |
| [`2026-08-29-invite-people-to-a-project-by-email.md`](../../.plan/general/2026-08-29-invite-people-to-a-project-by-email.md) | 초대 흐름 |
| [`2026-09-03-invite-by-account-without-email.md`](../../.plan/general/2026-09-03-invite-by-account-without-email.md) | 메일 없이 계정으로 초대하는 이유 |

### 포트·인프라·배포

| 문서 | 무엇에 답하나 |
| --- | --- |
| [`2026-08-05-unify-internal-api-paths.md`](../../.plan/general/2026-08-05-unify-internal-api-paths.md) | 서버-투-서버 경로를 `/internal` 로 모은 이유 (ARTEL-265) |
| [`2026-08-06-serve-internal-api-on-separate-port.md`](../../.plan/general/2026-08-06-serve-internal-api-on-separate-port.md) | ADR 0001 의 선택지 평가가 적힌 곳 |
| [`2026-08-07-verify-flyway-upgrade-path-on-real-postgres.md`](../../.plan/general/2026-08-07-verify-flyway-upgrade-path-on-real-postgres.md) | 업그레이드 경로를 진짜 Postgres 로 재는 이유 |
| [`2026-07-19-connect-rds-db-and-flyway.md`](../../.plan/general/2026-07-19-connect-rds-db-and-flyway.md) | Flyway 를 JDBC 로 돌리는 구성 |
| [`2026-07-19-support-dotenv-environment-file.md`](../../.plan/general/2026-07-19-support-dotenv-environment-file.md) | `.env` 를 읽게 만든 결정. 테스트까지 읽는 원인 |
| [`2026-07-22-inject-env-file-from-jenkins-credentials.md`](../../.plan/general/2026-07-22-inject-env-file-from-jenkins-credentials.md) | 배포 비밀을 Secret file 로 넣는 이유 |
| [`2026-07-16-convert-properties-to-yaml.md`](../../.plan/general/2026-07-16-convert-properties-to-yaml.md) | 설정을 YAML 로 옮긴 이유 |
| [`2026-08-13-raise-stream-lease-default.md`](../../.plan/general/2026-08-13-raise-stream-lease-default.md) | lease 기본값을 90 초로 올린 계산 |

### SDK 연동의 초기 결정

| 문서 | 무엇에 답하나 |
| --- | --- |
| [`2026-07-16-implement-action-forwarding.md`](../../.plan/general/2026-07-16-implement-action-forwarding.md) | action 을 게임으로 넘기는 첫 구현 |
| [`2026-07-16-forward-action-result.md`](../../.plan/general/2026-07-16-forward-action-result.md) | 결과를 되돌리는 경로 |
| [`2026-07-16-remove-command-flow.md`](../../.plan/general/2026-07-16-remove-command-flow.md) | 첫 command 흐름을 걷어낸 이유 |
| [`2026-07-16-refactor-gamestate-transformer.md`](../../.plan/general/2026-07-16-refactor-gamestate-transformer.md) | `GAME_STATE` 변환을 정리한 이유 |
| [`2026-07-28-expose-visual-only-blocks-to-agent-state.md`](../../.plan/general/2026-07-28-expose-visual-only-blocks-to-agent-state.md) | 보이기만 하는 블록을 싣는 이유 |
| [`2026-07-28-relay-scene-screen-coordinates-to-agent-state.md`](../../.plan/general/2026-07-28-relay-scene-screen-coordinates-to-agent-state.md) | 좌표를 중계하는 형식 |
| [`2026-07-26-exclude-non-interactable-ui-from-interactables.md`](../../.plan/general/2026-07-26-exclude-non-interactable-ui-from-interactables.md) | 누를 수 없는 UI 를 목록에서 빼는 이유 |
| [`2026-08-13-sdk-performance-ingest-and-aggregation.md`](../../.plan/general/2026-08-13-sdk-performance-ingest-and-aggregation.md) | SDK 성능 지표 적재와 집계 |

### 이슈와 tracker

| 문서 | 무엇에 답하나 |
| --- | --- |
| [`2026-08-05-issue-read-and-resolve-api.md`](../../.plan/general/2026-08-05-issue-read-and-resolve-api.md) | 결함을 읽고 닫는 API |
| [`2026-08-13-answer-issue-frames.md`](../../.plan/general/2026-08-13-answer-issue-frames.md) | QA 런이 결함을 적는 frame |
| [`2026-08-28-issue-tracker-link-and-github-issue-export.md`](../../.plan/general/2026-08-28-issue-tracker-link-and-github-issue-export.md) | GitHub 이슈로 내보내는 경로 |
| [`2026-08-13-answer-knowledge-write-frames.md`](../../.plan/general/2026-08-13-answer-knowledge-write-frames.md) | knowledge 쓰기 frame 에 답하는 규칙 |
