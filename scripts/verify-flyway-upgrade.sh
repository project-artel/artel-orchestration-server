#!/usr/bin/env bash
#
# Runs the migrations against a real PostgreSQL along the path a deployment
# actually takes: the base branch's migrations first, then this branch's on top,
# then validate.
#
# scripts/check-flyway-migrations.sh compares git trees, so it sees file names
# and version numbers and nothing else. A migration whose name is correct but
# whose SQL is malformed, or which references a column that does not exist,
# passes it and first surfaces as a deployed container dying in the Flyway step.
# Executing the SQL is the only way to see that.
#
# Applying everything to an empty database would not be enough either. That is
# the fresh-install path, not the deployment path; ordering tangles and checksum
# drift exist only relative to a database that already ran the base branch's
# migrations. Hence two phases.
#
# Exit codes: 0 clean, 1 this branch breaks the upgrade, 2 the base branch is
# already broken (not this branch's defect — Jenkins marks it unstable).

set -euo pipefail

MIGRATION_DIR="src/main/resources/db/migration"

# pgvector rather than stock postgres: V18 runs CREATE EXTENSION vector, so on a
# stock image the whole chain fails from that point on. PostgresTestContainer
# picks the same image for the same reason.
POSTGRES_IMAGE="pgvector/pgvector:pg16"

# Pinned to the Flyway that Spring Boot 3.3.1's BOM manages, so CI validates with
# the engine that will run in the deployed container. Bump both together.
FLYWAY_IMAGE="flyway/flyway:10.10.0-alpine"

DB_NAME="artel"
DB_USER="artel"
DB_PASSWORD="artel"

DEPLOY_BRANCHES=" main operation develop stage "

# Base branch to compare against, resolved the way check-flyway-migrations.sh
# resolves it: explicit argument, else the merge target Jenkins exposes on PR
# jobs, else develop. The two scripts share about ten lines of this; extracting a
# shared shell library would mean editing the merged check script for no gain
# until a third caller exists.
BASE_BRANCH="${1:-${CHANGE_TARGET:-develop}}"

# In a Jenkins PR job BRANCH_NAME is "PR-<n>" and the real head branch is
# CHANGE_BRANCH.
CURRENT_BRANCH="${CHANGE_BRANCH:-$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo HEAD)}"

# Every container this run creates carries this label so the Jenkins post block
# can sweep leftovers without touching a concurrently running build's containers.
# develop's Jenkinsfile has no disableConcurrentBuilds, so that matters.
RUN_LABEL="artel-flyway-upgrade=$(printf '%s' "${BUILD_TAG:-local}" | tr -c 'A-Za-z0-9._-' '_')"

RUN_ID="$$-$(date +%s)"
PG_CONTAINER="artel-flyway-pg-$RUN_ID"
WORKDIR=""
FLYWAY_CONTAINER=""
flyway_seq=0

# Every step guarded and the function forced to succeed: under `set -e` a single
# failing removal would abandon the ones after it, and a leaked database
# container is the failure this trap exists to prevent.
cleanup() {
    if [[ -n $FLYWAY_CONTAINER ]]; then
        docker rm -f "$FLYWAY_CONTAINER" >/dev/null 2>&1 || true
    fi
    docker rm -f "$PG_CONTAINER" >/dev/null 2>&1 || true
    if [[ -n $WORKDIR ]]; then
        rm -rf "$WORKDIR" || true
    fi
    return 0
}

# INT and TERM as well as EXIT: an aborted Jenkins build sends TERM, and without
# the trap the database container outlives the job.
trap cleanup EXIT INT TERM

if [[ ! -d $MIGRATION_DIR ]]; then
    echo "error: $MIGRATION_DIR does not exist. Run this from the repository root." >&2
    exit 1
fi

if ! docker info >/dev/null 2>&1; then
    echo "error: docker is unavailable or the socket is not accessible." >&2
    echo "       This check runs the migrations against a real PostgreSQL and cannot proceed without it." >&2
    exit 1
fi

echo "== Flyway upgrade verification =="
echo "branch    : $CURRENT_BRANCH"

# ---------------------------------------------------------------------------
# Base resolution
# ---------------------------------------------------------------------------

base_ref=''

is_deploy_branch() {
    [[ $DEPLOY_BRANCHES == *" $1 "* ]]
}

if [[ -z ${CHANGE_TARGET:-} ]] && is_deploy_branch "$CURRENT_BRANCH"; then
    # A deploy branch is its own base, so there is no upgrade step to verify.
    # The single-phase run still stands: it is the only place the merged tree's
    # SQL is executed before the stage server tries it.
    echo "base      : (none, $CURRENT_BRANCH is a deploy branch — fresh-install run only)"
else
    # Fetch explicitly. A Jenkins multibranch checkout fetches narrowly, so the
    # workspace copy of origin/develop can lag by several merges, and a stale
    # base is exactly the case where the upgrade being verified is not the
    # upgrade that will happen.
    if git fetch --no-tags --quiet origin "+refs/heads/$BASE_BRANCH:refs/remotes/origin/$BASE_BRANCH" 2>/dev/null; then
        base_ref="refs/remotes/origin/$BASE_BRANCH"
    elif git rev-parse --verify --quiet "refs/remotes/origin/$BASE_BRANCH" >/dev/null; then
        base_ref="refs/remotes/origin/$BASE_BRANCH"
        echo "warning: could not fetch origin/$BASE_BRANCH; using the local copy, which may be stale" >&2
    elif git rev-parse --verify --quiet "refs/heads/$BASE_BRANCH" >/dev/null; then
        base_ref="refs/heads/$BASE_BRANCH"
        echo "warning: no remote $BASE_BRANCH; using the local branch, which may be stale" >&2
    else
        echo "warning: base branch '$BASE_BRANCH' not found; verifying the fresh-install path only" >&2
    fi
    echo "base      : ${base_ref:-<unavailable>}"
fi

WORKDIR=$(mktemp -d)
mkdir -p "$WORKDIR/base"

# The archive keeps its full path, so the SQL lands at base/<MIGRATION_DIR>.
# Stripping the leading components instead would hard-code how deep the
# migration directory sits, and getting that wrong empties the directory
# silently — which reads as "base has no migrations" and downgrades the run to a
# fresh install without saying so.
base_sql_dir="$WORKDIR/base/$MIGRATION_DIR"

base_file_count=0
if [[ -n $base_ref ]]; then
    # git archive rather than a checkout: it writes the base tree's migrations
    # without touching the working tree, which still holds this branch's.
    if git archive "$base_ref" "$MIGRATION_DIR" 2>/dev/null | tar -x -C "$WORKDIR/base" 2>/dev/null; then
        base_file_count=$(find "$base_sql_dir" -type f -name '*.sql' 2>/dev/null | wc -l)
        if ((base_file_count == 0)); then
            echo "warning: $base_ref/$MIGRATION_DIR extracted no .sql files; verifying the fresh-install path only" >&2
        fi
    else
        echo "warning: $base_ref has no $MIGRATION_DIR; verifying the fresh-install path only" >&2
    fi
fi

head_file_count=$(find "$MIGRATION_DIR" -type f -name '*.sql' | wc -l)
echo "base sql  : $base_file_count file(s)"
echo "head sql  : $head_file_count file(s)"
echo

# ---------------------------------------------------------------------------
# Database
# ---------------------------------------------------------------------------

echo "-- starting $POSTGRES_IMAGE"
docker run -d \
    --name "$PG_CONTAINER" \
    --label "$RUN_LABEL" \
    -e POSTGRES_DB="$DB_NAME" \
    -e POSTGRES_USER="$DB_USER" \
    -e POSTGRES_PASSWORD="$DB_PASSWORD" \
    "$POSTGRES_IMAGE" >/dev/null

# The official entrypoint starts a temporary server for initdb and restarts it,
# so a single pg_isready can succeed against a server that is about to go away.
# Require two consecutive successes, and leave Flyway's connectRetries as the
# second line of defence.
ready_streak=0
for _ in $(seq 1 60); do
    if docker exec "$PG_CONTAINER" pg_isready -q -U "$DB_USER" -d "$DB_NAME" >/dev/null 2>&1; then
        ready_streak=$((ready_streak + 1))
        if ((ready_streak >= 2)); then
            break
        fi
    else
        ready_streak=0
    fi
    sleep 1
done

if ((ready_streak < 2)); then
    echo "error: PostgreSQL did not become ready within 60s" >&2
    docker logs "$PG_CONTAINER" 2>&1 | tail -20 >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# Flyway
# ---------------------------------------------------------------------------

# The SQL goes in with docker cp rather than a bind mount. When the Jenkins agent
# is itself a container that only shares the docker socket, workspace paths do
# not exist on the daemon's side and a mount silently attaches an empty
# directory — which would make every check pass. docker cp holds wherever the
# daemon lives, the same reason docker build streams its context.
#
# --network container: puts Flyway in the database container's network
# namespace, so it reaches PostgreSQL on localhost with no port published and no
# network to create and tear down.
flyway_run() {
    local sql_dir=$1
    shift

    flyway_seq=$((flyway_seq + 1))
    FLYWAY_CONTAINER="artel-flyway-cli-$RUN_ID-$flyway_seq"

    docker create \
        --name "$FLYWAY_CONTAINER" \
        --label "$RUN_LABEL" \
        --network "container:$PG_CONTAINER" \
        "$FLYWAY_IMAGE" \
        -url="jdbc:postgresql://localhost:5432/$DB_NAME" \
        -user="$DB_USER" \
        -password="$DB_PASSWORD" \
        -locations=filesystem:/flyway/sql \
        -baselineOnMigrate=true \
        -outOfOrder=false \
        -connectRetries=10 \
        "$@" >/dev/null

    docker cp "$sql_dir/." "$FLYWAY_CONTAINER:/flyway/sql" >/dev/null

    local status=0
    docker start -a "$FLYWAY_CONTAINER" || status=$?

    docker rm -f "$FLYWAY_CONTAINER" >/dev/null 2>&1 || true
    FLYWAY_CONTAINER=''
    return $status
}

if ((base_file_count > 0)); then
    echo
    echo "-- phase 1: applying $BASE_BRANCH migrations"
    if ! flyway_run "$base_sql_dir" migrate; then
        echo
        echo "ERROR: the migrations on $BASE_BRANCH do not apply to an empty database."
        echo "This branch is not the cause. Fix $BASE_BRANCH first; see docs/flyway-migrations.md."
        exit 2
    fi
else
    echo
    echo "-- phase 1: skipped (no base migrations to apply)"
fi

echo
echo "-- phase 2: applying this branch's migrations on top"
if ! flyway_run "$MIGRATION_DIR" migrate; then
    echo
    echo "ERROR: this branch's migrations do not apply on top of $BASE_BRANCH."
    echo "A deployed container would die in the same place at startup. Either a statement failed,"
    echo "or migrate's built-in validation found an already-applied migration whose file changed."
    echo "See docs/flyway-migrations.md."
    exit 1
fi

echo
echo "-- phase 3: validate"
if ! flyway_run "$MIGRATION_DIR" validate; then
    echo
    echo "ERROR: flyway validate failed after the upgrade."
    echo "Usually an already-applied migration was edited: the file's checksum no longer matches"
    echo "the flyway_schema_history row. See docs/flyway-migrations.md."
    exit 1
fi

echo
echo "OK: base migrations applied, branch migrations applied on top, validate passed."
