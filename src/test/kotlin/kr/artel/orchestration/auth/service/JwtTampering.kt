package kr.artel.orchestration.auth.service

import org.assertj.core.api.Assertions.assertThat
import java.util.Base64

/**
 * 서명 바이트가 실제로 달라지도록 JWT를 변조해 돌려준다.
 *
 * base64url 문자열의 **마지막 글자**를 바꾸는 방식은 쓰지 않는다. HS256 서명은 32바이트이고,
 * 패딩 없는 base64url로 인코딩하면 43글자가 된다. 43 × 6 = 258비트지만 유효한 것은 256비트뿐이라
 * 마지막 글자의 하위 2비트는 패딩이고 디코딩할 때 버려진다. 그래서 `A`(0) `B`(1) `C`(2) `D`(3)는
 * 상위 4비트가 같아 **모두 같은 바이트로 풀린다**. 마지막 글자가 그중 하나일 때 — 64분의 4, 약 6% —
 * 치환은 패딩 비트만 건드리고 서명 바이트는 그대로다. 토큰은 여전히 검증에 성공하고 테스트는
 * 간헐적으로 실패한다. 실측하면 2000회 중 143회가 그랬고, 그 143회가 정확히 토큰이 살아남은
 * 143회였다.
 *
 * 그래서 문자가 아니라 바이트를 건드린다. 서명을 디코딩해 첫 바이트의 최하위 비트를 뒤집고 다시
 * 인코딩한다. 패딩이 아닌 자리라 항상 다른 서명이 되며, 결과는 실행마다 동일하게 결정적이다.
 * 중간 글자를 바꾸는 방법도 바이트를 바꾸기는 하지만, 어느 자리가 패딩인지 세어 봐야 안전한지
 * 알 수 있다. 바이트를 직접 뒤집으면 그 계산 자체가 필요 없다.
 *
 * 아래 단언은 변조가 정말 바이트를 바꿨는지 매번 확인한다. 뒤집는 방식을 나중에 누가 손대도
 * 이 테스트가 조용히 no-op으로 되돌아가지 못한다.
 */
fun tamperSignature(token: String): String {
    val (header, payload, signature) = token.split(".")
    val original = Base64.getUrlDecoder().decode(signature)

    val flipped = original.copyOf()
    flipped[0] = (flipped[0].toInt() xor 1).toByte()

    assertThat(flipped)
        .describedAs("변조된 서명은 원본과 바이트 수준에서 달라야 한다")
        .isNotEqualTo(original)

    val tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(flipped)
    return "$header.$payload.$tampered"
}
