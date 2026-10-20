#!/usr/bin/env bash
#
# 배포 컨테이너를 새 이미지로 바꾸고, 새 컨테이너가 HTTP 요청에 답할 때까지 기다린다.
# 정해진 시간 안에 답하지 않으면 새 컨테이너의 로그를 남기고, 직전에 돌던 이미지로
# 컨테이너를 다시 띄운 뒤 실패로 끝난다.
#
# `docker run -d` 는 컨테이너가 만들어지면 바로 돌아온다. Spring context 가 뜨지 못해
# JVM 이 죽어도 `--restart unless-stopped` 가 컨테이너를 되살리기를 반복할 뿐이고, 빌드는
# 초록으로 끝난다. 앞 컨테이너는 이미 지운 뒤라 그동안 서버는 내려가 있다. 2026-10-20 에
# 두 controller 가 같은 경로를 등록해 stage 가 이렇게 내려갔다(ARTEL-1152).
#
# 필요한 환경 변수: CONTAINER_NAME, IMAGE_TAG, TARGET_ENV, ENV_FILE.
# Jenkinsfile 의 Deploy stage 가 채운다. 로컬에서 같은 경로를 돌려 볼 때는 직접 export 한다.
#
# 종료 코드: 0 새 컨테이너가 답했다, 1 새 컨테이너가 답하지 않았다(되돌리기 성공 여부는
# 로그에 남는다).

set -euo pipefail

: "${CONTAINER_NAME:?}" "${IMAGE_TAG:?}" "${TARGET_ENV:?}" "${ENV_FILE:?}"

DEPLOY_NETWORK="${DEPLOY_NETWORK:-app-net}"

# 이 서버에는 actuator 가 없어 health 전용 경로가 없다. `/api/auth/providers` 는 인증 없이
# 부르는 GET 이고 `signupOpen` 을 계산하려고 DB 를 읽는다. 200 이 오면 Netty 가 떴고(context
# 기동이 끝났다는 뜻이다), security filter chain 을 지났고, DB 에 닿았다는 뜻이다.
#
# 컨테이너는 `-p` 없이 `app-net` 에만 붙으므로 Jenkins 에이전트에서는 닿지 않는다. 그래서
# 컨테이너 안에서 `docker exec ... curl` 로 친다. curl 은 base 이미지(eclipse-temurin:21-jre)에
# 들어 있다.
HEALTH_URL="${HEALTH_URL:-http://localhost:8080/api/auth/providers}"

# 기동은 Flyway 마이그레이션을 포함하고, 에이전트는 세 레포의 잡이 나눠 쓴다. 느린 기동을
# 실패로 읽지 않도록 넉넉히 잡는다. 기동 실패는 RestartCount 로 먼저 잡히므로 이 상한까지
# 기다리는 것은 응답 없이 멈춘 경우뿐이다.
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-180}"
HEALTH_INTERVAL_SECONDS="${HEALTH_INTERVAL_SECONDS:-3}"

run_container() {
    docker run -d \
      --name "$CONTAINER_NAME" \
      --restart unless-stopped \
      --network "$DEPLOY_NETWORK" \
      --env-file "$ENV_FILE" \
      -e SPRING_PROFILES_ACTIVE="$TARGET_ENV" \
      "$1" >/dev/null
}

remove_container() {
    docker stop "$CONTAINER_NAME" >/dev/null 2>&1 || true
    docker rm "$CONTAINER_NAME" >/dev/null 2>&1 || true
}

# JVM 이 한 번이라도 죽으면 RestartCount 가 오른다. 기동 실패는 재시도해도 같은 이유로 다시
# 죽으므로, 그때는 시간 상한을 기다리지 않고 바로 실패로 본다.
wait_until_healthy() {
    local deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
    while ((SECONDS < deadline)); do
        if docker exec "$CONTAINER_NAME" curl -fsS -o /dev/null --max-time 5 "$HEALTH_URL" 2>/dev/null; then
            return 0
        fi
        local restarts
        restarts="$(docker inspect -f '{{.RestartCount}}' "$CONTAINER_NAME" 2>/dev/null || echo 0)"
        if ((restarts > 0)); then
            echo "컨테이너가 기동 중에 죽었다(RestartCount=$restarts)." >&2
            return 1
        fi
        sleep "$HEALTH_INTERVAL_SECONDS"
    done
    echo "${HEALTH_TIMEOUT_SECONDS}초 안에 $HEALTH_URL 이 답하지 않았다." >&2
    return 1
}

# 지우기 전에 읽어 둔다. 지우고 나면 무엇이 돌고 있었는지 알 곳이 없다. 컨테이너가 없으면
# (첫 배포) 비어 있고, 그때는 되돌릴 것이 없다.
previous_image="$(docker inspect -f '{{.Config.Image}}' "$CONTAINER_NAME" 2>/dev/null || true)"

remove_container
run_container "$IMAGE_TAG"
echo "$IMAGE_TAG 기동을 기다린다($HEALTH_URL, 최대 ${HEALTH_TIMEOUT_SECONDS}초)."

if wait_until_healthy; then
    echo "$IMAGE_TAG 가 답했다."
    exit 0
fi

echo "---- $CONTAINER_NAME 의 마지막 로그 ($IMAGE_TAG) ----" >&2
docker logs --tail 200 "$CONTAINER_NAME" >&2 || true
echo "---- 로그 끝 ----" >&2

# 실패한 컨테이너를 남겨 두지 않는다. `--restart unless-stopped` 라 남겨 두면 계속 되살아나
# 같은 이름을 차지한다. 로그는 위에 남겼다.
remove_container

if [[ -z $previous_image || $previous_image == "$IMAGE_TAG" ]]; then
    echo "되돌릴 이전 이미지가 없다. $CONTAINER_NAME 은 내려간 채로 있다." >&2
    exit 1
fi

echo "이전 이미지 $previous_image 로 되돌린다." >&2
run_container "$previous_image"
if wait_until_healthy; then
    echo "$previous_image 로 되돌렸다. 배포는 실패로 남긴다." >&2
else
    echo "$previous_image 도 답하지 않는다. 손으로 확인해야 한다." >&2
    docker logs --tail 50 "$CONTAINER_NAME" >&2 || true
fi
exit 1
