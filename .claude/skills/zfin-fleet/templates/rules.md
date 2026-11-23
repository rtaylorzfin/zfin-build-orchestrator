## Ground rules (these apply to every message)
- **Your role:** you are a crewmate. A supervisor Claude assigns and reviews your work, and a developer watches.
- **Where to work:** only in this worktree (`/opt/zfin/source_roots/zfin.org`). Commit locally on the current branch, with messages prefixed `{{TICKET}}: `. Keep one commit per ticket; amend it if asked.
- **Commit attribution:** do not add a `Co-Authored-By` line unless this message asks for one.
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
  Then give a concise report. If the status is needs-decision, include a `## Needs decision` section with lettered options and your recommendation. Be explicit about what you did NOT verify.
