#!/usr/bin/env bash
#
# Detects Flyway version collisions before they reach a running database.
#
# Flyway breaks in three ways, and only one of them is visible from the files in
# the working tree:
#
#   1. Collision  - two files claim the same version. Startup fails with
#                   "Found more than one migration with version N".
#   2. Tangle     - a new migration is numbered below one the target database has
#                   already applied. Startup fails with "Detected resolved
#                   migration not applied to database".
#   3. Tampering  - an already-merged migration is edited. Its checksum no longer
#                   matches the flyway_schema_history row and validate fails.
#
# Cases 2 and 3 cannot be caught by running the migrations against an empty
# database: on an empty database every ordering and every checksum succeeds. They
# are only visible by comparing this branch against the branch it will merge
# into, so this check works on git trees rather than on a database.
#
# Exit codes: 0 clean, 1 error, 2 warnings only.

set -euo pipefail

# Associative arrays need 4.0, and expanding an empty array under `set -u` is an
# error before 4.4 — which would surface as "unbound variable" on the clean path,
# the least useful place to be cryptic. Only RHEL/CentOS 7 still ships 4.2.
if ((BASH_VERSINFO[0] < 4 || (BASH_VERSINFO[0] == 4 && BASH_VERSINFO[1] < 4))); then
    echo "error: bash 4.4 or newer required (found $BASH_VERSION)" >&2
    exit 1
fi

MIGRATION_DIR="src/main/resources/db/migration"
DEPLOY_BRANCHES=" main operation develop stage "

# Base branch to compare against. Jenkins PR jobs expose the merge target as
# CHANGE_TARGET; branch jobs have no target, so fall back to develop.
BASE_BRANCH="${1:-${CHANGE_TARGET:-develop}}"

# Branch this checkout represents. In a Jenkins PR job BRANCH_NAME is "PR-<n>"
# and the real head branch is CHANGE_BRANCH, which is what we must exclude from
# the peer-branch scan.
CURRENT_BRANCH="${CHANGE_BRANCH:-$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo HEAD)}"

errors=()
warnings=()

fail() { errors+=("$1"); }
warn() { warnings+=("$1"); }

# ---------------------------------------------------------------------------
# Version handling
#
# Flyway accepts "." or "_" between numeric parts, so V1_1 and V1.1 are the same
# version. Normalise to "." so the two spellings cannot silently coexist.
# ---------------------------------------------------------------------------

version_of() {
    local name=${1##*/}
    name=${name#V}
    name=${name%%__*}
    printf '%s' "${name//_/.}"
}

# Zero-pads every numeric part so plain string comparison orders versions
# correctly: 26 -> "000026", 26.1 -> "000026000001", and "000026" sorts before
# "000026000001" exactly as 26 sorts before 26.1.
version_key() {
    local part key=''
    local IFS='.'
    for part in $1; do
        key+=$(printf '%06d' "$((10#$part))")
    done
    printf '%s' "$key"
}

# ---------------------------------------------------------------------------
# Working tree
# ---------------------------------------------------------------------------

if [[ ! -d $MIGRATION_DIR ]]; then
    echo "error: $MIGRATION_DIR does not exist. Run this from the repository root." >&2
    exit 1
fi

declare -A head_version_file=()   # version -> filename
declare -A head_file_blob=()      # filename -> blob hash
head_files=()

while IFS= read -r path; do
    head_files+=("$(basename "$path")")
done < <(find "$MIGRATION_DIR" -type f -name '*.sql' | sort)

echo "== Flyway migration check =="
echo "directory : $MIGRATION_DIR"
echo "branch    : $CURRENT_BRANCH"
echo "files     : ${#head_files[@]}"

# --- Check 1: naming convention ---------------------------------------------
#
# A versioned migration is V<version>__<description>.sql. A single underscore
# (V26_foo.sql) does not separate version from description, so Flyway either
# rejects the name or reads a version that was never intended.

versioned_files=()
for name in "${head_files[@]}"; do
    if [[ $name =~ ^R__[A-Za-z0-9_]+\.sql$ ]]; then
        continue    # repeatable migration: no version, nothing to collide
    fi
    if [[ ! $name =~ ^V[0-9]+([._][0-9]+)*__[A-Za-z0-9_]+\.sql$ ]]; then
        fail "invalid migration filename: $name (expected V<version>__<description>.sql with a double underscore)"
        continue
    fi
    versioned_files+=("$name")
done

# --- Check 2: duplicate versions in this tree -------------------------------

for name in "${versioned_files[@]}"; do
    version=$(version_of "$name")
    if [[ -n ${head_version_file[$version]:-} ]]; then
        fail "duplicate version V$version: ${head_version_file[$version]} and $name"
        continue
    fi
    head_version_file[$version]=$name
    head_file_blob[$name]=$(git hash-object "$MIGRATION_DIR/$name")
done

# ---------------------------------------------------------------------------
# Base comparison
# ---------------------------------------------------------------------------

base_ref=''

is_deploy_branch() {
    [[ $DEPLOY_BRANCHES == *" $1 "* ]]
}

if [[ -z ${CHANGE_TARGET:-} ]] && is_deploy_branch "$CURRENT_BRANCH"; then
    # Comparing a deploy branch against itself proves nothing. Checks 1 and 2
    # still ran, and on develop that is the check that stops an already-broken
    # branch from being deployed.
    echo "base      : (skipped, $CURRENT_BRANCH is a deploy branch)"
else
    # Fetch the base explicitly. A Jenkins multibranch checkout fetches narrowly,
    # so the workspace copy of origin/develop can lag behind by several merges —
    # and a stale base is exactly the case where a version that was just taken
    # still looks free.
    if git fetch --no-tags --quiet origin "+refs/heads/$BASE_BRANCH:refs/remotes/origin/$BASE_BRANCH" 2>/dev/null; then
        base_ref="refs/remotes/origin/$BASE_BRANCH"
    elif git rev-parse --verify --quiet "refs/remotes/origin/$BASE_BRANCH" >/dev/null; then
        base_ref="refs/remotes/origin/$BASE_BRANCH"
        echo "warning: could not fetch origin/$BASE_BRANCH; using the local copy, which may be stale" >&2
    elif git rev-parse --verify --quiet "refs/heads/$BASE_BRANCH" >/dev/null; then
        base_ref="refs/heads/$BASE_BRANCH"
        echo "warning: no remote $BASE_BRANCH; using the local branch, which may be stale" >&2
    else
        echo "warning: base branch '$BASE_BRANCH' not found; skipping base comparison" >&2
    fi
    echo "base      : ${base_ref:-<unavailable>}"
fi

declare -A base_version_file=()
declare -A base_file_blob=()
declare -A base_version_dup=()
base_max_key=''

if [[ -n $base_ref ]]; then
    # ls-tree on both sides rather than a three-dot diff: a shallow Jenkins
    # checkout may have no merge base, and migrations are immutable anyway, so a
    # direct tree-to-tree comparison is the right question.
    while read -r _mode _type blob path; do
        name=$(basename "$path")
        [[ $name == *.sql ]] || continue
        [[ $name == R__* ]] && continue
        base_file_blob[$name]=$blob
        version=$(version_of "$name")
        if [[ -n ${base_version_file[$version]:-} ]]; then
            base_version_dup[$version]="${base_version_file[$version]}, $name"
        fi
        base_version_file[$version]=$name
        key=$(version_key "$version")
        if [[ -z $base_max_key || $key > $base_max_key ]]; then
            base_max_key=$key
            base_max_version=$version
        fi
    done < <(git ls-tree -r "$base_ref" -- "$MIGRATION_DIR")

    # --- Checks 3 and 4: versions and files that base already owns -----------
    for version in "${!base_version_file[@]}"; do
        # A version that is already duplicated on base is base's defect, not this
        # branch's. Reporting it as reuse or as a removal would fail the very
        # branch that renumbers it — which is the only way to fix it.
        if [[ -n ${base_version_dup[$version]:-} ]]; then
            warn "$BASE_BRANCH itself has duplicate version V$version (${base_version_dup[$version]}); comparison for V$version skipped"
            continue
        fi

        base_name=${base_version_file[$version]}
        head_name=${head_version_file[$version]:-}

        if [[ -z $head_name ]]; then
            # Check 5: base has it, this branch does not. Any database already
            # migrated from base has the row; removing the file makes validate
            # fail.
            fail "migration removed: $base_name exists on $BASE_BRANCH but not on this branch"
            continue
        fi

        if [[ $head_name != "$base_name" ]]; then
            fail "version V$version already used on $BASE_BRANCH by $base_name; this branch reuses it for $head_name"
            continue
        fi

        if [[ ${head_file_blob[$head_name]} != "${base_file_blob[$base_name]}" ]]; then
            fail "already-merged migration modified: $head_name differs from $BASE_BRANCH (checksum change breaks validate on every migrated database)"
        fi
    done

    # --- Check 6: new versions must sort above everything base has -----------
    for version in "${!head_version_file[@]}"; do
        [[ -n ${base_version_file[$version]:-} ]] && continue
        key=$(version_key "$version")
        if [[ -n $base_max_key && ! $key > $base_max_key ]]; then
            fail "out-of-order version V$version (${head_version_file[$version]}): $BASE_BRANCH is already at V${base_max_version}, so a migrated database would never apply it"
        fi
    done
fi

# ---------------------------------------------------------------------------
# Check 7: versions claimed by other unmerged branches
#
# This is the only check that fires before either side merges. It warns instead
# of failing because the other branch may never land — but once one of the two
# does land, the loser hits check 3 and the build stops for real.
# ---------------------------------------------------------------------------

if [[ -n $base_ref && ${FLYWAY_CHECK_PEERS:-1} != 0 ]]; then
    new_versions=()
    for version in "${!head_version_file[@]}"; do
        [[ -n ${base_version_file[$version]:-} ]] || new_versions+=("$version")
    done

    if ((${#new_versions[@]} > 0)); then
        if git fetch --no-tags --quiet origin '+refs/heads/*:refs/remotes/origin/*' 2>/dev/null; then
            while IFS= read -r peer; do
                short=${peer#refs/remotes/origin/}
                [[ $short == HEAD || $short == "$BASE_BRANCH" || $short == "$CURRENT_BRANCH" ]] && continue

                declare -A peer_versions=()
                while IFS= read -r path; do
                    name=$(basename "$path")
                    [[ $name == *.sql && $name != R__* ]] || continue
                    peer_versions[$(version_of "$name")]=$name
                done < <(git ls-tree -r --name-only "$peer" -- "$MIGRATION_DIR" 2>/dev/null)

                for version in "${new_versions[@]}"; do
                    peer_name=${peer_versions[$version]:-}
                    # Only a version the peer *adds* is a real claim; one it
                    # merely inherited from base was already handled by check 3.
                    [[ -n $peer_name && -z ${base_version_file[$version]:-} ]] || continue
                    [[ $peer_name == "${head_version_file[$version]}" ]] && continue
                    warn "version V$version is also claimed by origin/$short as $peer_name (this branch: ${head_version_file[$version]}); whichever merges second will break"
                done
                unset peer_versions
            done < <(git for-each-ref --format='%(refname)' refs/remotes/origin)
        else
            echo "note: could not fetch other branches; skipping the peer-branch scan" >&2
        fi
    fi
fi

# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------

echo

for message in "${warnings[@]}"; do
    echo "WARNING: $message"
done

for message in "${errors[@]}"; do
    echo "ERROR: $message"
done

if ((${#errors[@]} > 0)); then
    echo
    echo "${#errors[@]} error(s). Renumber the new migration above the highest version on $BASE_BRANCH,"
    echo "and restore any already-merged file to its merged content. See docs/flyway-migrations.md."
    exit 1
fi

if ((${#warnings[@]} > 0)); then
    echo
    echo "${#warnings[@]} warning(s). Nothing is broken yet, but coordinate the version number"
    echo "before the other branch merges. See docs/flyway-migrations.md."
    exit 2
fi

echo "OK: no version collisions."
