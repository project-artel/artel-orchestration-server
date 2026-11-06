"""골든 content map 을 로컬 DB 에 넣는다. **로컬 실측 전용, 임시.**

ARTEL-441(적재 경로)이 아직 머지 전이라 저작 쪽을 로컬에서 돌려 보려면 지도를 손으로
넣어야 한다. 적재기가 들어오면 이 파일은 지운다 — 적재 규칙의 두 번째 판이 되면 안 된다.

    python3 scripts/load-golden-content-map.py

`SRC` 와 `GAME_BUILD_ID` 를 자기 환경에 맞게 고쳐서 쓴다. 같은 빌드의 기존 지도는 지우고
다시 넣는다(content_map 을 지우면 씬·기능·효과·간선이 CASCADE 로 함께 간다).
psycopg 의존을 만들지 않으려고 `docker exec artel-pg psql` 에 SQL 을 흘려 넣는다.
"""

import json
import subprocess
import sys

SRC = "/Users/dem/Downloads/golden-content-map.json"
GAME_BUILD_ID = int(sys.argv[1]) if len(sys.argv) > 1 else 2  # 기본값: project 3 (wordventure)

# 근거 문서의 확실성 어휘를 스키마 어휘로 옮긴다(V44).
CONFIDENCE = {"verified": "exact", "partial": "ambiguous"}


def q(v):
    """SQL 리터럴. None 은 NULL, bool 은 true/false, 나머지는 작은따옴표 이스케이프."""
    if v is None:
        return "NULL"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        return str(v)
    return "'" + str(v).replace("'", "''") + "'"


def j(v):
    return q(json.dumps(v, ensure_ascii=False)) + "::jsonb"


def main():
    d = json.load(open(SRC))
    build = d.get("build") or {}
    out = ["BEGIN;"]

    # 기존 지도는 지운다. content_map 을 지우면 scene/capability/effect/edge 가 CASCADE 로 함께 간다.
    out.append(f"DELETE FROM content_map WHERE game_build_id = {GAME_BUILD_ID};")

    out.append(f"""INSERT INTO content_map (
        game_build_id, schema_version, capture, evidence_digest,
        unity, platform, backend, development, sdk_version)
      VALUES ({GAME_BUILD_ID}, {d['schemaVersion']}, {q(d['capture'])}, {q(d['evidenceDigest'])},
        {q(build.get('unity'))}, {q(build.get('platform'))}, {q(build.get('backend'))},
        {q(build.get('development'))}, {q(build.get('sdkVersion'))});""")
    out.append("CREATE TEMP TABLE _map AS SELECT currval('content_map_id_seq') AS id;")

    # 씬 → 기능 → 효과. 간선은 씬이 전부 들어간 뒤에 넣는다(to_scene_id 를 이름으로 잇는다).
    capabilities = 0
    effects = 0
    for scene in d["scenes"]:
        out.append(f"""INSERT INTO scene (content_map_id, name, summary, walked, gaps)
          VALUES ((SELECT id FROM _map), {q(scene['name'])}, {q(scene.get('summary'))},
                  {q(scene.get('walked', False))}, {j(scene.get('gaps', []))});""")
        out.append(f"CREATE TEMP TABLE _s AS SELECT currval('scene_id_seq') AS id;")

        for cap in scene.get("capabilities", []):
            when = cap.get("when") or {}
            control = when.get("control") or {}
            given = cap.get("given") or {}
            ev = cap.get("evidence") or {}
            # 골든 파일의 capabilityId 는 원본 번호다. DB 는 자기 시퀀스를 쓰므로 매핑을 남긴다.
            # `status` 는 세 축에서 유도되는 생성 컬럼이라 쓸 수 없다(V45). 골든 파일의 status 를
            # 실행 축으로 옮겨 적는다 — 관측·적용 축은 문서가 말하지 않으므로 기본값에 맡긴다.
            out.append(f"""INSERT INTO capability (
                scene_id, content_map_id, origin, verification, summary, given_text,
                control_selector, control_path, control_label,
                interaction, input_key, input_phase, actionability)
              VALUES ((SELECT id FROM _s), (SELECT id FROM _map), {q(cap['origin'])},
                {q(cap.get('verification', 'unverified'))},
                {q(cap['summary'])}, {q(given.get('text'))},
                {q(control.get('selector'))}, {q(control.get('path'))}, {q(control.get('label'))},
                {q(when.get('interaction', 'none'))}, {q(when.get('inputKey'))},
                {q(when.get('inputPhase'))}, {q(cap.get('status', 'runnable'))});""")
            out.append(f"""INSERT INTO _src_capability (src_id, db_id)
              VALUES ({cap['capabilityId']}, currval('capability_id_seq'));""")
            capabilities += 1

            # 근거 레코드가 없는 기능이 있다(골든 파일이 `gaps: ["no-evidence-record"]` 로 표시).
            # 스키마가 요구하는 값이 없으므로 그 행은 넣지 않는다 — 기능 자체는 그대로 들어간다.
            if ev and all(ev.get(k) for k in ("entryId", "ownerType", "method",
                                              "recordKind", "triggerKind", "analysisConfidence")):
                # 확실성 어휘가 바뀌었다(V44): verified·partial → exact·ambiguous·unresolved.
                confidence = CONFIDENCE.get(ev["analysisConfidence"], ev["analysisConfidence"])
                # 호출 경로가 비면 그 사실을 gap 으로 적어야 한다(ck_capability_evidence_call_path_or_gap).
                gaps = list(ev.get("gaps", []))
                if not ev.get("callPath") and "call-path-missing" not in gaps:
                    gaps.append("call-path-missing")
                if not ev.get("methodId") and "method-id-missing" not in gaps:
                    gaps.append("method-id-missing")
                out.append(f"""INSERT INTO capability_evidence (
                    capability_id, entry_id, owner_type, method, method_id,
                    record_kind, trigger_kind, analysis_confidence,
                    condition_tree, call_path, gaps)
                  VALUES (currval('capability_id_seq'), {q(ev['entryId'])}, {q(ev['ownerType'])},
                    {q(ev['method'])}, {q(ev.get('methodId'))}, {q(ev['recordKind'])},
                    {q(ev['triggerKind'])}, {q(confidence)},
                    {j(given.get('tree', {}))}, {j(ev.get('callPath', []))}, {j(gaps)});""")

            for eff in cap.get("then", []):
                out.append(f"""INSERT INTO capability_effect (
                    capability_id, origin, category, kind, target, detail, watchable)
                  VALUES (currval('capability_id_seq'), {q(eff.get('origin', 'evidence'))},
                    {q(eff['category'])}, {q(eff['kind'])}, {q(eff.get('target'))},
                    {q(eff.get('detail'))}, {q(eff.get('watchable', False))});""")
                effects += 1

        out.append("DROP TABLE _s;")

    edges = 0
    for scene in d["scenes"]:
        for edge in scene.get("edges", []):
            via = edge.get("viaCapabilityId")
            capability = (
                f"(SELECT db_id FROM _src_capability WHERE src_id = {via})" if via is not None else "NULL"
            )
            out.append(f"""INSERT INTO scene_edge (
                from_scene_id, to_scene_name, to_scene_id, capability_id,
                given_text, source, verified_at)
              VALUES (
                (SELECT id FROM scene WHERE content_map_id = (SELECT id FROM _map) AND name = {q(scene['name'])}),
                {q(edge['to'])},
                (SELECT id FROM scene WHERE content_map_id = (SELECT id FROM _map) AND name = {q(edge['to'])}),
                {capability}, {q(edge.get('givenText'))}, {q(edge.get('source', 'static'))},
                {q(edge.get('verifiedAt'))});""")
            edges += 1

    out.insert(1, "CREATE TEMP TABLE _src_capability (src_id BIGINT, db_id BIGINT);")
    out.append("COMMIT;")

    sql = "\n".join(out)
    print(
        f"scenes={len(d['scenes'])} capabilities={capabilities} effects={effects} edges={edges}",
        file=sys.stderr,
    )
    proc = subprocess.run(
        ["docker", "exec", "-i", "artel-pg", "psql", "-U", "postgres", "-d", "postgres",
         "-v", "ON_ERROR_STOP=1", "-q"],
        input=sql, text=True, capture_output=True,
    )
    print(proc.stdout, proc.stderr, file=sys.stderr)
    sys.exit(proc.returncode)


if __name__ == "__main__":
    main()
