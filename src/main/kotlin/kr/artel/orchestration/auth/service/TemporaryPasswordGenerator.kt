package kr.artel.orchestration.auth.service

import org.springframework.stereotype.Component
import java.security.SecureRandom

/** 임시 비밀번호 길이. 아래 알파벳(56자)으로 20자면 약 116 비트다. */
const val TEMPORARY_PASSWORD_LENGTH = 20

/** 사람이 옮겨 적을 값이라 0/O, 1/l/I 처럼 헷갈리는 글자를 뺀다. */
private const val TEMPORARY_PASSWORD_ALPHABET =
    "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

/** ADMIN 이 만든 계정과 초기화한 비밀번호에 쓰는 임시 비밀번호. */
@Component
class TemporaryPasswordGenerator {
    private val random = SecureRandom()

    fun generate(): String = buildString(TEMPORARY_PASSWORD_LENGTH) {
        repeat(TEMPORARY_PASSWORD_LENGTH) {
            append(TEMPORARY_PASSWORD_ALPHABET[random.nextInt(TEMPORARY_PASSWORD_ALPHABET.length)])
        }
    }
}
