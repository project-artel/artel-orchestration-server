package kr.artel.orchestration.sdk.service

import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * 접속을 허용할 사용자 및 SDK의 sdkId 유효성을 관리하고 검증하는 인메모리 서비스
 */
@Service
class SdkIdVerificationService {
    private val validSdkIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * 유효한 sdkId를 신규로 등록합니다.
     */
    fun registerSdkId(sdkId: String): Boolean {
        return validSdkIds.add(sdkId)
    }

    /**
     * 전달받은 sdkId가 사전에 등록된 유효한 sdkId인지 검증합니다.
     */
    fun isValid(sdkId: String): Boolean {
        return validSdkIds.contains(sdkId)
    }

    /**
     * 현재 메모리에 등록된 모든 유효한 sdkId 목록을 반환합니다.
     */
    fun getAllValidSdkIds(): Set<String> {
        return validSdkIds.toSet()
    }
}
