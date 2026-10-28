# QA 영상 저장과 TC 검증 시도 연결

QA 영상은 SDK에서 orchestration이 관리하는 `media-worker`로 전송한다. worker는 동일 영상을 브라우저에 중계하고 H.264 MP4 segment로 인코딩한다. 브라우저 접속과 QA 녹화의 수명은 별도로 관리한다.

## 서비스 설정

`docker compose -f compose.media.yml up -d --build`로 내부 `app-net`에 worker를 올린다. 외부 HTTP 포트는 공개하지 않는다. orchestration에는 `ARTEL_MEDIA_WORKER_URL=http://qa-media-worker:8090`을 지정한다. worker의 `ORCHESTRATION_INTERNAL_URL`은 orchestration 내부 API 주소여야 한다.

WebRTC 연결에는 배포 환경에서 도달 가능한 TURN 서버가 필요하다. 기존 `ARTEL_TURN_URL`, `ARTEL_TURN_USERNAME`, `ARTEL_TURN_CREDENTIAL` 설정을 worker·SDK·브라우저 연결에 사용한다. 컨테이너 내부 주소만 있는 환경에서 STUN만으로 연결된다고 가정하지 않는다.

S3 저장은 기존 `DocumentStorage` 설정을 사용한다. 객체 prefix는 `qa-video/`다. 브라우저는 프로젝트 접근 확인 후 발급한 짧은 유효기간의 URL로 영상을 읽는다. bucket은 공개하지 않는다.

worker가 미설정이거나 녹화에 실패해도 QA는 계속된다. 화면에는 녹화 오류 또는 누락 구간이 표시된다. worker 주소를 설정하지 않은 배포에서는 기존 직접 스트리밍이 유지되고 녹화는 제공되지 않는다.

## 녹화와 보관

- 같은 `qa_run`의 시나리오들은 전체 녹화 하나를 공유한다. 단독 `qa_try`는 별도 녹화를 사용한다.
- 사용자 질문으로 agent가 대기하는 동안에도 게임 영상은 계속 저장한다.
- 기본 segment는 60초다. 업로드 대상은 최대 32 MiB이며, 대기열은 ingress별 10개·256 MiB로 제한한다. 업로드는 3회 시도하고 이후 해당 구간을 누락으로 남긴다.
- 녹화는 QA 종료 5초 뒤까지 유지한다. 각 segment 완료 시각부터 30일 후 객체를 삭제한다. QA가 30일 넘게 대기 중이어도 오래된 segment부터 삭제하며 평가·issue·판정 이력은 유지한다.
- 삭제 작업은 orchestration의 주기 작업이 실행한다. 저장소 삭제 오류는 다음 주기에 재시도한다. 운영에서는 작업 실패와 남은 만료 객체 수를 함께 점검해야 한다.
- worker의 임시 segment 디렉터리는 전용 공간이다. 재시작 시 미완성 파일을 복구하지 않으며, 저장되지 않은 구간은 재생할 수 없다.

## TC 판정과 평가

`start_test_case`가 검증 시도 UUID를 만들고 `report_test_case`가 결과를 기록한다. step은 참고 절차이고 별도 판정·완료 조건이 없다. 같은 TC를 재검증할 때는 새 시도를 시작한다. 재생 범위는 검증 시작 10초 전부터 결과 보고 5초 후까지이며 녹화 경계에 맞춰 제한한다.

agent가 실제로 받은 TC snapshot을 시도에 보존한다. 사람의 마지막 평가를 현재 결과로 사용하고 모든 수정은 revision으로 남긴다. 원래 agent 판정은 바꾸지 않는다. 다음 실행에는 같은 TC의 최근 평가를 참고 자료로 전달하며, 이전 TC 내용과 현재 내용의 차이를 표시한다.

신뢰도는 사용자가 검토한 시도에서 계산한다. 오탐률은 `FP / (FP + TN)`, 미탐률은 `FN / (TP + FN)`이다. 판단 불가·중단을 사람이 실제 실패로 확인하면 미탐에 포함한다. 분모가 0이면 비율은 `null`이다. 일별 추이는 검증 시작 시각을 `Asia/Seoul` 날짜로 묶으며 평가가 수정되면 과거 집계도 갱신한다.

## 사용자 질문과 issue

질문은 DB에 저장하며 첫 프로젝트 멤버의 답변만 채택한다. QA 종료 시 남은 질문도 취소한다. 브라우저 알림은 로그인한 앱이 열려 있을 때 구독 프로젝트 전체의 SSE를 통해 전달한다. 브라우저 권한이 없어도 화면에서 답할 수 있다. 닫힌 브라우저로 전달하는 Web Push는 포함하지 않는다.

TC 실패는 같은 TC의 열린 issue에 모은다. 사람이 해결한 후 재실패하면 이전 issue를 연결한 새 issue를 만든다. 오탐 평가로 유효 실패가 없어지면 자동 종료하고, 그 평가가 실제 실패로 바뀌면 원래 자동 종료된 issue를 다시 연다. 이미 다른 열린 issue가 있다면 발생 이력을 원래 issue에 합치고 연결 관계를 보존한다.

agent의 `report_issue`는 TC와 무관한 별도 결함에 사용한다. 반복 실패 이력에서 TC와 해당 QA history로 이동할 수 있다.
