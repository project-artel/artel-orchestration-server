#!/usr/bin/env python3
"""Register code-only (not-yet-in-Notion) API endpoints to the Notion API spec.

Run in CI on develop push. Extracts routes from this repo (orchestration-server)
and the sibling artel-agent-server, compares against Notion `API 명세서`, and
creates entries for missing endpoints.

Env required:
  NOTION_API_KEY  Notion integration token
  GITHUB_WORKSPACE  (set automatically by GH Actions)

Design: idempotent — regenerating never duplicates. Only adds missing rows.
"""

import json
import os
import re
import sys
from pathlib import Path

import requests

NOTION_DS = "c080bce5-474c-8264-b176-0706704849ee"
NOTION_BASE = "https://api.notion.com/v1"
NOTION_VER = "2022-06-28"


def headers():
    return {
        "Authorization": f"Bearer {os.environ['NOTION_API_KEY']}",
        "Notion-Version": NOTION_VER,
        "Content-Type": "application/json",
    }


def extract_orchestrator(path: Path):
    """Extract {method, url} from Spring @*Mapping annotations."""
    routes = {}
    ctrl_files = list(path.glob("src/main/kotlin/**/*Controller.kt"))
    for f in ctrl_files:
        text = f.read_text()
        class_match = re.search(r"@RequestMapping\(\"([^\"]*)\"\)", text)
        base = class_match.group(1) if class_match else ""
        for m in re.finditer(
            r"@(Get|Post|Put|Patch|Delete)Mapping\(\"([^\"]*)\"\)", text
        ):
            method = m.group(1).upper()
            path_part = m.group(2)
            routes.setdefault((method, base + path_part))
    return routes


def extract_agent(path: Path):
    """Extract {method, url} from FastAPI @router.* decorators."""
    routes = set()
    for f in path.glob("app/**/*.py"):
        text = f.read_text()
        for m in re.finditer(
            r"@router\.(get|post|put|patch|delete|websocket)\(\"([^\"]*)\"",
            text,
        ):
            method_map = {"websocket": "WSS"}
            method = method_map.get(m.group(1), m.group(1).upper())
            routes.add((method, m.group(2)))
    return routes


def notion_existing():
    """Return set of (Server, method, url) already registered."""
    out = set()
    cursor = None
    while True:
        body = {
            "filter": {"property": "명세 구분", "select": {"equals": "API 명세"}},
            "page_size": 100,
        }
        if cursor:
            body["start_cursor"] = cursor
        r = requests.post(
            f"{NOTION_BASE}/data_sources/{NOTION_DS}/query",
            headers=headers(),
            json=body,
            timeout=30,
        )
        r.raise_for_status()
        d = r.json()
        for p in d["results"]:
            props = p["properties"]
            server = ((props.get("Server") or {}).get("select") or {}).get("name", "")
            method = ((props.get("Request Method") or {}).get("select") or {}).get(
                "name", ""
            )
            url = "".join(x.get("plain_text", "") for x in props.get("Url", {}).get("rich_text", []))
            out.add((server, method, url))
        if not d.get("has_more"):
            break
        cursor = d.get("next_cursor")
    return out


def create_entry(name, server, method, url, version, evidence):
    payload = {
        "parent": {"type": "data_source_id", "data_source_id": NOTION_DS},
        "properties": {
            "Name": {"title": [{"text": {"content": name}}]},
            "Server": {"select": {"name": server}},
            "Type": {"select": {"name": "WSS" if method in ("WSS", "해당 없음") else "HTTP"}},
            "Request Method": {"select": {"name": method}},
            "Url": {"rich_text": [{"text": {"content": url}}]},
            "Category": {"select": {"name": "Agent" if "Server" in server else "팀"}},
            "Input type": {"select": {"name": "없음"}},
            "Input": {"rich_text": [{"text": {"content": ""}}]},
            "응답": {"rich_text": [{"text": {"content": ""}}]},
            "오류 응답": {"rich_text": [{"text": {"content": ""}}]},
            "인증": {"select": {"name": "미정"}},
            "구현 현황": {"select": {"name": "구현 완료"}},
            "명세 구분": {"select": {"name": "API 명세"}},
            "명세 변경": {"select": {"name": "Hermes 추가"}},
            "명세 버전": {"rich_text": [{"text": {"content": version}}]},
            "근거": {"rich_text": [{"text": {"content": evidence}}]},
        },
    }
    r = requests.post(f"{NOTION_BASE}/pages", headers=headers(), json=payload, timeout=30)
    return r


def main():
    if "NOTION_API_KEY" not in os.environ:
        print("skip: NOTION_API_KEY not set")
        return 0
    ws = Path(os.environ["GITHUB_WORKSPACE"])
    agent_path = Path("/tmp/artel-agent-server")

    existing = notion_existing()
    print(f"Notion 기존: {len(existing)}건")

    created = 0
    orch_routes = extract_orchestrator(ws)
    for method, url in orch_routes:
        if ("Orchestrator", method, url) not in existing:
            r = create_entry(url, "Orchestrator", method, url,
                             "orchestration-server develop", f"CI auto: {method} {url}")
            if r.status_code in (200, 201):
                created += 1
                print(f"+ [{method}] {url}")
    if agent_path.exists():
        for method, url in extract_agent(agent_path):
            if ("Agent Server", method, url) not in existing:
                r = create_entry(url, "Agent Server", method, url,
                                 "agent-server develop", f"CI auto: {method} {url}")
                if r.status_code in (200, 201):
                    created += 1
                    print(f"+ [{method}] {url}")

    print(f"신규 등록: {created}건")
    return 0


if __name__ == "__main__":
    sys.exit(main())
