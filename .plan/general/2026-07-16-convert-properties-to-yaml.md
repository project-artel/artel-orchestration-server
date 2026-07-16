# 2026-07-16 — Convert Properties to YAML

- Date: 2026-07-16
- GitHub Issue: None
- Status: Draft

## Goal
Convert the Spring Boot configuration from `application.properties` to `application.yml` for improved readability and nested structure.

## Context / Constraints
- File format: YAML.
- Porting all current configurations (`logging.level` and `artel.agent.base-url`).
- Clean up the old `.properties` file.

## Approach (Checklist)

- [ ] **Step 1: Create YAML file**
  - Create `src/main/resources/application.yml` with the equivalent YAML configuration.
  
- [ ] **Step 2: Delete Properties file**
  - Delete `src/main/resources/application.properties`.

- [ ] **Step 3: Verification**
  - Run `mvn clean test` to verify that Spring Boot picks up the new YAML configuration successfully and all tests pass.
