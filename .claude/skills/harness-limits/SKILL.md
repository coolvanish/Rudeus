---
name: harness-limits
description: What the Rudeus hooks and the auto-mode classifier block or ask about, and the workaround for each - secret-word Bash guard, hand-review zone asks, runs/*.json deny, module-boundary deny, the Stop-hook verify nag and --mark, refused git/worktree operations, settings.json self-edit. Load when a tool call is denied, when a command "mysteriously" fails, or before handing housekeeping to the user.
---

# Harness limits (hooks in `.claude/hooks/`, config in `.claude/settings.json`)

The harness enforces CLAUDE.md so agents do not have to remember it. When a call is denied, the fix is in this table;
never retry the same call, never try to bypass a hook, never edit the hooks or settings yourself.

## Bash guard (`guard_bash.py`, PreToolUse)
| Denied when the command text… | Do instead |
|---|---|
| mentions `local.properties` (unless it starts with `ls`/`test`/`stat`) | never read it; `scripts/probe-meta.sh` reads the key itself. Docs or scripts that must contain that word: use the Edit/Write tool, not sed or a heredoc |
| reads a dotenv file (`cat/sed/grep … .env`) | same: the file is the user's |
| prints `META_API_KEY` (`echo/printenv/env`) | nothing prints it; `BuildConfig.META_API_KEY` is the only consumer |
| `git push --force` / `git reset --hard` | list the command for the user |
| `git commit` with a staged diff that looks like a key (`META_API_KEY=…`, `sk-…`, `Bearer …`) or stages `local.properties` | unstage, redact (`docs/api-samples/` must be redacted), commit again |

Also: a plugin hook from `one-shot-prompting` (`block-bad-commands.sh`) pattern-matches words like `secret` and
`credentials` in any Bash command, even in a commit message. Keep those words out of command text; put them in files
via Edit/Write.

## Edit/Write guard (`guard_write.py`, PreToolUse)
| Path or content | Result | Meaning |
|---|---|---|
| `androidApp/src/main/**/*.kt|xml`, `*.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties` | **ask** | hand-review zone; the user approves each write. Keep the diff minimal, add `// HAND-REVIEW:` (load `hand-review`) |
| `runs/*.json` | deny | produced by MetricsLog only; README numbers must match real runs |
| `core/src/**` with ARCore/Filament/SceneView types, or `android.`/`androidx.`/Compose imports | deny | boundary violation: the code belongs in `:shared` or `:androidApp` |
| `shared/src/**` with ARCore/SceneView types or network calls | deny | same; the AR view is a slot from `:androidApp` |
| content that looks like a key | deny | redact |

## Stop hook (`stop_verify.py`)
`scripts/verify.sh` writes `.gradle/.dirty-since-verify` on source changes and clears it only on success. When you
stop with it present, the hook sends you back **once** with the file list. Options, in order of cost:
- `scripts/verify.sh` (the DoD; run in the background, 2–4 min warm);
- core-only change: `./gradlew :core:jvmTest` then `scripts/verify.sh --mark` (clears the stamp on your word; only
  when nothing outside `core/` changed);
- `scripts/verify.sh --quick` runs core tests without clearing the stamp (for iteration, not for stopping).
If verify cannot run (no SDK, Gradle down), say so explicitly in the final message; the hook never blocks twice.

## Auto-mode classifier refusals (not hooks; the app decides)
These were refused in past sessions and will be again. Do them yourself only if the user asks in chat; otherwise list
the exact commands in the human checklist (§5 Housekeeping):
- writing `.claude/settings.json` (self-modification) -> save a copy under `docs/claude-settings.json` and ask the user
  to copy it; check at session start that the file exists;
- `git worktree remove <path>`, `git branch -D/-d task/...` -> merged worktrees pile up under `.claude/worktrees/`
  (T04, T17–T24, two `agent-*` ones today); harmless, list them;
- deleting a directory (the iOS template went to `attic/` instead);
- entering a PIN or tapping a permission dialog on the device (load `device-evidence`).

## Session start checklist (30 seconds)
```
test -f .claude/settings.json || echo "copy docs/claude-settings.json -> .claude/settings.json"
git status --short | head; git branch --show-current
ls .gradle/.dirty-since-verify 2>/dev/null && echo "verify pending"
```
