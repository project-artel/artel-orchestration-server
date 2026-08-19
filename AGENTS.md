# Project Agent Instructions

## Scope and Precedence

This file is the repository-level entrypoint for coding agents.

Read `.agents/docs/project.md` before non-trivial
work. Repository-specific commands, constraints, and narrower instructions take
precedence over these template defaults.

## Project Workflow

For non-trivial work, follow:

- `.agents/docs/workflow.md`
- `.agents/docs/testing.md`

로컬에서 직접 띄워 확인할 때 무엇을 켜야 하는지는:

- `.agents/docs/local-stack.md` — Postgres(pgvector)와 Redis가 필수다. 빠뜨리면 서버는 뜨고 특정 기능만 500이 난다.

Coding conventions:

- `.agents/docs/coding-style.md`
- `.agents/docs/error-handling.md` — 오류는 `common/error`의 타입 예외로 던진다(신규 `ResponseStatusException` 금지).
- `.agents/docs/configuration.md` — 설정값은 `@ConfigurationProperties`로 받는다(신규 `@Value` 금지).

For tracked Git work, follow:

- `.agents/docs/issue.md`
- `.agents/docs/commit.md`
- `.agents/docs/pull-request.md`

Use project-local skills when installed and applicable. Skill instructions
define their own triggers, formats, and output paths.
