package kr.artel.orchestration.contentmap.dto

import io.r2dbc.postgresql.codec.Json

/**
 * 이 기능에 닿기까지 **실제로 거친 메서드들**(`capability_evidence.call_path`). 진입점에서 이 기능의 메서드까지 순서대로다.
 *
 * @property callPath `["System.Void Scenes.MemberView::ConfirmDownload()", …]` 꼴의 JSON 배열.
 */
data class CapabilityCallPath(
    val capabilityId: Long,
    val callPath: Json?,
)
