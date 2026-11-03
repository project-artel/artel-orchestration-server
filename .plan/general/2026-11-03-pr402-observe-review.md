# MAP TC observe 보존 수정

- 문제: MAP TC의 버전 metadata를 동기화하거나 읽으면 TC 공통 observe 설정이 사라졌다.
- 수정: syncIdentities와 TestCaseReader는 버전의 observe를 제거하고 현재 TC의 observe만 적용한다. 빈 목록으로 지운 설정도 과거 버전에서 복원하지 않는다.
- 충돌: GameFunctionsIntegrationTest의 observe 검증과 develop의 ACTION rollback·relay 검증을 함께 유지했다.
- 검증: Java 21 및 별도 Testcontainers에서 TestCaseObserveTest 7개, TestCaseVersionLibraryIntegrationTest 8개, ScenarioExecutionModeTest 10개, GameFunctionsIntegrationTest 26개 총 51개 통과. 실패·오류·skip 0개.
- 기존 로컬 서버와 DB는 변경하지 않았다. DB migration은 없다.
