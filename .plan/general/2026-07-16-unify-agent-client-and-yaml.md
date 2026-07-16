# 2026-07-16 — Unify AgentClient and Convert to YAML

- Date: 2026-07-16
- GitHub Issue: None
- Status: Completed

## Goal
- Remove the separate `AgentClient` interface and unify it back into a single `AgentClient` class as requested.
- Fix the Java 26 Mockito compatibility issue by enabling the `-Dnet.bytebuddy.experimental=true` VM option in `pom.xml` for the Surefire test runner.
- Delete `application.properties` and replace it with `application.yml`.

## Context / Constraints
- Unify the interface and implementation to keep the code simple.
- JVM Version: Java 26 (requires `net.bytebuddy.experimental=true` to allow inline mocking).

## Approach (Checklist)

- [x] **Step 1: Configuration (YAML)**
  - Create `src/main/resources/application.yml` containing the YAML equivalent of the properties.
  - Delete `src/main/resources/application.properties`.

- [x] **Step 2: Update pom.xml**
  - Add `maven-surefire-plugin` configuration in `pom.xml` with `<argLine>-Dnet.bytebuddy.experimental=true</argLine>`.

- [x] **Step 3: Unify AgentClient**
  - Delete `src/main/kotlin/kr/artel/orchestration/sdk/service/AgentClientImpl.kt`.
  - Rewrite `src/main/kotlin/kr/artel/orchestration/sdk/service/AgentClient.kt` as a single `@Service` class (no interface).

- [x] **Step 4: Verification**
  - Run `mvn clean test` to ensure all tests pass successfully with the unified class and the new YAML configuration.
