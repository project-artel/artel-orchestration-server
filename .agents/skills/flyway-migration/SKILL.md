---
name: flyway-migration
description: Create and manage Flyway DB migration SQL scripts under src/main/resources/db/migration/ with automatic versioning and validation. Use when asked to "add a migration", "create flyway script", "DB 스키마 추가", "Flyway 마이그레이션 생성", or when altering database schemas.
---

# flyway-migration Skill

## Trigger conditions
Use this skill when:
- Asked to "add a migration", "create flyway script", "DB 스키마 추가", "Flyway 마이그레이션 생성".
- Adding a new database table, modifying columns, adding indexes, or updating PostgreSQL schema.
- Preparing Flyway versioned migration scripts for database schema evolution.

## Inputs (ask if missing)
- **description** (required): Short summary of the migration (e.g., "add_user_table", "create_agent_log").
- **sql_content** (required or derived): The PostgreSQL DDL statements to be executed.

## Rules & Constraints (MUST)
1. **Directory Location:** `src/main/resources/db/migration/`
2. **Filename Naming Convention:**
   - Prefix: `V` followed by sequential version number (e.g., `V1`, `V2`, `V3`).
   - Separator: **Double Underscore `__`** (CRITICAL: Single underscore will fail Flyway parsing!).
   - Description: Snake_case description (e.g., `V2__add_agent_execution_log.sql`).
3. **Dialect:** Standard PostgreSQL DDL syntax.
4. **Idempotency & Safety:** Use `IF NOT EXISTS` for table/index creation where appropriate.

## Workflow

### Step 1: Scan Existing Migrations
List files in `src/main/resources/db/migration/` to identify the highest existing version number `N`.
- Example: If `V1__init_schema.sql` exists, the next version is `V2`.

The working tree is not the whole picture. Another unmerged branch may already
have claimed `N+1`, and that collision is invisible here — it surfaces after the
merge, as a server that will not start. Step 4 is what catches it, so do not
treat a free-looking number as settled until the check has run.

### Step 2: Generate Migration File
Create `src/main/resources/db/migration/V<N+1>__<snake_case_description>.sql`.

### Step 3: Write SQL Statements
Write clean, well-commented PostgreSQL DDL statements.

### Step 4: Validate Migration

First check the version number against every other branch:

```bash
./scripts/check-flyway-migrations.sh
```

This is the step that catches a number another branch already took, a number
below what `develop` has already applied, and an edit to an already-merged
migration. None of those are visible from the working tree alone, and none are
caught by the test suite — it migrates an empty database, where every ordering
and every checksum succeeds. Exit code `1` must be fixed; `2` means another
branch claims the same number, so coordinate before merging. See
`docs/flyway-migrations.md`.

Then run the suite for SQL correctness:

```bash
./mvnw clean test
```

Ensure that Flyway executes and validates the new migration script against the test database without syntax or versioning errors.
