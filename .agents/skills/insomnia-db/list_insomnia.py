#!/usr/bin/env python3
"""Print the local Insomnia tree (Project → Workspace → folders → requests) with
_id's, plus environments and their keys. Read-only; safe while Insomnia runs.

Usage: python3 list_insomnia.py [DATA_DIR]
Default DATA_DIR: platform Insomnia app-support path.
"""
import json, os, sys

def data_dir():
    if len(sys.argv) > 1:
        return os.path.expanduser(sys.argv[1])
    if sys.platform == "darwin":
        return os.path.expanduser("~/Library/Application Support/Insomnia")
    if sys.platform.startswith("win"):
        return os.path.join(os.environ.get("APPDATA", ""), "Insomnia")
    return os.path.expanduser("~/.config/Insomnia")

DIR = data_dir()

def rec(model):
    d = {}
    p = os.path.join(DIR, f"insomnia.{model}.db")
    if not os.path.exists(p):
        return d
    for line in open(p, encoding="utf-8", errors="replace"):
        line = line.strip()
        if not line:
            continue
        try:
            o = json.loads(line)
        except Exception:
            continue
        i = o.get("_id")
        if o.get("$$deleted"):
            d.pop(i, None); continue
        if i:
            d[i] = o
    return d

def main():
    if not os.path.isdir(DIR):
        print(f"Insomnia data dir not found: {DIR}"); return
    proj = rec("Project"); wrk = rec("Workspace"); grp = rec("RequestGroup")
    req = rec("Request"); ws = rec("WebSocketRequest"); sio = rec("SocketIORequest")
    env = rec("Environment")
    items = list(req.values()) + list(ws.values()) + list(sio.values())

    def kind(o):
        t = o.get("type", "")
        return {"WebSocketRequest": "WS", "SocketIORequest": "SIO"}.get(t, o.get("method", "?"))
    def nm(o):
        return o.get("name", "(no name)")

    print(f"# dir: {DIR}")
    print(f"# projects={len(proj)} workspaces={len(wrk)} folders={len(grp)} "
          f"http={len(req)} ws={len(ws)}\n")
    for p in proj.values():
        print(f"PROJECT {nm(p)}  _id={p['_id']} remoteId={p.get('remoteId')}")
        for w in [w for w in wrk.values() if w.get("parentId") == p["_id"]]:
            print(f"  WORKSPACE {nm(w)}  _id={w['_id']} scope={w.get('scope')}")
            for e in [e for e in env.values() if e.get("parentId") == w["_id"]]:
                print(f"    ENV {nm(e)}  _id={e['_id']} keys={list((e.get('data') or {}).keys())}")
            def walk(parent, depth):
                for g in [g for g in grp.values() if g.get("parentId") == parent]:
                    print("    " + "  " * depth + f"FOLDER {nm(g)}  _id={g['_id']}")
                    walk(g["_id"], depth + 1)
                for it in [i for i in items if i.get("parentId") == parent]:
                    print("    " + "  " * depth + f"[{kind(it)}] {nm(it)}  _id={it['_id']}  {(it.get('url') or '')[:55]}")
            walk(w["_id"], 1)
        print()

if __name__ == "__main__":
    main()
