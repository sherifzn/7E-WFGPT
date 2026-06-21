## OpenCode handoff

Before making changes, read:

`docs/OPENCODE_HANDOFF.md`

Then inspect:

```bash
git status --short
git log --oneline --decorate -10
git diff
git diff --cached
```

Do not reset, clean, stash, discard, push, merge, or rewrite history unless explicitly instructed.

Use small implementation slices. Do not combine domain, persistence, parent integration, API, and frontend into one task.
