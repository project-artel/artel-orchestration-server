package kr.artel.orchestration.contentmap.render

import com.fasterxml.jackson.databind.JsonNode

/**
 * 근거 record의 원자(call / effect / input / condition / handle)를 한 줄짜리 C# 표현식·문으로
 * 옮긴다. `wv2cs.py` 에서도 150줄 안팎의 독자 섹션이라 분리 근거가 있다.
 */

/** [condExpr] 결과. `code` 가 null 이면 이 조건은 코드로 낼 수 없다는 뜻 — 대신 [comments] 를 남긴다. */
data class CondResult(val code: String?, val comments: List<String>)

private val KEY_PHASE = mapOf("down" to "GetKeyDown", "up" to "GetKeyUp", "held" to "GetKey", "hold" to "GetKey")
private val MOUSE_PHASE = mapOf("down" to "GetMouseButtonDown", "up" to "GetMouseButtonUp", "held" to "GetMouseButton")
private val MOUSE_BUTTON = mapOf("left" to "0", "right" to "1", "middle" to "2")
private val LAMBDA_REGEX = Regex("""^<(?<outer>[^>]+)>b__[\w\d_]+$""")

/**
 * write류 effect kind 3종(fix 4 로컬 판별 대상). target 에 점이 없으면 지역변수/파라미터로
 * 본다 — 근거 추출기가 필드는 항상 `Type.member` 로 내는 관례를 실측으로 확인했다(오늘
 * 데이터 100% 성립). 다만 이 관례는 로컬 struct의 멤버 write(예: 로컬 `Vector3` 의 `.z`)를
 * `Vector3.z` 처럼 **선언 타입 이름으로** 적어 필드처럼 보이게 만드는 경우까지는 구분하지
 * 못한다 — evidence 자체가 그 자리에서 리시버(로컬 변수 이름)를 안 실어 온다. 이 스코프에서
 * 고칠 수 없는 근거의 한계이며, 지어내지 않는다.
 */
private val WRITE_LIKE_KINDS = setOf("write", "ui-value", "transform")

fun callExpr(call: JsonNode): String {
    val sig = call.path("target").textOrNull()
    val p = parseSignature(sig)
    val args = call.path("args").textOrNull() ?: ""
    val recv = call.path("receiver").textOrNull()
    val where = call.path("receiverWhere").textOrNull()
    val tname = p.declaringType.substringAfterLast('.')
    val name = p.name

    if (name == ".ctor") return "new $tname($args)"
    if (name.startsWith("get_")) {
        val base = recv ?: implicitBase(tname, where)
        return "$base.${name.removePrefix("get_")}"
    }
    if (name.startsWith("set_")) {
        val base = recv ?: implicitBase(tname, where)
        return "$base.${name.removePrefix("set_")} = ${args.ifEmpty { "/* ? */" }}"
    }

    val base: String? = when {
        recv != null -> recv
        where == "this" -> null
        where == "static" -> tname
        where != null && where.startsWith("arg:") -> "p" + where.substringAfter(":")
        else -> tname
    }
    val inner = if (base == null) "$name($args)" else "$base.$name($args)"
    return if (shortType(p.returnType) == "IEnumerator") "StartCoroutine($inner)" else inner
}

private fun implicitBase(typeName: String, where: String?): String =
    if (where == "static") typeName else if (where == "this") "this" else typeName

/**
 * effect 하나 -> 문 하나. fix 1(증분 `+=`)과 fix 4(지역변수 `local` 표시)를 여기서 적용한다.
 *
 * fix 1 은 `detail` 이 `+` 로 시작할 때만 적용한다 — 양수 리터럴 대입은 evidence 추출기가
 * `detail` 에 `+` 부호를 붙이지 않으므로(`"10"` 이지 `"+10"` 이 아니다) 앞에 `+` 가 붙어
 * 있다는 것 자체가 "기존 값에 더한다"는 증분 관용구라는 뜻이다. **`-` 는 그렇지 않다** —
 * 음수 리터럴 대입(`z = -10`)도 `detail` 이 그냥 `"-10"` 이라 증분(`z -= 10`)과 문자열로
 * 구분이 안 된다. 실측 확인: `_unplaced/Cards.Util.cs` 의 `Vector3.z` write 는
 * `detail: "-10"` 인데 `Vector3 MousePos { get; }` 안에서 z를 고정 깊이로 대입하는
 * 코드다 — 증분이 아니다. `-` 까지 증분으로 읽으면 이 대입을 `-=` 로 지어내게 된다.
 * 그래서 `-` 는 원래처럼 평범한 대입으로 낸다: 확실치 않은 연산자를 지어내지 않는다.
 */
fun effectStmt(effect: JsonNode): String {
    val kind = effect.path("kind").textOrNull()
    val target = effect.path("target").textOrNull() ?: "?"
    val detail = effect.path("detail").textOrNull()
    val value = if (detail.isNullOrEmpty()) "/* ? */" else detail

    if (kind in WRITE_LIKE_KINDS) {
        val prefix = if ('.' !in target) "local " else ""
        if (!detail.isNullOrEmpty() && detail.startsWith("+")) {
            return "$prefix$target += ${detail.drop(1)};"
        }
        return "$prefix$target = $value;"
    }
    return when (kind) {
        "active-state" -> "$target.SetActive($value);"
        "animation" -> if (!detail.isNullOrEmpty()) "$target.$detail;" else "$target.Play();"
        "audio" -> "$target.${if (detail.isNullOrEmpty()) "Play" else detail}();"
        "instantiate" -> "Instantiate($target);"
        "destroy" -> "Destroy($target);"
        "scene" -> "SceneManager.LoadScene(\"$target\");"
        "saved" -> "Save(\"$target\", $value);"
        else -> "/* $kind $target = $value */"
    }
}

fun inputExpr(input: JsonNode): String {
    val kind = input.path("kind").textOrNull()
    val neg = if (input.path("absent").asBoolean(false)) "!" else ""
    return when (kind) {
        "key" -> {
            val fn = KEY_PHASE[input.path("phase").textOrNull()] ?: "GetKey"
            "${neg}Input.$fn(KeyCode.${input.path("control").textOrNull()})"
        }
        "mouse" -> {
            val fn = MOUSE_PHASE[input.path("phase").textOrNull()] ?: "GetMouseButton"
            val control = input.path("control").textOrNull()
            val btn = MOUSE_BUTTON[control?.lowercase()] ?: control
            "${neg}Input.$fn($btn)"
        }
        else -> "$neg/* input:$kind ${input.path("control").textOrNull()} */"
    }
}

/**
 * 조건 트리 -> [CondResult]. fix 3: `subjectLost` 가 붙은 원자는 `code=null` 로 접고
 * `comments` 에 "왜 못 믿는지"를 남긴다 — 진짜 조건처럼 읽히는 걸 막는다.
 * `every`/`either` 는 자식들 중 code 가 있는 것만 `&&`/`||` 로 묶고, comments 는
 * 깊이와 상관없이 전부 위로 전파한다(순서 보존).
 */
fun condExpr(condition: JsonNode?): CondResult {
    if (condition == null || condition.isMissingNode || condition.isNull) return CondResult(null, emptyList())
    val kind = condition.path("kind").textOrNull()

    if (kind == "every" || kind == "either") {
        val children = condition.path("parts").arrayOrEmpty().map { condExpr(it) }
        val comments = children.flatMap { it.comments }
        val codeParts = children.mapNotNull { it.code }
        val join = if (kind == "every") " && " else " || "
        val code = if (codeParts.isEmpty()) null else codeParts.joinToString(join) { if (' ' in it) "($it)" else it }
        return CondResult(code, comments)
    }

    val rawCode: String? = when (kind) {
        "always" -> null
        "test" -> "${condition.path("left").textOrNull()} ${condition.path("operator").textOrNull()} ${condition.path("right").textOrNull()}"
        "gesture" -> "/* gesture: ${gestureDetail(condition)} */"
        null -> null
        else -> "/* $kind */"
    }

    val subjectLost = condition.path("subjectLost").textOrNull()
    return if (subjectLost != null && rawCode != null) {
        CondResult(null, listOf("unresolved condition (subject lost): $rawCode"))
    } else {
        CondResult(rawCode, emptyList())
    }
}

/** `kind` 를 뺀 나머지 필드를 `{"key": value, ...}` 로 적는다(파이썬 `json.dumps` 기본 간격과 동일). */
private fun gestureDetail(condition: JsonNode): String =
    condition.fields().asSequence()
        .filter { (key, _) -> key != "kind" }
        .joinToString(", ", prefix = "{", postfix = "}") { (key, value) ->
            "\"$key\": ${evidenceObjectMapper.writeValueAsString(value)}"
        }

fun handlerName(handler: String?): String {
    if (handler.isNullOrBlank()) return "?"
    val p = parseSignature(handler)
    val lambda = LAMBDA_REGEX.find(p.name)
    return if (lambda != null) "() => /* lambda in ${lambda.groups["outer"]!!.value} */" else declShort(handler)
}

fun handleStmt(handle: JsonNode): String {
    val channel = handle.path("channel").textOrNull()
    val handler = handle.path("handler").textOrNull()
    val name = handlerName(handler)
    return if (!channel.isNullOrEmpty()) {
        val member = handle.path("member").textOrNull() ?: "AddListener"
        "$channel.$member($name);"
    } else {
        "yield return new WaitUntil($name);"
    }
}
