package kr.artel.orchestration.project

import kr.artel.orchestration.project.config.StorageProperties
import kr.artel.orchestration.project.storage.S3DocumentStorage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock

class S3DocumentStorageTest {

    private fun properties(
        accessKey: String? = "local-access-key",
        secretKey: String? = "local-secret-key",
        endpoint: String? = null
    ) = StorageProperties(
        bucket = "artel-test",
        region = "ap-northeast-2",
        endpoint = endpoint,
        accessKey = accessKey,
        secretKey = secretKey
    )

    /**
     * 설정에 담긴 키만으로 서명이 되어야 한다.
     *
     * spring-dotenv는 .env를 Spring Environment에만 넣고 프로세스 환경변수로 만들지 못해,
     * AWS SDK의 기본 제공자 체인은 .env의 AWS_ACCESS_KEY_ID를 보지 못한다. 그래서 자격증명이
     * 주변에 전혀 없는 환경에서도 이 경로가 성공해야 한다. 이 테스트가 그것을 고정한다.
     */
    @Test
    fun `presigns an upload using credentials from configuration`() {
        val storage = S3DocumentStorage(properties(), Clock.systemUTC())

        val presigned = storage.presignUpload("projects/1/documents/abc/기획서.pdf", "application/pdf", 1024)

        assertThat(presigned.url).contains("artel-test")
        assertThat(presigned.url).contains("X-Amz-Signature")
        assertThat(presigned.requiredHeaders).containsEntry("Content-Type", "application/pdf")
    }

    @Test
    fun `presigns a download that restores the original file name`() {
        val storage = S3DocumentStorage(properties(), Clock.systemUTC())

        val presigned = storage.presignDownload("projects/1/documents/abc/기획서.pdf", "기획서.pdf")

        assertThat(presigned.url).contains("X-Amz-Signature")
        assertThat(presigned.url).contains("response-content-disposition")
    }

    /** MinIO 같은 커스텀 엔드포인트는 버킷을 서브도메인으로 붙일 수 없어 path-style이어야 한다. */
    @Test
    fun `uses path-style addressing for a custom endpoint`() {
        val storage = S3DocumentStorage(
            properties(endpoint = "http://localhost:9000"),
            Clock.systemUTC()
        )

        val presigned = storage.presignUpload("projects/1/documents/abc/a.pdf", "application/pdf", 10)

        assertThat(presigned.url).startsWith("http://localhost:9000/artel-test/")
    }

    /**
     * 키 하나만 채운 것은 설정 실수다. 조용히 기본 제공자 체인으로 넘어가면 로컬에서는
     * 알 수 없는 자격증명 오류로, 배포에서는 의도치 않은 자격증명으로 이어진다.
     */
    @Test
    fun `refuses a half-configured credential pair`() {
        assertThatThrownBy { properties(secretKey = null) }
            .isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy { properties(accessKey = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    /** 둘 다 비면 기본 제공자 체인을 쓴다. 배포에서 인스턴스 역할로 도는 경로다. */
    @Test
    fun `allows both credentials to be empty`() {
        val properties = properties(accessKey = null, secretKey = null)

        assertThat(properties.staticCredentials).isNull()
    }

    /**
     * 자격증명을 어디서도 찾지 못하면 서명 없이 간다.
     *
     * presigned URL은 정의상 서명이므로 이때는 만들 수 없다. 대신 객체를 그대로 가리키는
     * URL을 돌려주며, 버킷이 익명 쓰기를 허용할 때만 통한다.
     */
    @Test
    fun `falls back to an unsigned url when no credentials exist`() {
        val storage = S3DocumentStorage(
            properties(accessKey = null, secretKey = null),
            Clock.systemUTC(),
            credentials = null
        )

        val presigned = storage.presignUpload("projects/1/documents/abc/plan.pdf", "application/pdf", 10)

        assertThat(presigned.url)
            .isEqualTo("https://artel-test.s3.ap-northeast-2.amazonaws.com/projects/1/documents/abc/plan.pdf")
        assertThat(presigned.url).doesNotContain("X-Amz-Signature")
        assertThat(presigned.requiredHeaders).containsEntry("Content-Type", "application/pdf")
    }

    @Test
    fun `builds an unsigned url against a custom endpoint`() {
        val storage = S3DocumentStorage(
            properties(accessKey = null, secretKey = null, endpoint = "http://localhost:9000/"),
            Clock.systemUTC(),
            credentials = null
        )

        val presigned = storage.presignDownload("projects/1/documents/abc/plan.pdf", "plan.pdf")

        assertThat(presigned.url)
            .isEqualTo("http://localhost:9000/artel-test/projects/1/documents/abc/plan.pdf")
    }

    /** 키에 한글이나 공백이 있어도 경로 구분자는 살아 있어야 한다. */
    @Test
    fun `encodes an unsigned url per path segment`() {
        val storage = S3DocumentStorage(
            properties(accessKey = null, secretKey = null),
            Clock.systemUTC(),
            credentials = null
        )

        val presigned = storage.presignUpload("projects/1/documents/abc/기획서 v2.pdf", "application/pdf", 10)

        assertThat(presigned.url).endsWith("/projects/1/documents/abc/%EA%B8%B0%ED%9A%8D%EC%84%9C%20v2.pdf")
    }
}
