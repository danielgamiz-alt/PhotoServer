---
name: git-workflow
description: >-
  Git and GitHub conventions for the PhotoServer repo on this Windows machine.
  Use this whenever committing, pushing, opening or updating a pull request, or
  running any `gh` command here — it records the non-obvious local setup (where
  the GitHub CLI actually lives, the base branch, the required commit/PR
  trailers, and which git warnings to ignore) so you don't have to rediscover
  them every time. Consult it before reaching for `gh` or crafting a commit.
---

# Git & GitHub workflow (PhotoServer)

This repo is on a Windows machine with a couple of local quirks. Following the
notes below avoids the usual dead ends (a "gh: command not found", a PR opened
against the wrong base, or chasing harmless line-ending warnings).

## The GitHub CLI is installed but NOT on PATH

`gh` works, but a bare `gh ...` fails with "command not found" because the
install dir isn't on PATH. Always call it by full path:

- **Bash tool:** `"/c/Program Files/GitHub CLI/gh.exe"`
- **PowerShell:** `& "C:\Program Files\GitHub CLI\gh.exe"`

The user is authenticated as `danielgamiz-alt`, so no `gh auth login` is needed.
(If you ever want a bare `gh` to work, the fix is adding `C:\Program Files\GitHub
CLI` to PATH — but don't do that unless asked; the full path is reliable.)

## Repo facts

- Remote repo: `danielgamiz-alt/PhotoServer`
- **Base branch for PRs is `master`** (not `main`). Pass `--base master` explicitly.
- Feature work happens on branches; push the current branch and PR it into `master`.

## Default workflow — branch + PR (do this automatically)

The user wants changes to land via pull requests **by default, without being asked
each time**. So when a task involves changing files:

1. **Before starting non-trivial work, create a branch** (`git checkout -b <type>/<short-name>`,
   e.g. `feat/...`, `fix/...`, `docs/...`). Don't commit straight onto `master`.
2. Commit there, push the branch, and **open the PR yourself** using the flow
   below — then report the PR URL. This is expected behaviour, not something to
   ask permission for.

**When to PR vs. commit directly** (the agreed threshold):

| Change | What to do |
|---|---|
| Code (server / desktop / android), multi-file edits, anything substantive, or site/docs that go live on merge | **Branch + PR.** |
| A trivial one-off — typo, version bump, a single-line doc tweak | A direct commit to `master` is fine (no branch/PR needed). |

When unsure, branch + PR — it's cheap and reversible. **Always branch *first*** for
non-trivial work: committing onto `master` and then trying to carve out a PR
afterwards forces a `master` force-push (a rewind of the published default branch),
which needs explicit user approval and is disruptive. Branching up front avoids all
of that.

## Commit messages

End every commit message with this trailer (own line, blank line before it):

```
Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
```

To write a multi-line message safely in the Bash tool, pipe a heredoc into
`git commit -F -` rather than stacking `-m` flags:

```bash
git commit -q -F - <<'EOF'
type: short summary

Why this change / what it does.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
```

## Pull requests

End PR bodies with:

```
🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

Create-or-update flow (don't open a duplicate):

```bash
GH="/c/Program Files/GitHub CLI/gh.exe"
# 1. Is there already an open PR for this branch?
"$GH" pr list --head <branch> --state open --json url,number
# 2a. If yes: push the new commits — the existing PR updates automatically.
git push origin <branch>
# 2b. If no: push, then create it against master.
git push origin <branch>
"$GH" pr create --base master --head <branch> --title "..." --body "$(cat <<'EOF'
... body ...

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Report the resulting PR URL when done.

## Harmless warnings to ignore

`git add`/`commit` prints `warning: in the working copy of '<file>', LF will be
replaced by CRLF the next time Git touches it` for many files. This is just the
line-ending normalization on Windows — it is expected here and not an error.
Don't try to "fix" it or treat it as a failed command.

## Before committing app changes

The desktop/server code has fast headless test suites — run the relevant ones
and report results rather than assuming green:

```bash
cd desktop && node test/control-test.js && node test/gallery-test.js
cd server && node test/smoke-test.js
```

Android builds do **not** work from this harness — they must be built in Android
Studio. Don't attempt `gradlew` here.
