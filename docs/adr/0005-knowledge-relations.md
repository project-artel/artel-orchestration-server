# ADR 0005 — knowledge 관계 어휘를 닫고 `LEADS_TO` 를 읽기 전용으로 동결한다

- 상태: 뒤집힘 (어휘를 다섯으로 닫은 뒤 3 주 만에 그중 하나의 쓰기를 얼림)
- 근거: [`.plan/general/2026-08-06-knowledge-edge-graph.md`](../../.plan/general/2026-08-06-knowledge-edge-graph.md),
  [`.plan/general/2026-08-27-freeze-leads-to-writes.md`](../../.plan/general/2026-08-27-freeze-leads-to-writes.md),
  `db/migration/V83__add_part_of_to_knowledge_edge_relation.sql`,
  `knowledge/entity/KnowledgeRelation.kt`, `knowledge/service/KnowledgeGraphService.kt`
- agent 쪽에서 본 같은 결정은 `artel-agent-server/docs/adr/0004-update-knowledge-tool.md` 에 있고,
  읽기 방향 표도 그쪽에 있습니다.

## 결정

- QA agent 가 knowledge 항목 사이에 걸 수 있는 관계를 다섯으로 닫습니다 — `LEADS_TO`, `CONTRADICTS`,
  `REFINES`, `DEPENDS_ON`, `REPLACES`.
- 포괄 관계 `RELATED_TO`·`SEE_ALSO` 는 두지 않습니다.
- 그중 `LEADS_TO` 는 **쓰기만** 얼립니다. 이미 저장된 행은 검색·이웃·확장·그래프 조회에 그대로 나옵니다.
- 거절은 예외가 아니라 **값**으로 돌려줍니다.

```kotlin
sealed interface KnowledgeGraphMutation {
    data class Applied(val edgeId: Long) : KnowledgeGraphMutation
    data class Rejected(val reason: String) : KnowledgeGraphMutation
}
```

## 왜 어휘를 닫았나

> `**거부한 후보**: RELATED_TO/SEE_ALSO(벡터 채널이 이미 "이것과 비슷한 게 또 뭐냐"에 답하고, 그쪽은`
> `계산값이라 오염되지 않는다. 게다가 catch-all은 기본값이 되어 — 쉬운 선택 하나와 어려운 넷이 있으면`
> `쉬운 것이 골라진다 — 그래프를 무타입으로 퇴화시킨다. 도구 설명이 대신 "넷 중 맞는 것이 없으면`
> `링크하지 말라"고 말한다)`

- 포괄 관계는 **기본값이 됩니다.** 쉬운 선택 하나와 어려운 넷을 나란히 두면 쉬운 것이 골라집니다.
- 그 순간 그래프는 타입이 없는 것과 같아집니다. 타입이 없는 그래프는 순회할 이유가 없습니다.
- "비슷한 것" 은 이미 벡터 채널이 답합니다. 그쪽은 계산값이라 오염되지 않습니다.
- 어휘를 좁히는 대신 tool 설명이 "넷 중 맞는 것이 없으면 링크하지 말라" 고 말합니다.

## 왜 `LEADS_TO` 만 얼렸나

> `` `LEADS_TO` 를 **읽기 전용**으로 만든다. 화면 지도의 소유는 `content_map` 의 `screen_transition` 과 ``
> `` `scene_edge` 로 넘어갔고, 그쪽은 관측이 근거다. 지도가 둘인데 양쪽 다 쓰기를 받으면 영원히 ``
> `갈라지므로 지식창고 쪽 사본을 얼린다.`

- 화면에서 화면으로 간다는 사실의 소유가 옮겨 갔습니다 — `screen_transition` 과 `scene_edge` 는
  관측을 근거로 삼습니다.
- 같은 사실을 적는 자리가 둘인데 둘 다 쓰기를 받으면 영원히 갈라집니다.
- 어휘에서 빼지 않고 쓰기만 얼린 이유는 **읽기가 살아 있어야 하기 때문**입니다. 이미 쌓인 행이 검색
  히트의 이웃으로, 1-hop 확장으로, 그래프 조회로 계속 나옵니다. 얼린 것은 어휘가 아니라 쓰기입니다.

## 거절이 값인 이유

> `**거절은 예외가 아니라 값이다**([KnowledgeGraphMutation]). [KnowledgeService]와 같은 이유다 —`
> `호출자가 QA WebSocket 라우터라, 거절을 예외로 알리면 receive 파이프라인이 끊겨 프레임 하나가`
> `QA 런 전체를 실패시킨다.`

- 부르는 쪽이 QA WebSocket 라우터입니다.
- 던지면 receive 파이프라인이 끊깁니다. **프레임 하나가 QA 런 전체를 죽입니다.**
- `common/error` 의 타입 예외를 쓰라는 저장소 규약이 여기서는 틀린 도구입니다. 그 규약은 HTTP 요청
  하나가 실패하는 자리를 위한 것입니다.

## `PART_OF` — DB 에는 있고 enum 에는 없다

| 어디 | 값 |
| --- | --- |
| `knowledge_edge` CHECK (V83 이후) | `LEADS_TO`, `REFINES`, `CONTRADICTS`, `DEPENDS_ON`, `REPLACES`, `PART_OF` |
| Kotlin `KnowledgeRelation` enum | `LEADS_TO`, `CONTRADICTS`, `REFINES`, `DEPENDS_ON`, `REPLACES` |

- **차이 하나가 일부러입니다.** `PART_OF` 는 문서를 적재할 때 `KnowledgeService.store` 가 최상위 상수
  `PART_OF_RELATION` 으로 직접 쓰는 값이고, agent 가 손으로 주장하는 어휘가 아닙니다.
- enum 에 넣으면 `fromWire` 가 그 문자열을 파싱하게 되고, 그 순간 agent 의 link·unlink 경로가 열립니다.
  그러면 `LEADS_TO` 처럼 서비스 층에서 다시 얼려야 합니다.
- 지금은 그럴 필요가 없습니다. agent 가 `"PART_OF"` 를 보내면 파싱 앞에서 걸립니다.

```kotlin
val relation = KnowledgeRelation.fromWire(request.relation)
    ?: return rejected("relation must be one of ${KnowledgeRelation.NAMES}")
```

- V29 의 KDoc 이 `PART_OF` 를 한 번 거부한 적이 있고 사유는 "`REFINES` 와 거의 겹친다" 였습니다. 그
  판단은 agent 어휘에 대해서는 지금도 살아 있습니다.

## 거절한 대안

| 대안 | 왜 거절했나 |
| --- | --- |
| `RELATED_TO` 나 `SEE_ALSO` 를 둔다 | catch-all 이 기본값이 되어 그래프가 무타입으로 퇴화함 |
| `LEADS_TO` 를 어휘에서 아예 뺀다 | 이미 쌓인 행의 읽기까지 막힘. 얼릴 것은 쓰기뿐임 |
| `LEADS_TO` 쓰기를 예외로 거절한다 | receive 파이프라인이 끊겨 프레임 하나가 QA 런 전체를 실패시킴 |
| `PART_OF` 를 enum 에 넣는다 | agent 의 link·unlink 가 열리고 다시 얼려야 함 |
| `screen_transition` 쪽을 knowledge 로 되돌린다 | 관측이 근거인 지도를 주장이 근거인 저장소로 옮기는 일 |

## 대가

| 대가 | 무엇이 일어나나 |
| --- | --- |
| 어휘 밖의 관계를 적을 수 없음 | agent 는 링크를 걸지 않고 지나감. 그 사실은 어디에도 안 남음 |
| 같은 뜻의 값이 DB 와 코드에서 다름 | `PART_OF` 를 읽는 코드는 enum 이 아니라 문자열 상수를 봄 |
| 얼린 관계가 어휘에 남아 있음 | 목록만 보면 쓸 수 있어 보임. 거절 사유 문자열이 그것을 설명해야 함 |
| 거절이 값이라 무시될 수 있음 | 부르는 쪽이 `Rejected` 를 안 보면 조용히 아무 일도 안 일어남 |

- 읽기가 계속 도는지는 회귀 테스트 셋이 지킵니다 — 저장된 `LEADS_TO` 가 확장에, 검색 히트의 이웃에,
  그래프 조회에 각각 나오는지.
- graph 순회 깊이는 1 또는 2 만 허용합니다. 3 이상은 노드 수가 컨텍스트 예산을 넘습니다
  (`knowledge/config/KnowledgeGraphProperties.kt`).
