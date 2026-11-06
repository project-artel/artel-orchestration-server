package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.game.service.StoredGameFunctionCatalog
import kr.artel.orchestration.qa.dto.CreateQaRunRequest
import kr.artel.orchestration.qa.dto.CreateQaTryRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * cheat · behavior run 옵션의 검증, 기본값, 카탈로그와의 맞춤, 세션 개설 본문(ARTEL-1053).
 *
 * 여기서 깨지면 증상이 조용하다. 모드 오타가 `on` 으로 떨어지면 cheat 없이 돈다고 믿은 arm 이 cheat 를 쓰고, 목록이 비었는데
 * `arch.cheats` 가 나가면 agent 는 쓸 것이 없는 cheat tool 을 받는다.
 */
class QaFunctionOptionsTest {
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    private fun entry(name: String): JsonNode =
        objectMapper.readTree("""{"name":"$name","description":"d","params":[],"target":null,"group":null}""")

    private val catalog = StoredGameFunctionCatalog(
        revision = 2,
        receivedAt = Instant.parse("2026-10-14T08:00:00Z"),
        cheats = listOf(entry("give_gold"), entry("skip_tutorial")),
        behaviors = listOf(entry("auto_attack"), entry("flee"))
    )

    private fun tryRequest(
        cheatMode: String? = null,
        behaviorMode: String? = null,
        allowedCheats: List<String?>? = null,
        allowedBehaviors: List<String?>? = null
    ) = CreateQaTryRequest(
        testScenarioId = "1", gameInstanceId = "2",
        cheatMode = cheatMode, behaviorMode = behaviorMode,
        allowedCheats = allowedCheats, allowedBehaviors = allowedBehaviors
    )

    // ------------------------------------------------------------------ 요청 검증과 기본값

    @Test
    fun `옵션을 안 주면 둘 다 on 이고 허용 목록은 null 이다`() {
        val options = tryRequest().toFunctionOptions()

        assertThat(options.cheatMode).isEqualTo(FunctionMode.ON)
        assertThat(options.behaviorMode).isEqualTo(FunctionMode.ON)
        assertThat(options.allowedCheats).isNull()
        assertThat(options.allowedBehaviors).isNull()
    }

    @Test
    fun `run 요청과 단일 시나리오 요청이 같은 값을 같은 말로 거절한다`() {
        val run = { mode: String -> CreateQaRunRequest(testRunId = "1", gameInstanceId = "2", cheatMode = mode) }

        assertThat(run("OFF").toFunctionOptions().cheatMode).isEqualTo(FunctionMode.OFF)
        assertThat(tryRequest(cheatMode = " off ").toFunctionOptions().cheatMode).isEqualTo(FunctionMode.OFF)
        assertThatThrownBy { run("frozen").toFunctionOptions() }
            .isInstanceOf(BadRequestException::class.java).hasMessage("cheatMode must be one of [on, off]")
        assertThatThrownBy { tryRequest(behaviorMode = "frozen").toFunctionOptions() }
            .isInstanceOf(BadRequestException::class.java).hasMessage("behaviorMode must be one of [on, off]")
    }

    @Test
    fun `빈 이름과 null 원소는 400 이고 중복은 하나로 접는다`() {
        assertThatThrownBy { tryRequest(allowedCheats = listOf("a", " ")).toFunctionOptions() }
            .isInstanceOf(BadRequestException::class.java).hasMessageContaining("allowedCheats")
        assertThatThrownBy { tryRequest(allowedBehaviors = listOf(null)).toFunctionOptions() }
            .isInstanceOf(BadRequestException::class.java).hasMessageContaining("allowedBehaviors")

        assertThat(tryRequest(allowedCheats = listOf(" a ", "a", "b")).toFunctionOptions().allowedCheats)
            .containsExactly("a", "b")
        assertThat(tryRequest(allowedCheats = emptyList()).toFunctionOptions().allowedCheats).isEmpty()
    }

    // ------------------------------------------------------------------ 카탈로그와 맞춤

    @Test
    fun `허용 목록이 null 이면 카탈로그 전부이고 목록이 있으면 그 이름만 카탈로그 순서로 고른다`() {
        val all = QaFunctionOptions().resolve(catalog)
        assertThat(all.effectiveCheats).containsExactly("give_gold", "skip_tutorial")
        assertThat(all.effectiveBehaviors).containsExactly("auto_attack", "flee")

        val some = QaFunctionOptions(allowedCheats = listOf("skip_tutorial"), allowedBehaviors = emptyList()).resolve(catalog)
        assertThat(some.effectiveCheats).containsExactly("skip_tutorial")
        assertThat(some.effectiveBehaviors).isEmpty()
    }

    @Test
    fun `off 면 허용 목록이 있어도 비고 이름 검사는 그대로 한다`() {
        val resolved = QaFunctionOptions(cheatMode = FunctionMode.OFF, allowedCheats = listOf("give_gold")).resolve(catalog)
        assertThat(resolved.cheats).isEmpty()
        assertThat(resolved.behaviors).hasSize(2)

        assertThatThrownBy { QaFunctionOptions(cheatMode = FunctionMode.OFF, allowedCheats = listOf("nope")).resolve(catalog) }
            .isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `카탈로그에 없는 이름은 400 이고 목록을 받은 적 없는 빌드는 이름을 따지지 않는다`() {
        assertThatThrownBy { QaFunctionOptions(allowedBehaviors = listOf("flee", "fly")).resolve(catalog) }
            .isInstanceOf(BadRequestException::class.java).hasMessageContaining("allowedBehaviors").hasMessageContaining("fly")

        val unseen = QaFunctionOptions(allowedCheats = listOf("anything")).resolve(StoredGameFunctionCatalog.EMPTY)
        assertThat(unseen.cheats).isEmpty()
        assertThat(unseen.behaviors).isEmpty()
    }

    // ------------------------------------------------------------------ run_config

    @Test
    fun `run_config 에 계약의 키로 쓰고 allowed 가 null 이면 그 키는 쓰지 않는다`() {
        val config = objectMapper.createObjectNode()
        QaFunctionOptions(allowedBehaviors = listOf("flee")).resolve(catalog).writeTo(config)

        assertThat(config["cheat_mode"].asText()).isEqualTo("on")
        assertThat(config["behavior_mode"].asText()).isEqualTo("on")
        assertThat(config.has("allowed_cheats")).isFalse()
        assertThat(config["allowed_behaviors"].map { it.asText() }).containsExactly("flee")
        assertThat(config["effective_cheats"].map { it.asText() }).containsExactly("give_gold", "skip_tutorial")
        assertThat(config["effective_behaviors"].map { it.asText() }).containsExactly("flee")
    }

    @Test
    fun `run_config 에서 읽어 오고 키가 없는 옛 런은 on 과 빈 목록이다`() {
        val config = objectMapper.createObjectNode()
        QaFunctionOptions(cheatMode = FunctionMode.OFF, allowedCheats = listOf("give_gold")).resolve(catalog).writeTo(config)

        val read = QaFunctionAllowList.of(config.toString(), objectMapper)
        assertThat(read.cheatMode).isEqualTo(FunctionMode.OFF)
        assertThat(read.allowedCheats).containsExactly("give_gold")
        assertThat(read.allowedBehaviors).isNull()
        assertThat(read.effectiveCheats).isEmpty()
        assertThat(read.effectiveBehaviors).containsExactly("auto_attack", "flee")

        val old = QaFunctionAllowList.of("{}", objectMapper)
        assertThat(old).isEqualTo(QaFunctionAllowList())
        assertThat(QaFunctionAllowList.of("not json", objectMapper)).isEqualTo(QaFunctionAllowList())
    }

    @Test
    fun `Agent 스냅샷이 run_config 를 갈아치워도 옵션은 남는다`() {
        val carrier = objectMapper.createObjectNode().put("knowledge_mode", "learning")
        QaFunctionOptions(allowedCheats = listOf("give_gold")).resolve(catalog).writeTo(carrier)
        val agentConfig = objectMapper.readTree("""{"model":"m","cheat_mode":"agent-wrote-this"}""")

        val merged = mergeCarriedGates(carrier.toString(), agentConfig, objectMapper)

        assertThat(merged["model"].asText()).isEqualTo("m")
        assertThat(merged["cheat_mode"].asText()).isEqualTo("on")
        assertThat(merged["allowed_cheats"].map { it.asText() }).containsExactly("give_gold")
        assertThat(merged["effective_cheats"].map { it.asText() }).containsExactly("give_gold")
        assertThat(merged["effective_behaviors"].map { it.asText() }).containsExactly("auto_attack", "flee")
    }

    // ------------------------------------------------------------------ 세션 개설 본문

    private fun context(
        cheats: List<JsonNode>? = null,
        behaviors: List<JsonNode>? = null,
        arch: JsonNode? = null
    ) = QaAgentSessionContext(
        gameInstanceId = "1", model = null, qaTryId = "7", testScenarioId = "1",
        scenario = objectMapper.createObjectNode(), arch = arch, cheats = cheats, behaviors = behaviors
    )

    @Test
    fun `목록이 있으면 context 와 arch 에 싣고 요청의 arch 와 합친다`() {
        val request = context(
            cheats = catalog.cheats,
            behaviors = catalog.behaviors,
            arch = objectMapper.readTree("""{"vision":"off"}""")
        ).toOpenRequest()
        val json = objectMapper.valueToTree<JsonNode>(request)

        assertThat(json["context"]["cheats"].map { it["name"].asText() }).containsExactly("give_gold", "skip_tutorial")
        assertThat(json["context"]["behaviors"].map { it["name"].asText() }).containsExactly("auto_attack", "flee")
        assertThat(json["arch"]["cheats"].asText()).isEqualTo("on")
        assertThat(json["arch"]["behaviors"].asText()).isEqualTo("on")
        assertThat(json["arch"]["vision"].asText()).isEqualTo("off")
    }

    @Test
    fun `비었거나 없으면 context 에도 arch 에도 키를 싣지 않는다`() {
        val empty = objectMapper.valueToTree<JsonNode>(context(cheats = emptyList(), behaviors = emptyList()).toOpenRequest())
        assertThat(empty["context"].has("cheats")).isFalse()
        assertThat(empty["context"].has("behaviors")).isFalse()
        assertThat(empty.has("arch")).isFalse()

        val keeps = objectMapper.valueToTree<JsonNode>(
            context(arch = objectMapper.readTree("""{"cheats":"off"}""")).toOpenRequest()
        )
        assertThat(keeps["arch"]["cheats"].asText()).isEqualTo("off")
    }

    @Test
    fun `한쪽만 있으면 그쪽 arch 키만 나간다`() {
        val json = objectMapper.valueToTree<JsonNode>(context(behaviors = catalog.behaviors).toOpenRequest())

        assertThat(json["context"].has("cheats")).isFalse()
        assertThat(json["arch"].has("cheats")).isFalse()
        assertThat(json["arch"]["behaviors"].asText()).isEqualTo("on")
    }

    @Test
    fun `arch 객체를 제자리에서 바꾸지 않는다`() {
        val arch = objectMapper.readTree("""{"vision":"off"}""") as ObjectNode

        context(cheats = catalog.cheats, arch = arch).toOpenRequest()

        assertThat(arch.has("cheats")).isFalse()
    }
}
