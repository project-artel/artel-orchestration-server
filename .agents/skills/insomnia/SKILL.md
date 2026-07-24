---
name: insomnia
description: >-
  Manage the team's Insomnia collections two ways: (A) edit the git-synced
  Insomnia v5 YAML files in the `insomnia-api` repo — the team-shared, versioned
  way to publish API updates; and (B) directly edit the local Insomnia NeDB
  (`insomnia.*.db`) for quick personal or partial changes. Use when asked to
  "update Insomnia via the yaml", "sync the API spec to Insomnia", "add these
  APIs to the Insomnia collection", "put requests into the <X> workspace", "clean
  up Insomnia", or when Insomnia's Import lands requests in the wrong collection.
---

# Insomnia collections

Two ways to change what teammates see in Insomnia — pick by intent:

| Method | Use when | How it propagates |
|---|---|---|
| **A. Git YAML (v5)** | Publishing an API update the **whole team** relies on; versioned | edit `*.yaml` → `git push` → teammates `git pull` → Insomnia **Git Sync** |
| **B. Local NeDB** | Quick **personal/partial** edit on your own machine | edit local `.db` → reopen Insomnia → cloud sync |

**Prefer A** for API changes that should be shared and version-controlled. Use **B** for a
fast local fix, or when there is no yaml repo. The **Documenting requests** convention at
the bottom applies to both.

---

# Method A — git-synced Insomnia v5 YAML (preferred for team updates)

The team keeps **one YAML file per collection** in a git repo. Insomnia's **Git Sync**
reads/writes those files, so *committing an updated yaml and pushing is how an API update
reaches everyone* — teammates pull and Insomnia syncs it in. This does **not** touch the
local NeDB and does **not** require Insomnia to be closed.

## Locate the repo (relative — never hardcode an absolute path)

This skill is team-shared, so discover the collection repo relative to the project tree
rather than assuming a user-specific path. It is a sibling of the code repos (e.g.
`../insomnia-api`), a git repo whose `*.yaml` files start with
`type: collection.insomnia.rest/5.0`:

```bash
root=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
parent=$(cd "$root/.." && pwd)
grep -rl "collection.insomnia.rest" "$parent" --include=*.yaml 2>/dev/null
```
If nothing is found, ask the user for the path. Confirm the target `<server>.yaml`
(one file per collection, e.g. `agent-server.yaml`, `orchestration-server.yaml`).

## v5 collection YAML schema (mirror an existing file first)

```yaml
type: collection.insomnia.rest/5.0
schema_version: "5.1"
name: <collection name>
meta:
  id: wrk_<hex>
  created: <ms>
  modified: <ms>
  description: <text>
collection:                       # ordered list of requests
  - url: "{{ _.stage_orch_base_url }}/api/test-scenario/{{ _.test_scenario_id }}"
    name: "PUT /api/test-scenario/{id} — 자동저장"
    meta:
      id: req_<hex>               # WebSocket requests use ws-req_<hex>
      created: <ms>
      modified: <ms>
      isPrivate: false
      description: |-             # block scalar; rendered as Markdown (see below)
        **호출 주체**: ...

        **동작**: ...
      sortKey: <negative number; ascending = top→bottom>
    method: PUT                    # omit for WebSocket
    body:                          # optional
      mimeType: application/json
      text: |-
        { "draft": { ... } }
    headers:
      - name: Content-Type
        value: application/json
        disabled: false
    settings:
      renderRequestBody: true
      encodeUrl: true
      followRedirects: global
      cookies: { send: true, store: true }
      rebuildPath: true
cookieJar:
  name: Default Jar
  meta: { id: jar_<hex>, created: <ms>, modified: <ms> }
environments:
  name: Base Environment
  meta: { id: env_<hex>, created: <ms>, modified: <ms>, isPrivate: false }
  data:                            # environment variables (per collection)
    stage_orch_base_url: https://stage-orch.artel.kr
    access_token: ""
```

Notes:
- **Preserve `meta.id` when updating** a request; generate a fresh `req_<hex>` (or
  `ws-req_<hex>`) when adding one. Ids are how Git Sync matches rows.
- Order by `meta.sortKey` (negative float; smaller value sorts higher). Copy the existing
  file's spacing between keys when inserting.
- **Response chaining** (pull a value from a prior response) uses Insomnia's tag:
  `{% response 'body', '<req_id>', 'b64::<base64 of a JSONPath like $.session_id>::<nonce>', 'never', 60 %}`.
  Copy the exact tag from an existing request rather than hand-building the b64.
- Cookie-JWT auth: add a header `Cookie: artel_access_token={{ _.access_token }}` (Orchestration
  `/api/test-scenario/**` is authenticated; Agent/SDK paths are permitAll — no auth).

## Workflow (A)

1. **Locate** the repo + the target `<server>.yaml` (discover relatively).
2. **Read** it and mirror its exact shape (a real existing request is the best template).
3. **Add/update** requests under `collection:` — preserve ids, set `sortKey` for order,
   write `description` as Markdown in a `|-` block scalar.
4. **Write** the file. Prefer editing in place / mirroring formatting; if generating with a
   YAML emitter, keep block scalars for multi-line descriptions.
5. **Commit** in that repo (`git -C <repo> add/commit`). **Ask before pushing.**
6. Tell the user: teammates `git pull` + Insomnia **Git Sync** to receive it.

---

# Method B — local NeDB direct edit (quick/personal, or no yaml repo)

Insomnia stores everything in per-model **NeDB** files (append-only, line-delimited JSON).
Editing them directly is reliable when the Import UI misbehaves (it always dumps into the
*active* collection and cannot be undone or moved). Changes appear on reopen and sync to
the team cloud if the project has a `remoteId`.

## ⚠️ Non-negotiable safety rules

1. **Insomnia MUST be fully quit before any write.** A running app holds the DB and
   overwrites appended lines on its next flush. Check and refuse to write if running:
   ```bash
   pgrep -f "Insomnia" >/dev/null 2>&1 && echo "STILL_RUNNING — ask user to Cmd+Q" || echo "CLOSED"
   ```
   Reading is safe while it runs; **writing is not**.
2. **Back up every file you touch** first: `cp file file.bak.$(date +%s)`.
3. **Scope every change to a specific `parentId` (workspace) you intend**, and never delete
   or mutate resources you did not create. Select targets by an explicit filter (e.g.
   `parentId == <workspace>` AND `"/api/x" in url`) and **print the exact list first** so the
   user can confirm no one else's requests are in it. Other people's work syncs through the
   same store — treat it as read-only.
4. **Append only.** Never rewrite a `.db` file wholesale; add lines (Insomnia compacts on load).
5. **Verify after writing** by reconstructing and listing the affected workspace, and confirm
   untouched workspaces are unchanged. Then tell the user to reopen Insomnia.

## Data location

- macOS: `~/Library/Application Support/Insomnia`
- Linux: `~/.config/Insomnia`
- Windows: `%APPDATA%/Insomnia`

Files: `insomnia.Workspace.db`, `insomnia.RequestGroup.db` (folders), `insomnia.Request.db`
(HTTP), `insomnia.WebSocketRequest.db`, `insomnia.Environment.db`, `insomnia.Project.db`.
(SSE = a normal HTTP GET with `Accept: text/event-stream`; no separate model.)

## NeDB format

Each line is one JSON doc. Updates append a new line with the same `_id` (last write wins).
Deletes append `{"$$deleted":true,"_id":"..."}`. Reconstruct by replaying lines:

```python
import json, os
DIR=os.path.expanduser("~/Library/Application Support/Insomnia")
def rec(model):
    d={}
    p=os.path.join(DIR,f"insomnia.{model}.db")
    if not os.path.exists(p): return d
    for line in open(p,encoding="utf-8",errors="replace"):
        line=line.strip()
        if not line: continue
        try: o=json.loads(line)
        except: continue
        i=o.get("_id")
        if o.get("$$deleted"): d.pop(i,None); continue
        if i: d[i]=o
    return d
```

`list_insomnia.py` prints the full Project → Workspace → (folders) → requests tree with
`_id`s and Environment ids/keys — **run it first** to find target workspace/environment ids.

## Workflow (B)

1. **Discover**: `python3 list_insomnia.py` → note target workspace `_id` + Base Environment `_id`.
2. **Plan & confirm scope**: which `_id`s to add/delete/update; for deletions print the matched list.
3. **Guard**: confirm Insomnia CLOSED (rule 1).
4. **Backup** the `.db` files (rule 2).
5. **Write** (append) — templates below.
6. **Verify**: reconstruct + list; confirm others unchanged. Tell user to reopen (cloud-syncs).

## NeDB doc schemas (mirror an existing doc — read one first)

**Request** (`insomnia.Request.db`):
```json
{"_id":"req_<hex>","type":"Request","parentId":"<workspace_id>","modified":<ms>,
 "created":<ms>,"url":"{{ _.base }}/path","name":"METHOD /path — 설명","description":"<Markdown>",
 "method":"POST","body":{"mimeType":"application/json","text":"{...}"},
 "parameters":[],"headers":[{"disabled":false,"id":"pair_<x>","name":"Content-Type","value":"application/json"}],
 "authentication":{},"metaSortKey":<int, ascending = top→bottom>,"isPrivate":false,"pathParameters":[],
 "settingStoreCookies":true,"settingSendCookies":true,"settingDisableRenderRequestBody":false,
 "settingEncodeUrl":true,"settingRebuildPath":true,"settingFollowRedirects":"global"}
```
GET/DELETE: `"body":{}`. SSE: GET + header `Accept: text/event-stream`. Cookie auth: header
`Cookie: artel_access_token={{ _.access_token }}`. **Env vars are per-workspace** — a
`{{ _.x }}` reference resolves only if that var exists in the target workspace's environment.

**WebSocketRequest** (`insomnia.WebSocketRequest.db`): `"type":"WebSocketRequest"`, `url` is
`ws(s)://...`, no `method`/`body`.

**Environment** (`insomnia.Environment.db`): `{"_id":"env_<x>","type":"Environment",
"parentId":"<workspace_id>","name":"Base Environment","data":{...vars...},"color":null,
"isPrivate":false,"metaSortKey":<ms>,"environmentType":"kv"}`. To fill an existing empty Base
Environment, re-append its doc with the same `_id` and `data` filled.

## Templates (B)

**Delete by filter** (safe cleanup):
```python
WS="wrk_..."; REQDB=os.path.join(DIR,"insomnia.Request.db")
import shutil,time; shutil.copy2(REQDB,REQDB+f".bak.{int(time.time())}")
cur=rec("Request")
targets=[o for o in cur.values() if o.get("parentId")==WS and "/api/x" in (o.get("url") or "")]
for o in targets: print("DELETE", o["_id"], o.get("name"))        # confirm first
with open(REQDB,"a") as f:
    for o in targets: f.write(json.dumps({"$$deleted":True,"_id":o["_id"]})+"\n")
```

**Update a description** (preserve all other fields):
```python
o=dict(cur[<id>]); o["description"]=MARKDOWN; o["modified"]=int(time.time()*1000)
open(REQDB,"a").write(json.dumps(o,ensure_ascii=False)+"\n")
```

**Add requests / fill env**: build docs per the schemas with `parentId` = target workspace,
unique `_id`s (`req_<name>_<ms>_<i>`), ascending `metaSortKey`; append one JSON line each.

---

# Documenting requests (applies to both A and B)

Insomnia renders a request's `description` as **Markdown** in the Docs/preview panel. A single
newline (`\n`) does **not** break a line — Markdown collapses adjacent lines into one paragraph.
Write real Markdown:

- Separate blocks with a **blank line** (`\n\n`), never a single `\n`.
- Use `- ` bullet lists, `**bold**` labels, `## ` headings, `` `code` `` spans.

Answer more than input/output — **who calls it, what it does, and what the caller does with the
result**. Recommended structure (fill every heading):

    **호출 주체**: FE 대시보드(React) → Orchestration   ← 이 API를 어느 서버가 호출하나

    **동작**: 이 엔드포인트가 무엇을 하는지 1–2문장 (서버/DB에서 일어나는 일).

    **Request**
    - `projectId` (number): 소속 프로젝트 id

    **Response**
    - `200`: `{ testScenarioId }`
    - `404`: 프로젝트 비참여자/미존재 (존재하지 않는 것처럼 감춤)

    **활용**: 반환된 `testScenarioId`로 SSE 세션을 시작하고, 이후 요청들의 경로 키로 쓴다.

In YAML use a `|-` block scalar (keeps the blank lines). In JSON (NeDB) build the string with
real newlines (triple-quote or `"\n".join`) so the `\n\n` survives into the stored `description`.

# Notes

- **Method A vs B**: A is the source of truth the team versions in git; B is a local shortcut.
  If both a yaml repo and a live local collection exist, prefer updating the yaml (A) and let
  Git Sync reconcile.
- Import UI routes to the *active* collection and ignores a file's workspace id; that is why B
  writes the store directly. If a user insists on Import, tell them to trigger it from **inside**
  the target collection (collection dropdown → Import), not the global button.
- The community `mcp-insomnia` MCP server does NeDB sync as a tool, but has broad local-DB
  access — the scripted edits here are narrower and auditable.
