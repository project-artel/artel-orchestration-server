# TC 시연 기능 제거

사람이 게임을 조작해 TC 수행 방법을 기록하고 승인된 검증 절차로 제공하던 기능을 제거했다. QA 실행 스트리밍·녹화, TC 판정 검토, 사람의 판정 오류 피드백은 유지한다.

## 기능 제거

- 시연 HTTP API와 `/ws/demonstration`, SDK 시연 capability·제어권·입력 처리 연결을 제거했다.
- ORDERED·FREE 시나리오에서 `verification_procedure`를 공급하지 않는다. 이전 snapshot의 해당 필드도 읽을 때 무시한다.
- knowledge source는 `DOCS`와 `QA`만 허용한다. 시연 knowledge는 새 migration에서 원문·embedding·사용·수정·anchor·관계 기록과 함께 삭제한다.
- 기존 SDK가 시연 capability나 제어 결과 메시지를 보내도 해당 프레임만 무시한다. SDK 연결과 일반 QA 메시지는 유지한다.

## 저장 데이터 삭제

기존 V142는 checksum을 보존하고 V148을 새로 적용한다. V148은 기존 시연 event의 `objectKey`를 `retired_demonstration_object`에 먼저 옮긴 뒤 시연 전용 테이블을 삭제한다. QA 관련 JSONB snapshot에서는 `verification_procedure`와 `verificationProcedure` 필드만 재귀적으로 제거한다. QA 영상과 판정 데이터는 삭제하지 않는다.

서버 내부의 `RetiredDemonstrationCleanup`이 시작 30초 후부터 60초 간격으로 `demonstrations/` prefix를 정리한다. DB가 참조하지 않는 orphan, S3 객체 version, delete marker도 포함한다. 조회한 key는 삭제 전에 queue에 저장한다. 객체 삭제 실패는 완료로 표시하지 않고 다음 주기에 재시도한다. 한 주기에 pending 객체를 최대 500개 삭제하고 마지막 시도 시각이 오래된 객체부터 처리해 계속 실패하는 객체가 나머지를 막지 않게 한다. 객체 listing 실패 시에도 기존 queue 처리는 계속한다.

filesystem 저장소는 demonstrations/ 아래 일반 파일만 조회하며 symlink를 따라가지 않는다. QA 파일은 보존한다.

S3 권한에 `s3:ListBucketVersions`, `s3:DeleteObject`, `s3:DeleteObjectVersion`이 필요하다. Object Lock 또는 보관 정책 때문에 삭제가 거부되면 queue에 남는다. 서버 로그와 아래 조회로 완료 여부를 확인한다.

```sql
SELECT count(*) AS pending FROM retired_demonstration_object WHERE deleted_at IS NULL;
```

pending이 0인 것만으로 prefix의 orphan 조회가 완료됐다고 판단하지 않는다. 저장소 prefix listing이 정상 완료되고 객체가 없는지도 함께 확인한다. queue의 `deleted_at`은 삭제 완료 시각이며 재시작 후에도 유지한다.

이 데이터 삭제는 사용자 요청에 따른 영구 삭제다. 코드 revert로 삭제된 시연 기록이나 객체를 복구할 수 없다. 운영 반영은 서버를 먼저 배포한 뒤 home·agent·SDK 제거 변경을 반영한다.
