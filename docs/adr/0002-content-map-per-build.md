# ADR 0002 — content_map 을 게임 빌드당 하나로 모은다

- 상태: 뒤집힘 (V40 이 세운 것을 11 일 뒤 V63 이 뒤집음)
- 근거: `db/migration/V40__create_content_map.sql`,
  `db/migration/V63__collapse_content_map_to_one_per_build.sql`,
  [`.plan/general/2026-08-28-one-content-map-per-build.md`](../../.plan/general/2026-08-28-one-content-map-per-build.md),
  `contentmap/ingest/ContentMapIngestService.kt`

## 결정

- `content_map` 은 게임 빌드 하나에 하나입니다. 유일 키가 `(game_build_id, capture)` 에서
  `(game_build_id)` 로 바뀌었습니다.
- `capture` 는 `scene` 으로 내려갔습니다. 값은 그대로 `editor`, `editor-play`, `player` 셋입니다.
- 같은 씬을 다시 읽으면 **마지막 walk 가 이깁니다.**

```sql
ALTER TABLE content_map DROP CONSTRAINT IF EXISTS uk_content_map_build_capture;
ALTER TABLE content_map ADD CONSTRAINT uk_content_map_build UNIQUE (game_build_id);
```

- NOT NULL 셋이 함께 풀렸습니다 — `schema_version`, `evidence_digest`, `capture`. 문서 없이도 지도를
  만들 수 있어야 하기 때문입니다.
- `content_map.capture` 컬럼 자체는 남아 있지만 판정에 쓰지 않습니다. 마지막으로 등록된 `evidence`
  문서가 신고한 값을 보여 주는 요약일 뿐입니다.

## 왜 처음에는 쌍이었나

V40 은 `capture` 를 키에 넣는 이유를 길게 적었고, 그 논증은 지금도 틀리지 않았습니다.

> `-- capture 를 키에 넣는 이유: editor 는 저장된 값이고 player 는 플레이가 지나간 뒤의 값이라`
> `-- 같은 필드가 다른 뜻이다. 적의 label 이 authored 20 인가 남은 체력 20 인가가 갈린다.`

- 같은 필드를 두 시점에서 읽으면 뜻이 다릅니다. 한 행에 눌러 담으면 그 구분이 사라집니다.

## 왜 뒤집었나

- **소비자가 그 쌍을 읽지 않습니다.** 어느 조회도 editor 값과 player 값을 함께 꺼내지 않고, 전부
  `capture` 하나를 골라 읽습니다. 테스트 케이스 생성도 한쪽만 봅니다.
- 쌍을 유지하는 값은 스키마 전체가 치르는데 그 값을 쓰는 곳이 없었습니다.
- 빌드당 지도가 둘일 수 있다는 사실이 그 위의 모든 조회를 "어느 쪽 지도냐" 로 시작하게 만들었습니다.

**V63 은 V40 의 논증에 답하지 않습니다.** 잃는 것을 적어 두고 판단을 미룹니다.

> `-- 잃는 것을 적어 둔다: capture 를 키에서 빼면 editor 값과 player 값을 한 빌드에서 동시에 들 수 없다.`
> `-- 그 쌍은 지금도 아무도 안 읽으므로 실사용 손실이 없고, ...`

- plan 문서의 Non-goals 가 그것을 명시합니다 — `capture` 별 값 쌍의 보존은 **ARTEL-638** 이 결정할 일이고
  이 마이그레이션의 목표가 아닙니다.
- 그러니 이 ADR 이 기록하는 것은 "쌍이 필요 없다" 가 아니라 "쌍을 지금 아무도 안 쓰므로 미룬다" 입니다.
  다시 필요해지면 V40 의 문장을 그대로 다시 읽어야 합니다.

## 거절한 대안

| 대안 | 왜 거절했나 |
| --- | --- |
| `(game_build_id, capture)` 를 그대로 둔다 | 아무도 안 읽는 구분을 위해 모든 조회가 "어느 지도냐" 로 시작해야 함 |
| 빌드당 여러 지도를 두고 하나를 primary 로 표시한다 | 행이 여전히 여럿이라 조회의 복잡도가 그대로 남음 |
| `capture` 를 `content_map` 에 남긴 채 유일 키만 바꾼다 | 한 지도의 여러 씬이 서로 다른 `capture` 에서 올 수 있는데 그것을 적을 자리가 없어짐 |

## 대가

| 대가 | 무엇이 일어나나 |
| --- | --- |
| 되돌릴 수 없는 삭제 | 빌드마다 `MAX(id)` 만 남기고 나머지 `content_map` 행을 지움. CASCADE 로 옮겨지지 못한 `scene` 과 문서 행이 함께 사라짐 |
| 복구 수단이 덤프뿐 | 합쳐진 행은 복원되지 않음. 배포 전 덤프가 유일한 길 |
| 쌍을 다시 원하면 스키마를 다시 만져야 함 | ARTEL-638 이 그 결정을 들고 있음 |
| 마지막 walk 가 이김 | 오래된 walk 로 덮어쓰는 실행이 있으면 조용히 최신 값을 잃음 |

```sql
DELETE FROM content_map loser
 USING (
           SELECT game_build_id, MAX(id) AS winner_id
             FROM content_map
            GROUP BY game_build_id
       ) winners
 WHERE winners.game_build_id = loser.game_build_id
   AND loser.id <> winners.winner_id;
```

- 이 `DELETE` 는 마이그레이션 안에 있고 되돌리는 마이그레이션이 없습니다.
- 지금 쓰기 경로는 `ContentMapIngestService.upsertScenes` 이고, 다시 읽은 씬의 `capture` 를 그대로
  덮어씁니다. "마지막 walk 가 이긴다" 는 규칙이 코드에서 뜻하는 것이 이 한 줄입니다.
