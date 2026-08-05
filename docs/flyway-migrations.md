# Flyway Migration Version Check

Migrations live in `src/main/resources/db/migration/` and are named
`V<version>__<description>.sql`. The version number is global and immutable:
two branches that pick the same number produce a repository that no database can
migrate, and the failure surfaces only when a deployed container refuses to
start.

That has happened repeatedly — `Flyway V20 충돌 해소 (ARTEL-216)`,
`Flyway 번호 충돌 해소 (ARTEL-233)`, and ARTEL-245/ARTEL-255 both taking `V26`,
which ARTEL-260 had to unpick. `scripts/check-flyway-migrations.sh` moves that
discovery to CI.

## Running it

```bash
./scripts/check-flyway-migrations.sh
```

Compares against `develop` by default. Pass a branch name to compare against
something else, and note that the script fetches that branch itself — a stale
local copy is exactly the case where a number that was just taken still looks
free.

Exit codes: `0` clean, `1` error, `2` warnings only.

## Why this is a git check and not a test

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

## What it checks

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
5. **No removed migration** — a file the base has and this branch does not.
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

## Fixing a failure

**Reused, out-of-order, or peer-claimed version.** Renumber your own migration
above the highest version on `develop`; never renumber the one that is already
merged. Rename the file and update any reference to it. Because the number is
only a name, nothing else changes.

**Modified or removed migration.** Restore the file to its merged content and
express the change as a new migration. A database that already applied the
original recorded its checksum; editing the file makes `validate` fail on every
such database, and deleting it fails the same way.

The exception is a migration that is broken on `develop` and has never started
successfully anywhere — the ARTEL-260 case. No database holds its checksum, so
renumbering it in place is safe. Confirm that the stage server never came up on
it before assuming this.

## CI

`Jenkinsfile` runs the check in the `Flyway Migration Check` stage, ahead of
`Build` and `Deploy Pipeline`, on every branch and PR. Exit code `1` fails the
build; exit code `2` marks it unstable.
