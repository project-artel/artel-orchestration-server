#!/usr/bin/env bash
# Runs only in the trusted, serial CD job. The old container retains its env/config for rollback.
set -euo pipefail
: "${IMAGE_ID:?}" "${TARGET_ENV:?}" "${ENV_FILE:?}" "${SOURCE_JOB:?}" "${SOURCE_BUILD:?}" "${SOURCE_COMMIT:?}"
[[ $IMAGE_ID =~ ^sha256:[a-f0-9]{64}$ ]] || { echo 'Invalid image ID' >&2; exit 2; }
[[ $TARGET_ENV == stage || $TARGET_ENV == operation ]] || exit 2
[[ $SOURCE_BUILD =~ ^[1-9][0-9]*$ && $SOURCE_COMMIT =~ ^[a-f0-9]{40}$ ]] || exit 2
[[ -f $ENV_FILE ]] || exit 2
CONTAINER_NAME="artel-orchestration-server-$TARGET_ENV"
BACKUP_NAME="$CONTAINER_NAME-rollback"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-180}"
HEALTH_INTERVAL_SECONDS="${HEALTH_INTERVAL_SECONDS:-3}"
HEALTH_SUCCESSES="${HEALTH_SUCCESSES:-3}"
[[ $HEALTH_TIMEOUT_SECONDS =~ ^[1-9][0-9]*$ && $HEALTH_INTERVAL_SECONDS =~ ^[0-9]+$ && $HEALTH_SUCCESSES =~ ^[1-9][0-9]*$ ]] || exit 2
# Match the manifest to the already built local image before stopping anything.
[[ $(docker image inspect --format '{{index .Config.Labels "artel.ci.job"}}' "$IMAGE_ID") == "$SOURCE_JOB" ]] || exit 2
[[ $(docker image inspect --format '{{index .Config.Labels "artel.ci.build"}}' "$IMAGE_ID") == "$SOURCE_BUILD" ]] || exit 2
[[ $(docker image inspect --format '{{index .Config.Labels "artel.ci.commit"}}' "$IMAGE_ID") == "$SOURCE_COMMIT" ]] || exit 2
if docker inspect "$BACKUP_NAME" >/dev/null 2>&1; then
    echo "Unresolved rollback container $BACKUP_NAME; inspect manually before deploying." >&2
    exit 2
fi

healthy() {
    local name=$1 baseline=$2 deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS)) consecutive=0
    while (( SECONDS < deadline )); do
        [[ $(docker inspect -f '{{.State.Running}}' "$name") == true ]] || return 1
        [[ $(docker inspect -f '{{.RestartCount}}' "$name") == "$baseline" ]] || return 1
        local code
        code=$(docker exec "$name" curl -sS -o /dev/null -w '%{http_code}' --max-time 5 \
            http://localhost:8080/api/auth/providers 2>/dev/null) || code=000
        if [[ $code == 200 ]]; then
            consecutive=$((consecutive + 1))
            ((consecutive >= HEALTH_SUCCESSES)) && return 0
        else
            consecutive=0
        fi
        sleep "$HEALTH_INTERVAL_SECONDS"
    done
    return 1
}

backup_created=0
new_attempted=0
committed=0
rollback() {
    local status=$?
    trap - EXIT INT TERM
    if ((committed)); then return; fi
    set +e
    if ((new_attempted)); then docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1; fi
    if ((backup_created)); then
        if docker rename "$BACKUP_NAME" "$CONTAINER_NAME" && docker start "$CONTAINER_NAME" >/dev/null; then
            local baseline
            baseline=$(docker inspect -f '{{.RestartCount}}' "$CONTAINER_NAME")
            if healthy "$CONTAINER_NAME" "$baseline"; then
                echo 'ROLLBACK_OK: previous container restored; deployment failed.' >&2
            else
                echo 'ROLLBACK_UNHEALTHY: manual recovery required.' >&2
            fi
        else
            echo 'ROLLBACK_FAILED: manual recovery required.' >&2
        fi
    else
        echo 'NO_PREVIOUS_CONTAINER: deployment failed.' >&2
    fi
    ((status != 0)) || status=1
    exit "$status"
}
trap rollback EXIT
trap 'exit 143' TERM
trap 'exit 130' INT

if docker inspect "$CONTAINER_NAME" >/dev/null 2>&1; then
    # Rename before stop so a stop failure can still restore the original name/config.
    docker rename "$CONTAINER_NAME" "$BACKUP_NAME"
    backup_created=1
    docker stop "$BACKUP_NAME" >/dev/null
fi
new_attempted=1
docker run -d --name "$CONTAINER_NAME" --restart no --network app-net \
    --env-file "$ENV_FILE" -e "SPRING_PROFILES_ACTIVE=$TARGET_ENV" "$IMAGE_ID" >/dev/null
healthy "$CONTAINER_NAME" 0
# Enable restart only after consecutive readiness responses, avoiding startup loops.
docker update --restart unless-stopped "$CONTAINER_NAME" >/dev/null
committed=1
if ((backup_created)); then
    docker rm "$BACKUP_NAME" >/dev/null || echo 'Healthy deployment, but rollback container cleanup failed.' >&2
fi
echo "DEPLOYED $IMAGE_ID ($TARGET_ENV)"
