# 2026-07-19 — Add .env Support for Spring Boot

- Date: 2026-07-19
- GitHub Issue: None
- Status: Completed

## Goal
Enable automatic `.env` file loading in Spring Boot to cleanly manage environment variables (`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`) for local and AWS RDS database connections.

## Context / Constraints
- Dependency: `me.paulschwarz:spring-dotenv` (version 4.0.0).
- Git Security: `.env` is ignored by `.gitignore`. `.env.example` will be committed as a template.

## Approach (Checklist)

- [x] **Step 1: Add spring-dotenv dependency to pom.xml**
  - Add `me.paulschwarz:spring-dotenv:4.0.0` dependency.

- [x] **Step 2: Create .env.example Template File**
  - Create `.env.example` in the project root with placeholders for DB credentials.

- [x] **Step 3: Create .env File**
  - Create `.env` in the project root containing default/sample connection properties.

- [x] **Step 4: Verification**
  - Run `mvn clean test` to ensure compilation and property resolution.
