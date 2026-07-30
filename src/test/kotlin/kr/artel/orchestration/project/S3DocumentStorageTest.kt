package kr.artel.orchestration.project

import kr.artel.orchestration.project.config.StorageProperties
import kr.artel.orchestration.project.storage.S3DocumentStorage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
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

    /**
     * 한글 파일명은 RFC 5987 형식과 ASCII 폴백을 함께 실어야 한다. `filename*`만 주면 그것을
     * 모르는 클라이언트가 이름을 통째로 잃는다. 헤더 값 조립만 검증하며, 실제 브라우저가 어느
     * 쪽을 쓰는지는 수동 확인이 필요하다.
     */
    @Test
    fun `carries both an ascii fallback and the utf-8 name for a non-ascii file`() {
        val storage = S3DocumentStorage(properties(), Clock.systemUTC())

        val presigned = storage.presignDownload("projects/1/spec.xlsx", "테스트케이스명세.xlsx")

        val disposition = dispositionOf(presigned.url)
        // 순한글 이름이라 ASCII 폴백은 남길 글자가 없다. 확장자는 지킨다.
        assertThat(disposition).isEqualTo(
            "attachment; filename=\"download.xlsx\"; " +
                "filename*=UTF-8''%ED%85%8C%EC%8A%A4%ED%8A%B8%EC%BC%80%EC%9D%B4%EC%8A%A4%EB%AA%85%EC%84%B8.xlsx"
        )
    }

    /** ASCII 이름은 손대지 않는다. 확장자 앞의 공백·하이픈도 그대로 남는다. */
    @Test
    fun `keeps an ascii file name as the fallback`() {
        val storage = S3DocumentStorage(properties(), Clock.systemUTC())

        val presigned = storage.presignDownload("projects/1/spec.pdf", "spec v2-final.pdf")

        assertThat(dispositionOf(presigned.url)).startsWith("attachment; filename=\"spec v2-final.pdf\";")
    }

    /**
     * 파일 이름이 이중으로 인코딩되지 않는지 본다. URL에서 쿼리 값을 **한 번** 디코딩하면 바로
     * 헤더 값이 나와야 한다. 여기서 `%25`가 보이면 우리가 미리 한 겹 더 씌운 것이다.
     */
    @Test
    fun `does not double-encode the file name`() {
        val storage = S3DocumentStorage(properties(), Clock.systemUTC())

        val presigned = storage.presignDownload("projects/1/spec.xlsx", "테스트케이스명세.xlsx")

        val disposition = dispositionOf(presigned.url)
        assertThat(disposition).doesNotContain("%25")
        // filename*은 정확히 한 번만 나온다(폴백과 겹쳐 두 벌이 실리지 않는다).
        assertThat(disposition.split("filename*=")).hasSize(2)
    }

    /** presigned URL의 response-content-disposition 쿼리 값을 한 번 디코딩해 돌려준다. */
    private fun dispositionOf(url: String): String {
        val raw = URI.create(url).rawQuery
            .split("&")
            .first { it.startsWith("response-content-disposition=") }
            .substringAfter("=")
        return URLDecoder.decode(raw, StandardCharsets.UTF_8)
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
     * yml의 `${VAR:}`는 null이 아니라 빈 문자열로 바인딩된다. 정규화하지 않으면 "비워 둠"이
     * "빈 값을 지정함"이 되어, 서명 없는 경로 대신 빈 자격증명으로 서명을 시도하게 된다.
     */
    @Test
    fun `reads blank configuration as unset`() {
        val properties = properties(accessKey = "", secretKey = "   ", endpoint = "")

        assertThat(properties.accessKey).isNull()
        assertThat(properties.secretKey).isNull()
        assertThat(properties.endpoint).isNull()
        assertThat(properties.staticCredentials).isNull()
    }

    /** 빈 엔드포인트가 URI로 넘어가면 안 된다. 실제로 이 경로에서 500이 났었다. */
    @Test
    fun `does not treat a blank endpoint as a url`() {
        val storage = S3DocumentStorage(
            properties(endpoint = ""),
            Clock.systemUTC()
        )

        val presigned = storage.presignUpload("projects/1/documents/abc/a.pdf", "application/pdf", 10)

        assertThat(presigned.url).startsWith("https://artel-test.s3.ap-northeast-2.amazonaws.com/")
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
