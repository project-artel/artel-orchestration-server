package kr.artel.orchestration.testrun.entity

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * TestRun — 여러 TestScenario를 묶은 실행 세트(정의). (TestSuite 개념은 쓰지 않는다.)
 *
 * 시나리오와의 조합은 test_run_scenario junction으로 표현한다. 실제 QA 실행 인스턴스(qa_try)와의
 * 배선은 이 단계 범위 밖(후속). FK 없음(프로젝트/시나리오는 논리 참조). id가 null이면 INSERT.
 */
@Table("test_run")
data class TestRunEntity(
    @Id
    val id: Long? = null,

    @Column("project_id")
    val projectId: Long,

    @Column("name")
    val name: String,

    @Column("description")
    val description: String? = null,

    /**
     * 이 TR 을 만든 게임 버전(ARTEL-994). 만들 때 프로젝트의 최신 build 로 채운다. build 가 없던
     * 프로젝트의 TR 이나 한 번도 QA 를 돌리지 않은 옛 TR 은 null 이다.
     */
    @Column("game_build_id")
    val gameBuildId: Long? = null,

    @CreatedDate
    @Column("created_at")
    val createdAt: Instant? = null,

    @LastModifiedDate
    @Column("updated_at")
    val updatedAt: Instant? = null,
)
