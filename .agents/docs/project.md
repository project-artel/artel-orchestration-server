# Project Context

Fill this document during project initialization. Agents must verify commands against repository configuration before running them.

## Overview

- Product: artel-orchestration-server
- Primary users: TODO
- Core domain: TODO
- Runtime environment: TODO

## Architecture

- Entry points: TODO
- Main modules: TODO
- Dependency direction: TODO
- External systems: GitHub repository `project-artel/artel-orchestration-server`; Jira project `ARTEL` via the `mcp-atlassian` MCP server; Insomnia collection repository `project-artel/insomnia-api`
- Persistent data: TODO

## Commands

| Purpose | Command |
|---|---|
| Install dependencies | TODO |
| Run locally | TODO |
| Format | TODO |
| Lint | TODO |
| Type-check | TODO |
| Unit tests | TODO |
| Integration tests | TODO |
| Build | TODO |
| Set up Jira credentials | `cp .jira.env.example .jira.env` |

Jira access goes through the `mcp-atlassian` MCP server, declared in `.mcp.json`
at the repository root. Claude Code starts it on demand and asks for approval
the first time it connects.

Credentials live in `.jira.env`, which the server reads through `--env-file`.
Copy `.jira.env.example` and fill in `JIRA_URL`, `JIRA_USERNAME`, and
`JIRA_API_TOKEN`, issuing the token at
`https://id.atlassian.com/manage-profile/security/api-tokens`. `.gitignore`
excludes `.jira.env`; never commit it.

The server reads that file itself, so the setup does not depend on how Claude
Code was launched or on which shell exports the variables. Do not register a
`jira` server in user scope as well, or two copies start.

### Insomnia collections

API collections live in `project-artel/insomnia-api`, one YAML file per
repository (`orchestration-server.yaml` for this one), and reach people through
Insomnia's git sync. Publish changes with the `insomnia-sync` skill: it derives
the API surface from the springdoc contract at `/v3/api-docs`, writes the
collection file, and opens a PR.

Do not publish by writing into a local Insomnia app — neither through the
`insomnia` MCP server's write tools nor by editing the `insomnia.*.db` NeDB
store. Either way only one machine changes and no reviewable diff exists.
Reading local state is fine.

Environment variables are committed alongside the requests, so every consumer
gets working URLs on pull. Credentials are excluded: `access_token` for the
authenticated `/api/test-scenario/**` paths stays in an Insomnia private
environment. The collection repository is currently public.

## Constraints

- Supported platforms:
- Compatibility requirements:
- Performance constraints:
- Security or privacy requirements:

## Ownership

- Maintainers:
- Sensitive modules:
- Changes requiring explicit review:
