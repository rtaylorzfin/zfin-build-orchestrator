# You are running inside the ZFIN Claude sidecar

This file is user memory seeded into `~/.claude/CLAUDE.md` **only inside the sidecar
container**, so it does not affect sessions running on the host.

`ZFIN_SIDECAR=true` is set in the environment; `/.dockerenv` also exists. Either confirms it.

## Do not use `z` here

The project `CLAUDE.md` documents `z` as the front door for running commands. That guidance
is for a session on the **host**, where `z` wraps `docker compose` to reach into a container.

**You are already inside that container.** `z` refuses to run here on purpose (it detects
`/.dockerenv` and exits), because it drives the host Docker daemon and reasons in host paths,
which name something different — or nothing — on this side of the mount.

So wherever the project docs say `./z run -c "<cmd>"`, just run `<cmd>`.

| Host session does | You do |
|---|---|
| `./z run -c "gradle dirtycopy"` | `gradle dirtycopy` |
| `./z run -c "gradle compileJava"` | `gradle compileJava` |
| `./z run -c "psql ..."` | `psql -h db -U postgres -d zfindb` |

Run builds from `$SOURCEROOT` (`/opt/zfin/source_roots/zfin.org`), which is this feature's
worktree, mounted read-write. It is the only source tree you can reach.

## What you can and cannot do

**Can:** edit this worktree; `gradle` / `ant` builds; `gradle dirtycopy` and `dirtydeploy`
(the deploy volumes are mounted, and Tomcat reloads on its own); reach `db` and `solr` by
those hostnames; `gradle loaddb` / `loadsolr` (the dumps are mounted read-only) — note
**loaddb destroys and rebuilds this stack's database**, so confirm before running it.

**Cannot, by construction rather than by rule:**

- **`git push`** — this container has no SSH agent and no keys, so there is nothing to
  authenticate with. A `PreToolUse` hook also blocks it, to make the failure legible. Pushing
  is a human action on the host; ask, do not work around it.
- **Restart Tomcat** — a host operation. `buildfiles/tomcat.xml` fails deliberately if you
  try. Ask the human to run `z restart tomcat`. You rarely need it: `dirtycopy` plus Tomcat's
  own reload usually suffices.
- **Reach the host Docker daemon** — no socket is mounted, so no `docker` commands.
- **Reach another feature's worktree, or `/mnt/research`** — not mounted. The `/research/*`
  symlinks point at empty directories here.
- **`gradle getdb` / `getsolr`** — they `scp` from a remote host and there are no credentials.
  Dumps are fetched on the host and land in the mounted unloads directory.

## Why it is set up this way

The container exists so an agent can work without permission prompts while the worst case
stays "a trashed, git-tracked worktree" rather than a damaged host or an unwanted push. If
something seems blocked, that is usually deliberate — say so rather than routing around it.
