# Flyway Migrations in CI

Two checks guard the migrations, and they catch different things.

| | Sees | Cost |
|---|---|---|
| `scripts/check-flyway-migrations.sh` | file names, version numbers, and numbers claimed by other unmerged branches | seconds, git only |
| `scripts/verify-flyway-upgrade.sh` | the SQL itself, executed along the upgrade path a deployment takes | ~30s, needs Docker |

Neither replaces the other. The static check is the only one that can see a
branch that has not merged yet. The upgrade check is the only one that can see
whether the SQL runs at all.

## Version check

Migrations live in `src/main/resources/db/migration/` and are named
`V<version>__<description>.sql`. The version number is global and immutable:
two branches that pick the same number produce a repository that no database can
migrate, and the failure surfaces only when a deployed container refuses to
start.

That has happened repeatedly — `Flyway V20 충돌 해소 (ARTEL-216)`,
`Flyway 번호 충돌 해소 (ARTEL-233)`, and ARTEL-245/ARTEL-255 both taking `V26`,
which ARTEL-260 had to unpick. `scripts/check-flyway-migrations.sh` moves that
discovery to CI.

### Running it

```bash
./scripts/check-flyway-migrations.sh
```

Compares against `develop` by default. Pass a branch name to compare against
something else, and note that the script fetches that branch itself — a stale
local copy is exactly the case where a number that was just taken still looks
free.

Exit codes: `0` clean, `1` error, `2` warnings only.

### Why this is a git check and not a test

Flyway breaks in three ways, and only one of them is visible from the files in
one working tree.

| | Symptom at startup | Visible from |
|---|---|---|
| **Collision** — two files claim one version | `Found more than one migration with version N` | the working tree alone |
| **Tangle** — new version numbered below one the database already applied | `Detected resolved migration not applied to database` | comparison with the base branch |
| **Tampering** — an already-merged migration edited | checksum mismatch, `validate` fails | comparison with the base branch |

The last two cannot be caught by running the migrations against an empty
database: on an empty database every ordering and every checksum succeeds. They
exist only relative to a database that already ran the base branch's migrations,
and the branch this one merges into is the closest available stand-in. Hence a
comparison of git trees rather than a test.

`scripts/verify-flyway-upgrade.sh` builds that database instead of inferring it,
so it sees the same two failures by execution. The git comparison is still what
catches a version another *unmerged* branch has claimed — that branch is not in
any database yet, and nothing an execution does can reveal it.

### What it checks

Always, with no base branch needed:

1. **Filename** matches `V<version>__<description>.sql`. A single underscore
   does not separate version from description, so Flyway reads a version that
   was never intended. `R__` repeatable migrations are allowed and skipped.
2. **No duplicate version** within the working tree.

Against the base branch (a PR job's `CHANGE_TARGET`, otherwise `develop`):

3. **No reused version** — a number the base already owns under a different
   filename.
4. **No modified migration** — a file the base already has, with different
   content.
5. **No removed migration** — a file the base has and this branch does not. A
   branch that has simply fallen behind trips this without deleting anything;
   see *Fixing a failure*.
6. **No out-of-order version** — every new number sorts above the base's highest.

Against other remote branches:

7. **No version claimed elsewhere** — a number this branch adds that an unmerged
   `origin/*` branch also adds under a different filename. This is the only
   check that fires before either side merges, so it is the one that would have
   caught ARTEL-245 against ARTEL-255. It warns rather than fails because the
   other branch may never land; once one of the two does land, the other hits
   check 3 and the build stops for real.

   Set `FLYWAY_CHECK_PEERS=0` to skip it.

Deploy branches (`main`, `operation`, `develop`, `stage`) run only checks 1 and
2 — comparing a branch against itself proves nothing. On `develop` that is still
the check that stops an already-broken branch from reaching the stage server.

If the base branch itself carries a duplicate version, the script reports it as
a warning and skips checks 3–5 for that number. Otherwise the branch that
renumbers the duplicate — the only branch that can fix it — would fail for
removing a migration.

### Fixing a failure

**Reused, out-of-order, or peer-claimed version.** Renumber your own migration
above the highest version on `develop`; never renumber the one that is already
merged. Rename the file and update any reference to it. Because the number is
only a name, nothing else changes.

**Removed because the branch is behind.** Check 5 cannot tell a deleted file
from one that never arrived. A migration merged into the base after this branch
was cut is missing here for the second reason, and the fix is to catch up with
the base — merge it in, or rebase onto it while the branch is yours alone — not
to restore anything. ARTEL-388 hit this with a branch that changed one
configuration value and two documents; it never touched SQL. Rule out staleness
before reading the paragraph below, because the two failures print the same
line. `.agents/docs/workflow.md` carries the habit that avoids it.

**Modified or removed migration.** Restore the file to its merged content and
express the change as a new migration. A database that already applied the
original recorded its checksum; editing the file makes `validate` fail on every
such database, and deleting it fails the same way.

The exception is a migration that is broken on `develop` and has never started
successfully anywhere — the ARTEL-260 case. No database holds its checksum, so
renumbering it in place is safe. Confirm that the stage server never came up on
it before assuming this.

## Upgrade verification

The version check reads file names. A migration whose name is correct but whose
SQL is malformed, or which references a column that does not exist, passes it
untouched — and `Build` runs `-DskipTests`, so nothing else in the pipeline
executes the SQL either. The first thing that does is the deployed container,
which dies in the Flyway step.

`scripts/verify-flyway-upgrade.sh` executes it in CI instead.

### Running it

```bash
./scripts/verify-flyway-upgrade.sh          # against develop
./scripts/verify-flyway-upgrade.sh stage    # against another base
```

Needs a working Docker. Takes about 30 seconds once the images are cached.

Exit codes: `0` clean, `1` this branch breaks the upgrade, `2` the base branch is
already broken.

### What it does

1. Starts `pgvector/pgvector:pg16` — not stock `postgres`, because
   `V18__create_knowledge_embedding.sql` runs `CREATE EXTENSION vector` and the
   whole chain fails from there on an image without it. `PostgresTestContainer`
   picks the same image for the same reason.
2. **Phase 1** — applies the *base branch's* migrations, read straight out of the
   git tree with `git archive`. This is the state a deployed database is in.
3. **Phase 2** — applies *this branch's* migrations on top. Only the new ones
   run; the rest are already in `flyway_schema_history`.
4. **Phase 3** — `flyway validate`.

Applying everything at once would be a different question. That is the
fresh-install path, and on an empty database every ordering and every checksum
succeeds — which is exactly why the two phases are separate.

The Flyway image is pinned to the version Spring Boot's BOM manages, so CI
validates with the engine the deployed container runs. Bump both together.

On a deploy branch (`main`, `operation`, `develop`, `stage`) there is no base to
upgrade from, so phase 1 is skipped and the run becomes a fresh install. That is
still the only place the merged tree's SQL is executed before the stage server
tries it.

Containers are labelled and removed by a `trap`, including on `SIGTERM` from an
aborted build. The `post` block in `Jenkinsfile` sweeps this build's label as a
backstop.

### Fixing a failure

**Exit 1.** Read Flyway's own error; it names the file, the line, and the
statement. A checksum mismatch here means an already-merged migration was
edited — restore it and express the change as a new migration.

**Exit 2.** The base branch is broken and this branch did not cause it. Fix the
base first. The build is marked unstable rather than failed for that reason.

## CI

`Jenkinsfile` runs both, ahead of `Build` and `Deploy Pipeline`, on every branch
and PR:

| Stage | Script | `1` | `2` |
|---|---|---|---|
| `Flyway Migration Check` | `check-flyway-migrations.sh` | fail | unstable (another branch claims the number) |
| `Flyway Upgrade Verify` | `verify-flyway-upgrade.sh` | fail | unstable (the base branch is broken) |

Both put the failure ahead of the deploy, so an already-broken `develop` does not
reach the stage server.
