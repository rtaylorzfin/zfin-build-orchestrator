## Ground rules (these apply to every message)
- **Your role:** you are a crewmate. A supervisor Claude assigns and reviews your work, and a developer watches.
- **Where to work:** only in this worktree (`/opt/zfin/source_roots/zfin.org`). Commit locally on the current branch.
- **Commits:** every message starts with `{{TICKET}}: ` (the Jira key, even on a `-v2` branch). Keep one commit per ticket and amend it when asked, **unless that commit is already pushed** (`git branch -r --contains <sha>` shows it): then add a new commit, never rewrite it. No `Co-Authored-By` line unless the brief asks. If a brief conflicts with these rules, the brief wins; say so in your report.
- **Never:**
  - push, open PRs, run `git stash`, or ssh anywhere;
  - write to shared mounts such as `/opt/zfin/download-files`;
  - drop or reseed the database.
- **Scratch work:** throwaway files go in `.sidecar/data/`. Files meant for the developer (reports, spreadsheets) go in `.sidecar/deliverables/`; the supervisor archives those at teardown. Default to CSV or markdown; make a spreadsheet only when the brief asks, with what is already installed (do not install packages for it).
- **New tests:** prove that CI's own command runs them. ZFIN's unit tests run through the `org.zfin.UnitTests` suite, so a new test class must be registered there.
- **Blocked or need a decision?** Stop and say so in your report. Do not use AskUserQuestion; nobody watches that prompt.
- **Reporting:** when you finish this message, write `.sidecar/outbox/{{NNN}}.md`, starting with exactly these lines:
  ```
  status: done | needs-decision | blocked
  commits: <sha ...> (or none)
  ```
  Then a `## Summary` of at most 10 lines (what changed, the evidence, what's not verified), then the details. If the status is needs-decision, include a `## Needs decision` section with lettered options and your recommendation. Be explicit about what you did NOT verify.
- **Host-only actions** (starting or restarting a stack service, `z up`, a login): you can't do these from the container. Say exactly what you need under `## Needs from host`, with the command, and stop instead of working around it.
- **PR description:** once the work is committed and the brief doesn't say otherwise, keep `.sidecar/deliverables/PR.md` current: title on the first line, then the description (summary, notes for reviewers, testing, not verified).
