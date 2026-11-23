# Sidecar fleet: one Claude per ticket, supervised

Work several ZFIN tickets at once. Each ticket gets:
- its own feature stack (`z feature new`);
- its own [herdr](https://github.com/herdrdev/herdr) workspace;
- a **crewmate**: a Claude running in that stack's sidecar container (`z run claude`).

A **supervisor** (captain) Claude, running on the host, briefs the crewmates, rules on their questions, reviews their work and hands finished branches to you. You watch everything live in herdr and make the calls that are yours: provisioning, product and data decisions, push, PR, teardown.

The supervisor's playbook is the `zfin-fleet` skill in this repo (`.claude/skills/zfin-fleet/`), with its helper `bin/fleet`. This page is for the developer: setup, what to expect, and how to step in.

> First used for real on 2026-11-20: ZFIN-10603 and ZFIN-8072 in parallel, both ready to push in about 1.5h. See [Why it works this way](#why-it-works-this-way).

## Setup (once per machine)

1. **herdr** from its GitHub release binary (not `curl | sh`). The fleet uses your **default** herdr session.
2. **Claude token for the sidecars:** on the host run `claude setup-token`, then save the token to `~/.zfin/claude-token` (mode 600), or point `ZFIN_CLAUDE_TOKEN_FILE` at it.
3. **Sidecar image** `zfin-claude:<release><arch>`. It is built the first time you run `z run claude`. After pulling changes to `compose/claude/`, rebuild it:
   ```bash
   docker build --build-arg ZFIN_RELEASE=main --build-arg DOCKER_ARCH=-arm64 \
     -t zfin-claude:main-arm64 compose/claude
   ```
4. **Host tools the supervisor uses:** `acli` (Jira) and `gh`. The sidecar container has neither. The supervisor fetches that data and puts it in the stack's `.sidecar/data/`.

## Starting a session

Start herdr, open a pane **in this checkout** (`stacks/orchestrator`), and run `claude` there. Claude Code finds project skills from its starting directory, so `zfin-fleet` loads only when the supervisor starts here. Then ask in plain words, for example:

> Work ZFIN-1234 and ZFIN-5678 in parallel: one stack each, with crewmates.

The supervisor asks once before provisioning the batch, then creates one herdr workspace per ticket, named after the stack (e.g. `zfin-1234`):

| Pane | Runs |
|---|---|
| 1 | `z feature new` (provisioning), then free for the supervisor's visible commands |
| 2 | the crewmate `crew-1234` |

herdr's sidebar shows each crewmate as working, blocked, idle or done. You can type into a crewmate's pane at any time. Tell the supervisor afterwards so it can note it in `state.md`.

To resume the supervisor after a restart, run `claude --continue` in the same directory. Its per-ticket state lives in files (below), so a resumed or summarized supervisor rereads it rather than relying on memory.

## The lifecycle of one ticket

```
provisioning -> briefed -> plan-review -> implementing -> my-review -> ready-to-push
   -> (you push + open the PR) -> teardown
```

1. **Intake:** the Jira ticket with its comments, linked tickets and prior commits, written to `.sidecar/data/ticket.md`.
2. **Plan:** the crewmate's first brief always asks for a plan only, not code. On both tickets of the first run, the plan surfaced decisions before any code changed.
3. **Rule:** the supervisor answers technical questions itself. Questions that are yours come to you with a recommendation, prefixed with the ticket, e.g. **[ZFIN-1234]**. If it doesn't need you right away, the supervisor picks the conservative option and records it under `open decisions` in `state.md`.
4. **Implement**, then **review**. The supervisor reads the whole diff and asks the crewmate for evidence rather than guessing. That includes an anomaly sweep over the whole affected population for data-transforming changes, and proof that CI's own command runs any new test.
5. **Hand-off:** the branch and SHA, what was verified and what wasn't, and a draft PR description. **You push and open the PR.** Neither Claude ever pushes.
6. **Teardown,** when you say so. The supervisor runs read-only checks: is the branch on a remote with nothing unpushed, is the worktree clean, which deliverables exist. It shows you the result and asks once. Then it:
   - copies `.sidecar/deliverables/` to `archive/reports/<ticket>/`;
   - exits the crewmate;
   - runs `z feature rm`;
   - closes the workspace.

## Files: the `.sidecar/` mailbox

Each worktree gets a `.sidecar/` directory. It ignores itself (it holds a `.gitignore` of `*`) and is deleted with the worktree.

```
.sidecar/
  state.md          # supervisor-owned source of truth: stage, waiting-on, crewmate, open decisions
  inbox/NNN.md      # supervisor -> crewmate briefs, numbered, never edited after sending
  outbox/NNN.md     # crewmate -> supervisor reports, same number as the brief they answer
  data/             # inputs and scratch files (ticket text, query results); lost at teardown
  deliverables/     # reports and spreadsheets meant for you; archived at teardown
```

Every report starts with a header that the supervisor acts on. herdr's `idle` can't tell "done" from "done, with a question":

```
status: done | needs-decision | blocked
commits: <sha ...> | none
```

Read these files any time. `fleet status` prints one line per ticket, and `fleet state <ticket>` prints its `state.md`. The helper lives at `.claude/skills/zfin-fleet/bin/fleet`.

## Who may do what

| | Crewmate | Supervisor | Only you, or after your yes |
|---|---|---|---|
| Edit, build, test, commit locally in its own worktree and stack | yes | | |
| Mailbox, briefs, waits, `state.md`, launching crewmates, smoke checks | | yes | |
| Provision a batch of stacks; image rebuilds | | asks once | yes |
| Product or curation decisions; anything that changes external output | asks | recommends | yes |
| Jira comments or transitions | | drafts | yes |
| Writes to shared mounts (`stacks/mounts/*`) | never | asks | yes |
| Teardown (`z feature rm`) | | asks after read-only checks | yes |
| Push, open PRs | never (structurally: no keys, no agent socket) | never | **you** |
| `git stash`, force-push, ssh to remote VMs | never | never | |

Crewmates don't add `Co-Authored-By` lines unless their brief asks for one.

## What makes the crewmate's container safe to run unattended

The sidecar runs `claude --dangerously-skip-permissions`. That is safe only because of what the container lacks; see `compose/claude/Dockerfile`:
- no docker socket and no ssh agent socket or keys, so it can't push, ssh, or drive other stacks;
- only its own worktree is mounted;
- a `PreToolUse` hook blocks `git push` anyway.

For the same reason the sidecar's profile pre-accepts Claude's folder-trust prompt and bypass-mode warning, so a crewmate starts without keystrokes. If you add a mount or a credential to the sidecar, revisit that.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| A crewmate's npm fails with `EALLOWSCRIPTS` | The image predates orchestrator 329d933. Rebuild it (Setup step 3). |
| A crewmate stops at "trust this folder" or "Yes, I accept" | The image predates the pre-accept change. Rebuild it; meanwhile `fleet crew` answers both prompts itself. |
| Two stacks provisioned together collide on a port | Fixed in 08d4798 (atomic offset claim). If it still happens, `z feature new` dies with a duplicate-offset message: rerun it. |
| `z exec db psql` breaks | Use `docker exec -i <stack>-db-1 psql -U postgres -d zfindb`. |
| npm can't resolve the newest version of a package | Expected: the project `.npmrc` has `min-release-age=7`. Don't bypass it. |
| The supervisor forgot a decision after a long session | Its memory was summarized. `state.md` is the record: ask it to reread `fleet state <ticket>`. |

## Why it works this way

**Lessons from ZFIN-10564 (one crewmate, tmux, 2026-11-18):**
- **Screen-scraping is unreliable.** Claude Code's greyed-out suggestion in the input box looked like typed text, and a grep for a "done" marker matched the command line itself. So messages travel as files, and busy/idle comes from herdr's agent state.
- **Plan first, and the supervisor reviews every diff.** Review caught a window over 1.3M rows, a stale flag and an over-broad `sed`.
- **Shared resources need rules.** The downloads mount is visible to every stack.

**The herdr test (2026-11-18):**
- herdr detects a `claude` running inside the container as a Claude agent.
- It tracks working, blocked and idle correctly, including during long tool calls and on AskUserQuestion.
- Its CLI can send a prompt and wait for completion. It replaced the planned tmux helpers and the busy/idle hooks.

**The first real parallel run (2026-11-20).** Two crewmates were tracked independently, and while one worked the other was briefed or reviewed. What slowed things down, and how each was resolved:

| Friction | Resolution |
|---|---|
| Concurrent `z feature new` picked the same port offset | Fixed: atomic claim in `worktrees/.port-claims/` (08d4798) |
| `ENV NPM_CONFIG_ALLOW_SCRIPTS` broke npm 12 in the sidecar | Fixed: moved to a global npmrc (329d933) |
| Crewmate startup needed four timed keystrokes | Fixed: the sidecar profile pre-accepts both prompts |
| Hand-built mailbox, numbering, rules and `state.md` edits | `fleet send` / `state` plus brief templates |
| The supervisor blocked on waits; one wait returned before the report existed | `fleet wait` in the background, finishing only when the outbox file exists **and** the crewmate is idle |
| Ticket intake by one-off scripts | `fleet intake` |
| Teardown took 4+ manual steps and risked losing reports | `fleet check` plus `fleet down --yes` with deliverables archived |
| Gaps the developer caught: a new test outside the CI suite; junk source data in a report | Review checklist items, and crewmate rules |
| Crewmates added `Co-Authored-By` lines | Rule: only when the brief asks |
| Report tooling (openpyxl only in system Python, no LibreOffice) | Not worth adding to this repo. Reports default to CSV or markdown; a spreadsheet only when asked, with what's already installed. |

## Not done yet, maybe later
- A watcher that wakes the supervisor on any crewmate state change, instead of per-ticket background waits.
- A cap on concurrent crewmates. Three is the working guess for a laptop; read-only tickets can use `--shared-db`.
- `z feature freeze` / thaw for tickets parked in PR review.
- Moving `fleet` into `z`, and a `z` command to install the skill for sessions started elsewhere.

## References
- [herdr](https://github.com/herdrdev/herdr)
- [firstmate](https://github.com/kunchenguid/firstmate): the source of the plan-first and scout/ship ideas
- ZFIN-10564 sidecar transcript: `archive/sessions/zfin-10564-20261118-132908.tgz`
