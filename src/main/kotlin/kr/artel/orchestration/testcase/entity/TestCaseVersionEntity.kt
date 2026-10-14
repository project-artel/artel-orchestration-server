package kr.artel.orchestration.testcase.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 한 케이스의 한 게임 버전(build) 몫(ARTEL-1018). `(test_case_id, game_build_id)` 가 유일하다.
 *
 * 그 버전의 지도가 낸 내용 스냅샷과, 그 버전에서 QA 가 내린 판정을 담는다. 행이 없으면 그 케이스는 그
 * 버전에 없다. [TestCaseEntity] 는 케이스의 정체이고, 그 내용 칸은 가장 최근 버전의 이 행을 비춘다.
 *
 * @property contentMapId 이 내용을 낸 content map. 사람이 쓴 케이스가 판정으로 만든 행은 null 이다.
 * @property verifiedAt 이 버전에서 마지막으로 판정이 난 시각. 판정이 없으면 null 이다.
 */
@Table("test_case_version")
data class TestCaseVersionEntity(
    @Id
    val id: Long? = null,

    @Column("test_case_id")
    val testCaseId: Long,

    @Column("game_build_id")
    val gameBuildId: Long,

    @Column("content_map_id")
    val contentMapId: Long? = null,

    @Column("scene")
    val scene: String,

    @Column("step")
    val step: String,

    @Column("precondition")
    val precondition: String? = null,

    @Column("condition")
    val condition: Json? = null,

    @Column("expected_value")
    val expectedValue: String,

    @Column("expected_items")
    val expectedItems: Json? = null,

    @Column("status")
    val status: String? = null,

    @Column("capability_key")
    val capabilityKey: String? = null,

    @Column("metadata")
    val metadata: Json = Json.of("{}"),

    @Column("verification_status")
    val verificationStatus: String = "DRAFT",

    @Column("verified_at")
    val verifiedAt: Instant? = null,

    @CreatedDate
    @Column("created_at")
    val createdAt: Instant? = null,

    @LastModifiedDate
    @Column("updated_at")
    val updatedAt: Instant? = null,
) {
    companion object {
        /** 정체의 지금 내용으로 이 버전 몫을 만든다. 사람이 쓴 케이스가 판정을 처음 받을 때 쓴다. */
        fun snapshotOf(testCase: TestCaseEntity, gameBuildId: Long) = TestCaseVersionEntity(
            testCaseId = requireNotNull(testCase.id),
            gameBuildId = gameBuildId,
            scene = testCase.scene,
            step = testCase.step,
            precondition = testCase.precondition,
            condition = testCase.condition,
            expectedValue = testCase.expectedValue,
            expectedItems = testCase.expectedItems,
            status = testCase.status,
            capabilityKey = testCase.capabilityKey,
            metadata = testCase.metadata,
            verificationStatus = VerificationStatus.DRAFT.name,
        )
    }
}
