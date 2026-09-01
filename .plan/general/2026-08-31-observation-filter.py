"""관측 케이스 고르기 — 두 규칙을 견준다."""
import json, subprocess, collections
def q(sql):
    r=subprocess.run(["docker","exec","artel-pg","psql","-U","postgres","-tAc",sql],capture_output=True,text=True)
    return [l for l in r.stdout.split("\n") if l.strip()]
used=set()
def walk(n):
    if not isinstance(n,dict): return
    if n.get("kind")=="test": used.add(n["left"].split(".")[-1].lower())
    for p in n.get("parts",[]): walk(p)
for row in q("select condition_tree::text from capability_evidence e join capability c on c.id=e.capability_id join scene s on s.id=c.scene_id where s.content_map_id=27 and condition_tree is not null;"):
    try: walk(json.loads(row))
    except Exception: pass

rows=q("""select c.id||'|'||s.name||'|'||coalesce(e.target,'')||'|'||e.kind
from capability c join scene s on s.id=c.scene_id join capability_effect e on e.capability_id=c.id
where s.content_map_id=27 and c.merged_into is null
  and c.actionability='not-a-step' and c.observability='observable';""")
cap=collections.defaultdict(list)
for r in rows:
    cid,sc,t,k=r.split("|",3); cap[cid].append((t,k))

# 규칙 A: 게임 로직이 읽는 값만
A={c for c,es in cap.items() if any(t.split(".")[-1].lower() in used for t,_ in es)}
# 규칙 B: 사람이 보는 것 + 로직이 읽는 것. 소리·애니메이터만 버린다.
SEEN={"scene","instantiate","destroy","ui-value","active-state","transform"}
B={c for c,es in cap.items()
   if any(t.split(".")[-1].lower() in used or k in SEEN for t,k in es)}
only_sound={c for c,es in cap.items() if all(k in ("animation","audio") for _,k in es)}

print(f"볼 수 있는데 케이스 없는 기능   {len(cap)}건")
print(f"  규칙 A (로직이 읽는 값만)      담김 {len(A)} · 버림 {len(cap)-len(A)}")
print(f"  규칙 B (사람이 보는 것도)      담김 {len(B)} · 버림 {len(cap)-len(B)}")
print(f"  소리·애니메이터만 내는 기능    {len(only_sound)}건")
print()
lost = A - B
print("A 에는 있고 B 에 없는 것:", len(lost))
dropped_B = {c: cap[c] for c in cap if c not in B}
by=collections.Counter((k, t.split('.')[-1]) for es in dropped_B.values() for t,k in es)
print("\nB 가 버리는 것:")
for (k,t),n in by.most_common(20): print(f"  {k:12} {t[:40]:42} {n}")
print(f"\n케이스 수: 지금 42 → 규칙 A {42+len(A)} · 규칙 B {42+len(B)}")
