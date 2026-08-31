"""볼 수 있는데 케이스가 없는 137건 — 그중 '쓰이는 값'은 몇인가."""
import json, subprocess, re, collections
def q(sql):
    r=subprocess.run(["docker","exec","artel-pg","psql","-U","postgres","-tAc",sql],capture_output=True,text=True)
    return [l for l in r.stdout.split("\n") if l.strip()]

# 조건에 쓰이는 이름 전부(지도의 근거 조건 + 케이스 전제)
used=set()
def walk(n):
    if not isinstance(n,dict): return
    if n.get("kind")=="test":
        used.add(n["left"].split(".")[-1].lower())
    for p in n.get("parts",[]): walk(p)
for row in q("select condition_tree::text from capability_evidence e join capability c on c.id=e.capability_id join scene s on s.id=c.scene_id where s.content_map_id=27 and condition_tree is not null;"):
    try: walk(json.loads(row))
    except Exception: pass
print("조건에 쓰이는 이름", len(used))

rows=q("""select c.id||'|'||coalesce(regexp_replace(e.target,'^.*\\.',''),'')||'|'||e.kind||'|'||coalesce(e.detail,'')
from capability c join scene s on s.id=c.scene_id join capability_effect e on e.capability_id=c.id
where s.content_map_id=27 and c.merged_into is null
  and c.actionability='not-a-step' and c.observability='observable';""")
cap=collections.defaultdict(list)
for r in rows:
    cid,t,k,d=r.split("|",3); cap[cid].append((t,k,d))
hit=[c for c,es in cap.items() if any(t.lower() in used for t,_,_ in es)]
print(f"볼 수 있는데 케이스 없는 기능 {len(cap)}건 · 그중 조건에 쓰이는 값을 건드리는 것 {len(hit)}건")
kinds=collections.Counter(k for es in cap.values() for _,k,_ in es)
print("효과 종류:", dict(kinds.most_common(8)))
kinds2=collections.Counter(k for c in hit for _,k,_ in cap[c])
print("쓰이는 것들의 종류:", dict(kinds2.most_common(8)))
