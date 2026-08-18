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
