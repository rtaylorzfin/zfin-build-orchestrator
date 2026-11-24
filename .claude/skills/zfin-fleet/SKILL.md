---
name: zfin-fleet
description: Supervise Claude "crewmates" working ZFIN Jira tickets in parallel, one feature stack each, hosted in herdr. Use when the developer asks to work one or more ZFIN tickets via sidecars/crewmates/stacks ("spin up a stack for ZFIN-1234 and delegate it", "work these tickets in parallel"), or to check on, review, or tear down such work.
---

# ZFIN fleet: supervising crewmates on feature stacks

You are the **supervisor**. Each ticket gets its own feature stack (`z feature new`), its own herdr workspace, and a **crewmate**: a Claude running in that stack's sidecar container. You brief the crewmates, rule on their questions, review their work, and hand finished branches to the developer. The developer watches everything in herdr. The developer-facing runbook, including the reasons behind these rules, is `docs/sidecar-fleet.md`.

Helper: `F=<this skill dir>/bin/fleet`. Run `$F` with no arguments for its usage. It does the mechanical steps; the judgment calls stay with you.

## Preconditions
- `HERDR_ENV=1`: you are running inside herdr. If not, stop and tell the developer.
- You were started in `stacks/orchestrator`, which is why this skill loaded. Keep that as the working directory: `fleet` resolves the stacks root from `ZFIN_DEV_ROOT`, not from it.
- Run `herdr` commands against the developer's **default** session. Never stop the default herdr server.
- `acli` (Jira) and `gh` work on the host. The container has neither, so gather that data yourself and put it in `.sidecar/data/`.

## Lifecycle (one ticket)

```
provisioning -> briefed -> plan-review -> implementing -> my-review -> ready-to-push
   -> (developer pushes + opens PR) -> teardown
```

1. **Intake.** `acli jira workitem view <KEY>`. Read the comments: they often supersede the description. Check linked tickets and prior-art commits (`git log --grep`).
   - **Rework of a merged ticket:** if the ticket's branch is already merged into main and the ticket got requested changes, work on a new branch `<ticket>-v2` (then `-v3`, ...) cut from main: `$F up zfin-1111-v2`. The Jira key and commit prefix stay `ZFIN-1111`; `fleet` derives them from the stack name. Check with `git log --oneline origin/main --grep <KEY>` or `gh pr list -R ZFIN/zfin --search <KEY> --state merged`.
2. **Provision.** Ask once for the whole batch: "provision N stacks? (y/n)". Then:
   - `$F up <ticket> [z flags]`. The default is `-y --up --deploy --liquibase --node`. Use `--no-seed` and so on for a cold stack. Parallel runs are safe since orchestrator 08d4798.
   - Wait with `$F up-wait <ticket>`, **in the background** (`run_in_background`), so you stay responsive.
   - Then `$F intake <ticket>`, which writes `.sidecar/data/ticket.md`.
3. **Crewmate.** `$F crew <ticket>` starts `crew-<n>` in pane 2. The sidecar profile pre-accepts Claude's trust and bypass prompts; with an older image, `fleet crew` answers them itself.
4. **Plan.** Copy `templates/brief-plan.md`, fill it in, then `$F send <ticket> <brief>`. Always ask for a plan first: on both 2026-11-20 tickets the plan surfaced real decisions before any code changed.
5. **Wait.** `$F wait <ticket>`, in the background. It returns when `outbox/NNN.md` exists **and** the crewmate is idle. Work on other tickets meanwhile.
6. **Rule.** Answer the plan's questions with `templates/brief-implement.md`.
   - Rule on technical questions yourself.
   - Questions for the developer or curators (data policy, anything that changes external output): ask the developer with your recommendation, or pick the conservative option and record it under `open decisions:` with `$F state <t> "open decisions=..."`.
7. **Review**, using the checklist below. Ask for evidence with follow-up briefs (`templates/brief-sweep.md`) instead of guessing.
8. **Hand off.** Set `$F state <t> stage=ready-to-push waiting-on=developer`. Give the developer:
   - the branch and SHA;
   - what was verified, and what wasn't;
   - the PR description: the crewmate's `deliverables/PR.md`, reviewed by you.
   The developer pushes and opens the PR. Never push. Long descriptions pasted through the terminal get truncated, so offer to apply it yourself with `gh pr edit <n> -R ZFIN/zfin --body-file <file>` (or `--title`), and run it only after the developer says yes; it is outward-facing.
9. **Teardown**, only after the developer says so.
   - Run `$F check <ticket>`. It's read-only: is the branch pushed and matching origin, is the worktree clean, which deliverables are there.
   - Show the developer the result and ask "tear down <ticket>? (y/n)".
   - Then `$F down <ticket> --yes`. It archives `.sidecar/deliverables/` to `archive/reports/<ticket>/`, exits the crewmate, runs `z feature rm`, and closes the workspace.

`$F status` shows every ticket's stage, who it's waiting on, and the crewmate's state; `$F status --pr` adds push state, PR number, draft, checks, `EMPTY-DESCRIPTION` and `PR-HEAD-BEHIND`. Use it to answer "is everything pushed and PR'd?". Reread a ticket's `state.md` (`$F state <t>`) before acting on it: it is the source of truth, and your own memory may have been summarized.

## Review checklist
- [ ] Read the whole diff, and judge whether its scope is minimal.
- [ ] Tests exist **and CI's own command runs them**. ZFIN unit tests run only through the `org.zfin.UnitTests` suite (`build.gradle`, `-PnonSmokeTests`), so a new test class must be registered there. This was missed once.
- [ ] Data-transforming change: an anomaly sweep over the **whole** affected population, not just the ticket's examples (`brief-sweep.md`). The sweep on 2026-11-20 found 4 general bugs.
- [ ] UI or JS change: check from the host (`https://<t>.review.zfin.test`) that pages render and bundles load (200s), in addition to the crewmate's in-container checks.
- [ ] Build passes (`gradle npmBuild` / `compileJava` / `dirtydeploy`, whichever applies).
- [ ] Data change: which **scheduled jobs and triggers** read or rewrite the tables it touches? Check `server_apps/jenkins/trigger.production.properties` and the DB triggers, and run the relevant job's function in a rolled-back transaction after the change. On 2026-11-24, `Regen-Construct_d` (every 2h) would have renamed 24 constructs after a component re-point that looked correct in isolation.
- [ ] The report says what was NOT verified. Pass those gaps on to the developer.
- [ ] Reports or spreadsheets for the developer are in `.sidecar/deliverables/`. Anything left in `data/` is lost at teardown.

## Automation boundaries
- **Automatic** (local, reversible): mailbox and state upkeep, crewmate launch, briefs, waits, read-only checks and smoke checks, archiving deliverables, local commits by crewmates.
- **Ask once (y/n), with a recommendation:**
  - provisioning a batch of stacks;
  - decisions that belong to the developer or curators;
  - teardown;
  - image rebuilds;
  - Jira comments or transitions (show the draft);
  - any write to a shared mount (`stacks/mounts/*`).
- **Never:** push, open PRs, force-push, ssh to remote VMs (not even read-only), `git stash`, stop the default herdr server.
- **Don't edit a crewmate's worktree yourself.** Code changes go through the crewmate. If you must write something (e.g. an urgent fix), do it in a separate worktree on its own branch, then brief the crewmate to cherry-pick it after its current commit. Two writers in one worktree collide.
- **Host actions a crewmate asks for** under `## Needs from host`: start or restart that stack's own services (`z up <svc>`, `docker restart <stack>-<svc>-1`) without asking; they're local and reversible. Anything touching other stacks, shared mounts, accounts, or the default herdr server goes to the developer.

## Gotchas
- `z feature rm` deletes the worktree, `.sidecar/` included. That's why `fleet down` archives `deliverables/` first. Archive layout: `archive/reports/<ticket>/<name>`.
- Never run `z feature rm` from inside the stack's own pane or shell. `fleet down` runs it from `worktrees/main`.
- The crewmate's `Bash` runs in the container: the worktree is at `/opt/zfin/source_roots/zfin.org`, the site is at `https://httpd/` (self-signed), and there's no `gh` and no `acli`.
- psql from the host: `docker exec -i <stack>-db-1 psql -U postgres -d zfindb`. `z exec db psql` breaks.
- The project `.npmrc` has `min-release-age=7`, so the newest npm versions may not resolve yet. That's expected; don't bypass it.
- If an old `zfin-claude` image is still in use and npm fails with EALLOWSCRIPTS, rebuild the image (fixed in orchestrator 329d933) or `unset NPM_CONFIG_ALLOW_SCRIPTS` per command.
- The shared downloads mount (`stacks/mounts/downloads`) is visible to every stack. Ask before writing to it.
