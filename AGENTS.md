# Project Agent Instructions

## Scope and Precedence

This file is the repository-level entrypoint for coding agents.

Read `.agents/docs/project.md` before non-trivial
work. Repository-specific commands, constraints, and narrower instructions take
precedence over these template defaults.

## Terminology in comments, documents, and pull requests

Keep a technical term in English, in backticks, even in the middle of a Korean
sentence: `pulse`, `screen`, `capability`, `anchor`, `branch`, `fold`,
`discriminator`, `evidence`, `wiring`.

Do not invent a Korean substitute for something the code already names. `판독`
for `pulse`, `갈래` for `branch`, `배선` for `wiring`, `판별자` for
`discriminator`, `근거 문서` for an `evidence` document — none of these.

**How common a coinage is in this repository is not an argument for writing
another one.** Several of them are already widespread here. That is history, not
a standard: it means the habit spread before anyone stopped it, and matching it
spreads it further. When you write a new comment, choose the English word even
when the file beside it does not.

The one exception is a sentence you are editing that already uses the old word,
where changing it would leave a single paragraph speaking two ways. Match the
line you are touching; do not convert the file around it as a side errand.

This is not a push toward more English or more Korean. Prose stays whatever
reads naturally. The rule is narrower than that: a thing the code names keeps
the name the code gave it.

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
