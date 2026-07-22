package kr.artel.orchestration.game.service

import org.springframework.stereotype.Component
import java.security.SecureRandom

/**
 * 읽고 옮겨 적을 때 헷갈리는 글자를 뺀 알파벳. I/1, L/1, O/0, U/V가 서로 섞이는 것을 막는다.
 */
private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

private const val GROUP_LENGTH = 5
private const val GROUP_COUNT = 4

/**
 * 게임 인스턴스의 영구 자격증명을 만든다.
 *
 * `H4KQ2-8VTRM-9XZ0C-N5JWE` 형태로, 32글자 알파벳에서 20글자를 뽑아 약 100비트다.
 *
 * 대시보드와 Unity 창 모두 이 값을 복사해서 옮기지 손으로 치지 않으므로, 길이는 타이핑
 * 편의가 아니라 눈으로 대조할 수 있는지를 기준으로 정했다. 화면의 키와 붙여넣은 키가 같은지
 * 사람이 확인하는 순간이 있고, 32바이트 Base64의 43글자는 필요 이상으로 길면서 대소문자가
 * 섞여 그 확인을 불가능하게 만든다.
 */
@Component
class InstanceKeyGenerator {

    private val random = SecureRandom()

    fun generate(): String =
        (0 until GROUP_COUNT).joinToString("-") {
            (0 until GROUP_LENGTH)
                .map { ALPHABET[random.nextInt(ALPHABET.length)] }
                .joinToString("")
        }
}
