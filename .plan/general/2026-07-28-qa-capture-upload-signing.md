# 2026-07-28 — QA 캡처 업로드 서명과 증거 적재

- Date: 2026-07-28
- Jira: ARTEL-142
- Status: Implemented

## Goal

SDK가 만든 화면 캡처를 스토리지에 올릴 수 있도록 서명을 발급하고, 그 캡처가 있었다는
사실을 QA 타임라인에 남긴다. 이미지 바이트는 이 서버를 지나가지 않는다.

## Non-goals

- 캡처 생성·업로드 실행(SDK, ARTEL-141)
- 이미지를 모델에 넣는 일(Agent, ARTEL-143)
- artel-home 표시
- 기획서 저장소 로직 변경

## Context / Constraints

**바이트를 WebSocket에 실으면 안 된다.** `QaSdkBridgeService`가 중계 프레임 전체를
`qa_log.payload`에 적재하고 SSE로 발행하며, `ActionResultMessageHandler`가 payload 전문을
INFO로 로깅한다. 캡처 한 장이 이 경로를 지나면 Postgres·SSE·로그가 함께 부푼다.

**서명은 이벤트 루프에서 안전하다.** presign은 로컬 HMAC 계산이라 네트워크 I/O가 없다
(`S3DocumentStorage` 클래스 주석). 반면 바이트 중계를 넣으면 WebFlux 이벤트 루프에
메가바이트 버퍼가 생긴다. 그래서 `DocumentStorage`에 `put`을 추가하지 않았다.

**게임 머신이 스토리지에 직접 닿아야 성립한다.** `artel.storage.endpoint`는 기본이 비어
있어 실제 AWS S3(공개 인터넷)를 가리키고, 로컬은 개발자가 지정한 MinIO다. 두 구성 모두
게임이 도는 머신에서 닿는다. 닿지 않는 배포가 생기면 `DocumentStorage.put`과 본문
스트리밍을 추가해 오케스트레이션 경유로 바꿔야 한다.

## 구현 중 드러난 충돌

**1. 다운로드 TTL이 QA 런보다 짧았다.** `StorageProperties.downloadUrlTtl` 기본값은 5분인데
Agent의 `RUN_DEADLINE_SECONDS`는 600초다. 이 값을 그대로 쓰면 런 초반에 찍은 캡처를 후반에
열지 못하고, 증상은 "이미지를 받지 못했다"라는 모호한 실패로만 나타난다.

기존 값을 올리지 않았다. 사람이 즉시 클릭하는 기획서 링크와 진행 중인 런이 끝날 때까지
살아야 하는 캡처 링크는 필요한 수명이 다르다. `captureDownloadUrlTtl`(기본 30분)을 따로
두고, 런 데드라인보다 짧으면 기동 시 `require`로 막는다.

이를 위해 `DocumentStorage.presignDownload`에 `ttl` 파라미터를 더했다. 기본값 null이라
기존 두 호출부(기획서 다운로드, 레퍼런스 컨텍스트 추출)는 그대로다.

**2. `SCREENSHOT`은 DB CHECK 제약에 없었다.** `V11`이 `qa_log_type_check`로 타입을 7개로
고정해 두어, 마이그레이션 없이 넣으면 insert가 제약 위반으로 터진다. `V16`으로 제약을 다시
만들고 `QaLogService.TYPES`에도 같이 넣었다. 두 곳이 따로 있으므로 한쪽만 고치면 통과하는
경로가 생긴다.

**3. `returnValue` 중계는 코드 변경이 필요 없었다.** `routeActionResult`가
`objectMapper.readTree(payloadText)`로 통째 파싱해 그 `JsonNode`를 그대로 넘긴다. 다만
누군가 이 자리를 타입 고정 DTO로 바꾸면 필드가 조용히 사라지므로 회귀 테스트를 남겼다.

## 설계 결정

**SCREENSHOT 로그를 티켓 발급 시점에 남긴다.** 업로드 성공 후 두 번째 호출로 확정하는 쪽이
정확하지만, 실패한 업로드는 곧 실패한 액션이고 그 실패는 ACTION_RESULT에 이미 남는다.
왕복을 한 번 더 두면 캡처마다 라운드트립이 늘 뿐 리뷰어가 얻는 것이 없다.

**활성 try가 없으면 409다.** 인스턴스는 존재하고 요청도 올바르다. 지금 이 게임이 QA 실행
중이 아니라는 상태 충돌이므로, SDK가 다시 붙어도 결과가 달라지지 않는다.

**엔드포인트는 `/api/sdk/**` 아래에 두고 인스턴스를 `instanceKey`로 지목한다.** 게임을
실행하는 쪽에는 로그인 세션이 없어 엔드유저 JWT로 막을 수 없다. 처음에는 `gameInstanceId`를
받게 만들었는데, 그러면 순번을 훑어 실행 중인 아무 QA의 프리픽스에나 쓰는 서명을 받아낼 수
있다. `instanceKey`는 등록이 이미 쓰는 자격증명이고 SDK가 반드시 보관하고 있다(등록 응답의
`instanceId`는 지금 파싱조차 되지 않으므로 SDK는 자기 인스턴스 id를 알지도 못한다).
키가 없으면 404 — 등록과 같은 이유로, 호출자에게는 다시 시도할 realm이 없다.

**허용 형식과 상한은 설정이 아니라 상수다.** 배포마다 다르면 특정 환경에서만 열리는 캡처가
생긴다(`StorageProperties`가 기획서 형식에 대해 이미 내린 결정과 같다). 크기 상한만
운영 여유를 위해 프로퍼티로 뒀다.

## 변경 목록

- `V16__add_qa_log_screenshot_type.sql` — `qa_log_type_check`에 `SCREENSHOT` 추가
- `QaLogService` — 애플리케이션 쪽 타입 허용 목록 동기화
- `StorageProperties` — `captureDownloadUrlTtl`, `maxCaptureBytes`, 런 데드라인 검증
- `DocumentStorage`/`S3DocumentStorage` — `presignDownload(ttl)` 오버라이드 가능화
- `QaCaptureService`/`QaCaptureController`/`QaCaptureDtos` — 티켓 발급 경로
- `SecurityConfig` — `/api/sdk/qa-captures/**` permitAll

## Validation

- `./mvnw test` — 133건 중 132건 통과.
- 실패 1건(`ProjectDocumentIntegrationTest.assigns distinct versions to concurrent uploads`)은
  `origin/develop`에서도 같은 방식으로 실패하는 기존 결함이다. 동시 업로드에서 유니크 제약
  위반이 트랜잭션을 중단시키고, 버전 재계산이 중단된 커넥션을 다시 쓰면서 500이 된다.
  이 작업 범위 밖이라 별도 이슈로 넘긴다.
