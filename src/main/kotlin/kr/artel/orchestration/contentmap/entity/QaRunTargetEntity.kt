package kr.artel.orchestration.contentmap.entity

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * qa_run_target — 런 단위 조준 해석표. `selector` → 이번 실행의 instance id.
 *
 * content_map 에 두지 않는다. **instance id 는 프로세스를 넘지 못하므로** 런이 끝나면 쓰레기다.
 *
 * [reading] 을 같이 두는 이유: 액션 실패가 "버튼이 안 먹었다"인지 "id 가 낡았다"인지 갈라야 한다.
 * 실패 시 [reading] 이 최신인지 보고 아니면 재조회 후 1회 재시도한다.
 *
 * 이 치환은 **agent 가 아니라 서버가 한다.** 기계적인 일이고, agent 에게 시키면 판독 전체를
 * 프롬프트에 넣어야 한다.
 *
 * 복합 PK 라 `@Id` 가 없다.
 */
@Table("qa_run_target")
data class QaRunTargetEntity(
    @Column("qa_run_id")
    val qaRunId: Long,

    @Column("scene_name")
    val sceneName: String,

    @Column("selector")
    val selector: String,

    @Column("instance_id")
    val instanceId: Int,

    /** 이 값을 얻은 판독 번호. 낡음 판정의 근거. */
    @Column("reading")
    val reading: Long,

    @Column("updated_at")
    val updatedAt: Instant? = null,
)
