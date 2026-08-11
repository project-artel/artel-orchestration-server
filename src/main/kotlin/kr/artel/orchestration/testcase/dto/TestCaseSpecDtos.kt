package kr.artel.orchestration.testcase.dto

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

/**
 * Agent가 보내는 기능 테스트 명세 봉투(ARTEL-329).
 *
 * ```
 * { "id": …, "revision": 3,
 *   "cases": [ { schema_version, spec, metadata }, … ],
 *   "created_at": …, "updated_at": … }
 * ```
 *
 * **봉투 자체는 저장하지 않는다.** 원문 JSON은 파싱 후 버리고, 필요한 값만 각 케이스 행에 찍는다
 * ([revision] → `spec_revision`, [createdAt] → `source_sent_at`). 산출물로 남는 것은 XLSX 하나와
 * `test_case` 행들뿐이다.
 *
 * [id]는 Agent 쪽 식별자라 쓰지 않는다 — 우리 PK와 다른 공간이고, 받아 두면 어느 쪽 id인지
 * 헷갈리는 값이 하나 더 생긴다. 필드를 선언은 해 둔다(무엇이 오는지 계약에 남기려고).
 *
 * @property revision 이 명세 판의 번호. 같은 판이 다시 오면 적재를 통째로 건너뛰고, 적재 후
 *   `spec_revision`이 이보다 낮은 행은 이번 명세에 없던 케이스다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TestCaseSpecEnvelope(
    val id: String? = null,
    val revision: Int? = null,
    /**
     * 케이스 배열.
     *
     * 키 이름이 아직 확정되지 않아 흔한 표기를 함께 받는다 — CSV 시절 열 이름을 후보로 받던 것과
     * 같은 판단이다(`TestCaseSpecService`의 옛 `*_HEADERS`). 확정되면 별칭을 하나로 줄인다.
     * TODO(ARTEL-329): 보내는 쪽과 키 이름을 확정하면 [JsonAlias]를 제거한다.
     */
    @JsonAlias("test_cases", "items", "data", "specs")
    val cases: List<TestCaseSpecEntry> = emptyList(),
    /** Agent가 이 명세를 보낸 시각. 우리가 저장한 시각과 구분해 `source_sent_at`으로 남는다. */
    @JsonProperty("created_at") val createdAt: Instant? = null,
    @JsonProperty("updated_at") val updatedAt: Instant? = null,
)

/**
 * 명세 배열의 원소 하나 = TestCase 하나.
 *
 * [spec]의 필드는 컬럼으로, [metadata]는 JSONB 한 칸으로 들어간다. metadata를 쪼개지 않는 이유는
 * 그 안의 모양이 생성기 쪽 사정으로 바뀌는데 우리는 그 값으로 질의하지 않기 때문이다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TestCaseSpecEntry(
    @JsonProperty("schema_version") val schemaVersion: String? = null,
    val spec: TestCaseSpecBody,
    /**
     * `source`/`generation`을 통째로 담는 원본 노드. 구조를 세우지 않고 [JsonNode]로 받는 것은
     * 우리가 이 안을 해석하지 않고 그대로 보관하기 때문이다 — 필드가 늘어도 코드가 따라갈 필요가 없다.
     * 다만 [TestCaseSpecBody]와 달리 딱 한 값만 꺼내 쓴다: `source.spec_id`(멱등 키).
     */
    val metadata: JsonNode? = null,
)

/**
 * `spec` 블록. 이 다섯이 그대로 `test_case`의 컬럼이 된다.
 *
 * @property status 명세를 만든 쪽이 매긴 상태("ready" 등). 우리 QA 런의 결과인
 *   `verification_status`와 **다른 축**이라 다른 컬럼으로 간다. 적재는 후자를 건드리지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TestCaseSpecBody(
    val scene: String? = null,
    val precondition: String? = null,
    val step: String? = null,
    @JsonProperty("expected_value") val expectedValue: String? = null,
    val status: String? = null,
)

/**
 * 명세 한 벌을 받아 처리한 결과 요약.
 *
 * 보낸 쪽(Agent)이 무엇이 반영됐는지 알 수 있어야 재전송 여부를 판단할 수 있다. id 계열은
 * 담지 않는다 — 이 응답은 사용자 화면까지 흘러갈 수 있고, 내부 식별자를 노출할 이유가 없다.
 *
 * @property totalCases 배열에서 읽은 케이스 수
 * @property created 새로 만든 케이스 수
 * @property updated 이미 있어서 내용만 갱신한 케이스 수
 * @property skipped 케이스로 반영되지 않은 수 — 필수값(씬/스텝/기대결과) 누락, 또는 같은 배열 안에서
 *   같은 식별자가 반복돼 한 건으로 합쳐진 경우
 * @property unchanged 같은 revision이 다시 와서 손대지 않은 경우. 이때 나머지 수치는 전부 0이다.
 */
data class TestCaseSpecIngestResult(
    val totalCases: Int,
    val created: Int,
    val updated: Int,
    val skipped: Int,
    val unchanged: Boolean = false,
)

/** 명세 XLSX 다운로드 티켓. 요청할 때마다 새로 발급하는 단기 URL이다. */
data class TestCaseSpecDownloadResponse(
    val downloadUrl: String,
    val expiresAt: Instant,
)
