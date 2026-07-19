# 2026-07-19 — Connect AWS RDS PostgreSQL DB & Add Flyway Migration

- Date: 2026-07-19
- GitHub Issue: None
- Status: Completed

## Goal
Integrate AWS RDS PostgreSQL Database connection and Flyway migration support into the Orchestrator server for versioned DB schema management.

## Context / Constraints
- Framework: Spring Boot 3.3.1 (WebFlux / Data JPA / JDBC).
- Database Engine: PostgreSQL.
- Migration Tool: Flyway (`flyway-core`, `flyway-database-postgresql`).
- Configuration: Managed in `application.yml` via environment variables with sensible defaults.

## Approach (Checklist)

- [x] **Step 1: Add Dependencies to pom.xml**
  - Add `spring-boot-starter-data-jpa` for ORM/Database access.
  - Add PostgreSQL Driver (`org.postgresql:postgresql`).
  - Add Flyway dependencies (`org.flywaydb:flyway-core`, `org.flywaydb:flyway-database-postgresql`).

- [x] **Step 2: Update application.yml Configuration**
  - Add DataSource properties (`url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:artel}`, `username`, `password`, `driver-class-name: org.postgresql.Driver`).
  - Add JPA properties (`hibernate.ddl-auto: validate`, `show-sql: true`).
  - Add Flyway configuration (`spring.flyway.enabled=true`, `spring.flyway.baseline-on-migrate=true`, `spring.flyway.locations=classpath:db/migration`).

- [x] **Step 3: Create Initial Flyway Migration Script**
  - Create directory `src/main/resources/db/migration/`.
  - Create initial migration script `V1__init_schema.sql` (e.g., table for tracking SDK sessions or execution logs).

- [x] **Step 4: Verification**
  - Run `mvn clean test` to ensure compilation and Flyway migration setup validation.
