---
name: insomnia-db
description: >-
  Read and modify the local Insomnia app's collections by editing its NeDB
  (`insomnia.*.db`) files directly — add/delete HTTP·WebSocket·SSE requests,
  fill environments, or inspect the workspace tree. Use when the user asks to
  "add these APIs to Insomnia", "put requests into the <X> workspace", "clean up
  Insomnia", "show teammate's Insomnia", or when Insomnia's own Import keeps
  landing requests in the wrong collection. Bypasses the flaky Import UI by
  writing straight to the store (which then cloud-syncs to the team).
---

# Insomnia local DB

Insomnia stores everything in per-model **NeDB** files (append-only, line-delimited
JSON). Editing them directly is more reliable than the desktop Import, which always
dumps into the *active* collection and cannot be undone or moved between collections.
Changes made here appear when Insomnia reopens and **sync to the team cloud** if the
project has a `remoteId`.

## ⚠️ Non-negotiable safety rules

1. **Insomnia MUST be fully quit before any write.** A running app holds the DB and
   will overwrite your appended lines on its next flush. Check first and refuse to
   write if it is running:
   ```bash
   pgrep -f "Insomnia" >/dev/null 2>&1 && echo "STILL_RUNNING — ask user to Cmd+Q" || echo "CLOSED"
   ```
   Reading is safe while it runs; **writing is not**.
2. **Back up every file you touch** before appending: `cp file file.bak.$(date +%s)`.
3. **Scope every change to a specific `parentId` (workspace) you intend**, and never
   delete or mutate resources you did not create. When cleaning up, select targets by
   an explicit filter (e.g. `parentId == <workspace>` AND `"/api/x" in url`) and
   **print the exact list first** so the user can confirm no one else's requests are
   in it. Other people's work syncs through the same store — treat it as read-only.
4. **Append only.** Never rewrite a `.db` file wholesale. Add lines; Insomnia compacts
   on load.
5. **Verify after writing** by reconstructing and listing the affected workspace, and
   confirm untouched workspaces are unchanged. Then tell the user to reopen Insomnia.

## Data location

- macOS: `~/Library/Application Support/Insomnia`
- Linux: `~/.config/Insomnia`
- Windows: `%APPDATA%/Insomnia`

Relevant files: `insomnia.Workspace.db`, `insomnia.RequestGroup.db` (folders),
`insomnia.Request.db` (HTTP), `insomnia.WebSocketRequest.db`, `insomnia.Environment.db`,
`insomnia.Project.db`. (SSE = a normal HTTP GET request with `Accept: text/event-stream`;
there is no separate SSE model.)

## NeDB format

Each line is one JSON doc. Updates append a new line with the same `_id` (last write
wins). Deletes append `{"$$deleted":true,"_id":"..."}`. Reconstruct current state by
replaying lines in order:

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

`list_insomnia.py` in this skill prints the full Project → Workspace → (folders) →
requests tree with `_id`s and Environment ids/keys — **always run it first** to find
the target workspace/environment `_id`s.

## Workflow

1. **Discover**: `python3 list_insomnia.py` → note the target workspace `_id`, its
   Base Environment `_id`, and existing contents.
2. **Plan & confirm scope**: decide exactly which `_id`s to add/delete/update. For
   deletions, print the matched list and confirm none belong to others.
3. **Guard**: confirm Insomnia is CLOSED (rule 1). If running, ask the user to quit.
4. **Backup** the `.db` files you will append to (rule 2).
5. **Write** (append) — see templates below.
6. **Verify**: reconstruct, list the target workspace + confirm others unchanged.
7. Tell the user to reopen Insomnia; note it cloud-syncs to the team.

## Doc schemas (mirror an existing doc — read one first)

**Request** (`insomnia.Request.db`):
```json
{"_id":"req_<hex>","type":"Request","parentId":"<workspace_id>","modified":<ms>,
 "created":<ms>,"url":"{{ _.base }}/path","name":"METHOD /path — 설명","description":"<Markdown — see 'Documenting requests'>",
 "method":"POST","body":{"mimeType":"application/json","text":"{...}"},
 "parameters":[],"headers":[{"disabled":false,"id":"pair_<x>","name":"Content-Type","value":"application/json"}],
 "authentication":{},"metaSortKey":<int, ascending = top→bottom>,"isPrivate":false,"pathParameters":[],
 "settingStoreCookies":true,"settingSendCookies":true,"settingDisableRenderRequestBody":false,
 "settingEncodeUrl":true,"settingRebuildPath":true,"settingFollowRedirects":"global"}
```
GET/DELETE: use `"body":{}`. SSE: GET + header `Accept: text/event-stream`. Cookie auth:
header `Cookie: artel_access_token={{ _.access_token }}` (Insomnia reads it server-side
regardless of httpOnly). **Env vars are per-workspace** and do not cross workspaces — if
requests reference `{{ _.x }}`, that var must exist in the target workspace's environment.

**WebSocketRequest** (`insomnia.WebSocketRequest.db`): `type":"WebSocketRequest"`, `url`
is `ws(s)://...`, no `method`/`body`.

**Environment** (`insomnia.Environment.db`): `{"_id":"env_<x>","type":"Environment",
"parentId":"<workspace_id>","name":"Base Environment","data":{...vars...},"color":null,
"isPrivate":false,"metaSortKey":<ms>,"environmentType":"kv"}`. To fill an existing empty
Base Environment, re-append its doc with the same `_id` and `data` filled.

## Documenting requests (the `description` field)

Insomnia renders a request's `description` as **Markdown** in the Docs/preview panel.
A single newline (`\n`) does **not** produce a line break — Markdown collapses adjacent
lines into one paragraph. Terse `\n`-joined text (e.g. `"IN: ...\nOUT: ..."`) shows up as
one run-on line. Write real Markdown instead:

- Separate blocks with a **blank line** (`\n\n`), never a single `\n`.
- Use `- ` bullet lists, `**bold**` labels, `## ` headings, and `` `code` `` spans.
- Do not rely on soft line breaks; use list items or separate paragraphs.

Make each description answer more than input/output — **who calls it, what it does, and
what the caller does with the result**. Recommended structure (fill every heading):

    **호출 주체**: FE 대시보드(React) → Orchestration   ← 이 API를 어느 서버가 호출하나

    **동작**: 이 엔드포인트가 무엇을 하는지 1–2문장 (서버/DB에서 일어나는 일).

    **Request**
    - `projectId` (number): 소속 프로젝트 id

    **Response**
    - `200`: `{ testScenarioId }`
    - `404`: 프로젝트 비참여자/미존재 (존재하지 않는 것처럼 감춤)

    **활용**: 반환된 `testScenarioId`로 SSE 세션을 시작하고, 이후 요청들의 경로 키로 쓴다.

Build the string in Python with real newlines — a triple-quoted string, or
`"\n".join([...])` where blank lines are empty `""` entries — so the `\n\n` between blocks
survives into the stored `description`. JSON-encoding preserves them; Insomnia renders them.

## Templates

**Delete by filter** (safe cleanup):
```python
AGENT="wrk_..."; REQDB=os.path.join(DIR,"insomnia.Request.db")
import shutil,time; shutil.copy2(REQDB,REQDB+f".bak.{int(time.time())}")
cur=rec("Request")
targets=[o for o in cur.values() if o.get("parentId")==AGENT and "/api/x" in (o.get("url") or "")]
for o in targets: print("DELETE", o["_id"], o.get("name"))        # confirm first
with open(REQDB,"a") as f:
    for o in targets: f.write(json.dumps({"$$deleted":True,"_id":o["_id"]})+"\n")
```

**Add requests / fill env**: build docs per the schemas above with `parentId` = target
workspace, unique `_id`s (`req_<name>_<ms>_<i>`), ascending `metaSortKey`, then append
one JSON line each. Use `int(time.time()*1000)` for `created`/`modified`.

## Notes

- Import UI routes to the *active* collection and ignores the file's workspace `_id`;
  that is why direct writes are used. If a user prefers Import, tell them to trigger it
  from **inside** the target collection (collection dropdown → Import), not the global button.
- The mcp-insomnia MCP server does the same NeDB sync as a tool, but is a community server
  with broad local-DB access — direct scripted edits here are narrower and auditable.
