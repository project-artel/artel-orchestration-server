FROM eclipse-temurin:21-jre

WORKDIR /app

ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} app.jar

# 8080: 공개 API. 리버스 프록시가 넘기는 유일한 포트.
# 8081: 무인증 내부 API(`/internal/**`). `app-net` 안에서만 부른다.
#
# EXPOSE는 문서일 뿐 포트를 호스트에 게시하지 않는다. 8081을 외부에서 못 닿게 하는 것은
# `docker run`에 `-p`가 없다는 사실이며, 그것이 유일한 방어선이다 — docs/deployment.md 참고.
EXPOSE 8080 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
