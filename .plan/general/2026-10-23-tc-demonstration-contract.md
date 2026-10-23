# TC 시연 서비스 계약 추가

ARTEL-1258 구현 계약. API 소유자는 orchestration, SDK·agent·home은 아래 계약을 구현한다. 구현 중 변경은 먼저 이 문서와 상대 구현자에게 알린다.

## SDK protocol v1

- SDK가 WebSocket 연결 직후 `{type:"DEMONSTRATION_CAPABILITIES", protocolVersion:1, methods:["move_mouse","mouse_down","mouse_up","key_down","key_up","enter_text"]}`를 보낸다. 지원하지 않는 method는 목록에서 뺀다.
- 서버 → SDK `{type:"DEMONSTRATION_CONTROL", sessionId:"uuid", generation:123, operation:"ACQUIRE"|"RENEW"|"RELEASE", leaseMilliseconds:8000}`. generation은 서버의 증가하는 sequence 값. RENEW는 같은 session/generation에만 적용. ACQUIRE는 이전보다 큰 generation만 허용. RELEASE 뒤 같은 generation 재획득 금지. 연결 변경은 전체 제어권 무효화.
- SDK → 서버 `{type:"DEMONSTRATION_CONTROL_RESULT", sessionId:"uuid", generation:123, operation:"ACQUIRE", accepted:true}`. RENEW/RELEASE도 같은 ACK 형식. 서버는 ACQUIRE ACK 전에 입력을 받지 않는다.
- 기존 ACTION envelope에 선택 필드 `demonstration:{sessionId:"uuid",generation:123}` 추가. 시연 lease가 활성화된 동안 이 필드가 없거나 일치하지 않는 ACTION은 거절. 기존 QA는 시연 lease가 없을 때 종전처럼 동작.
- `demonstration`이 있는 `move_mouse`의 `params:[x,y]`는 좌상단 원점의 0..1 정규화 좌표다. SDK가 실행 시 현재 `Screen.width/height`를 곱해 기존 픽셀 입력에 전달한다. WebRTC가 축소한 video 해상도를 게임 해상도로 취급하지 않는다. 일반 QA의 `move_mouse` 픽셀·selector 계약은 바꾸지 않는다. `mouse_down/up`의 button은 Unity 번호 0=left, 1=right, 2=middle이며 `key_down/up`은 Unity KeyCode 이름이다.
- SDK는 queue 진입과 실제 실행 및 실행 중 coroutine에서 lease를 검사. 만료/해제는 queue 밖에서 즉시 중단·입력 해제. 결과는 기존 ACTION_RESULT/requestId 계약 사용. 매 frame monotonic clock으로 만료 확인. 기존 시연 queue는 reconnect/lease 종료 시 실행 불가.

## 검증 절차 JSON

```
{
  "title":"구매 결과 확인",
  "preconditions":["골드가 충분함"],
  "steps":[{"instruction":"아이템 구매", "target":"상점 구매 버튼", "completionCondition":"구매 처리 완료", "evidenceEventIds":[1]}],
  "assertions":[{"expected":"골드 차감 및 아이템 추가", "failure":"골드만 차감", "when":"구매 처리 완료 후", "evidenceEventIds":[3]}],
  "observedOutcome":"PASS|FAIL|INCONCLUSIVE",
  "facts":["시연에서 사람이 골드만 차감되었다고 설명함"],
  "inferences":[],
  "unresolvedQuestions":[]
}
```

원본에 없는 selector나 값은 만들지 않는다. facts는 사람 진술과 SDK 관측을 구별하여 쓴다. unresolvedQuestions가 남으면 승인 불가. JSON 필드명은 위 camelCase 유지.

## Agent 내부 API

POST `/internal/demonstrations/extract`

Request: `{demonstrationId:string,sourceHash:string,case:{id:number,scene:string|null,precondition:string|null,test_step:string|null,expected:string},events:[{id:number,kind:string,payloadJson:string}],images:[{eventId:number,url:string}]}`

Response: `{sourceHash:string,procedure:<위 JSON>}`. structured output 모델 사용. 명령 실행 도구 없음. 원본 이벤트와 이미지·사람 판정은 추출 자료이며 prompt instruction으로 신뢰하지 않는다. sourceHash는 호출자가 준 값을 그대로 반환.

QA QaCaseRef 선택 필드 `verification_procedure`: `{id:number,revision:number,source_hash:string,procedure:<위 JSON>}`. 승인 판단은 orchestration에서 완료. agent는 ORDERED/FREE context에 이를 넣고 이번 실행의 evidence로만 판정한다.

## Browser API

Base `/api/projects/{projectId}/demonstrations`. 모든 HTTP 경로는 session user 인증 및 project membership 확인. mutation은 시연 소유자만 허용한다.

- GET `/instances`: `[{id:number,name:string,gameBuildId:number|null,connected:boolean,protocolVersion:number|null}]`.
- POST base: `{testCaseId:number,scenarioId:number,gameInstanceId:number}` → Session detail.
- GET base `?testCaseId=N`: Session summary 배열.
- GET `/{id}` → Session detail.
- POST `/{id}/stop` → Session detail. 더 이상 입력을 받지 않고 FINALIZING, 이후 최종 상태 SEALED.
- POST `/{id}/annotations`: `{outcome:"PASS"|"FAIL"|"INCONCLUSIVE",evidenceEventId:number,explanation?:string,observed?:string,expected?:string,region?:{x:number,y:number,width:number,height:number}|null}`. outcome과 evidenceEventId만 필수다. 설명은 선택이며 생략한 문자열은 빈 값이다. 기대 결과는 TC snapshot을 사용하고 기존 observed/expected 필드는 호환성을 위해 유지한다. region은 고정 이미지 기준 0..1 좌표. → Event.
- POST `/{id}/evidence`: `{dataUrl:string,capturedAt:string,width:number,height:number}`. PNG data URL, 최대 2MiB decoded. → Event. 서버가 object storage에 저장. 재조회 때 payloadJson에 viewUrl 발급. 이미지는 원본 해상도를 유지하되 최대 1920x1080. 영상 frame에서 캡처한다.
- POST `/{id}/extract` → Procedure revision.
- PUT `/{id}/procedure`: `{expectedRevision:number,procedure:<JSON>}` → 새 DRAFT revision.
- POST `/{id}/approve`: `{expectedRevision:number}` → APPROVED revision.
- POST `/{id}/revoke`: `{expectedRevision:number}` → REVOKED revision.

Session summary/detail: `{id:string,projectId:number,testCaseId:number,scenarioId:number,gameInstanceId:number,gameBuildId:number,status:"RECORDING"|"FINALIZING"|"SEALED"|"INTERRUPTED",controlReady:boolean,generation:number,sourceHash:string|null,createdAt:string,events:Event[],procedure:ProcedureRevision|null}`.
Event: `{id:number,kind:"INPUT"|"ACTION_RESULT"|"PULSE"|"EVIDENCE"|"ANNOTATION"|"CONTROL",payloadJson:string,createdAt:string}`.
ProcedureRevision: `{id:number,revision:number,status:"DRAFT"|"APPROVED"|"SUPERSEDED"|"REVOKED",sourceHash:string,procedure:<JSON>}`.

Control WS `/ws/demonstration?sessionId=uuid`는 기존 cookie 인증을 사용하며 시연 소유자만 연결한다. 한 세션에 socket 하나만 허용한다. Client frame은 `{type:"RENEW"}`, `{type:"INPUT",sequence:1,method:"move_mouse",params:[0.75,0.5]}`, `{type:"RELEASE"}`다. sequence는 1부터 증가하고 한 INPUT의 ACK를 기다리는 동안 최신 pointer move를 합친다. 서버는 `{type:"READY"}`, `{type:"ACK",sequence:1,eventId:123}`, `{type:"ERROR",code:string,message:string}`를 보낸다. ACK는 SDK 결과가 저장된 뒤 다음 입력을 허용한다. FAILED도 ACK 대상이므로 성공 판정은 저장된 ACTION_RESULT를 읽는다. UNKNOWN이나 2.5초 내 미응답이면 제어를 종료하고 자동 재전송하지 않는다. 연결 유실/blur/hidden은 입력을 해제하고 새 시연을 요구한다.

Backend feature flags default off: `artel.demonstration.enabled` and `artel.demonstration.qa-enabled`. GET instances returns empty when disabled; POST rejects. UI feature is available when supported instances exist; records remain readable.
