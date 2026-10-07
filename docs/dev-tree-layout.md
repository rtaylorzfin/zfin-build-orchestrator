# The dev tree: one parent for everything

Everything this tooling reads or writes lives under **one directory you choose**: the dev
tree, `ZFIN_DEV_ROOT`. A `zfin-dev.env` file at its root marks it, and `z` finds that file by
walking up from wherever you are, the way git finds `.git`. One host can carry several trees.

There is deliberately **no default**. An absolute default like `/opt/zfin/dev` is a guess
about someone else's machine, and when it is wrong the symptom is a directory appearing
somewhere unexpected rather than an error.

Create it with:

```bash
z scaffold --root ~/zfin-dev      # or just `z scaffold` from inside an existing tree
```

It creates what is missing and never clobbers, so it is safe to re-run on a partially
set-up tree. It writes the tree's `zfin-dev.env`; if you ran it from somewhere that is not
inside the new tree, it also records the tree in your user file, so `z` finds it from there.
Run `z` with no tree to be found and, on a terminal, it offers to create one.

---

## Recommended structure

```
$ZFIN_DEV_ROOT/                  e.g. ~/zfin-dev  or  /opt/zfin-dev
├── zfin-dev.env                 marks the tree; its settings (z config)
├── orchestrator/                this tooling, cloned by you; one install serves the host
├── worktrees/                   every ZFIN checkout, side by side
│   ├── main/                    the main checkout: owns .git, holds docker/.env, supplies
│   │                            every stack's base compose file. Cloned by you; keep it on main
│   ├── zfin-10358/              a feature: a linked worktree, named for its ticket
│   └── zfin-10475/
├── seeds/                       captured stacks, restored into new ones (z seed)
│   └── <tag>/
├── archive/                     parked state
│   ├── <ticket>/                a frozen stack's volumes (z feature freeze)
│   └── sessions/                sidecar session history (z feature session)
├── cache/                       z seed build's staging; safe to delete
└── mounts/                      optional: host paths bind-mounted into containers
    ├── unloads/{db,solr}        DOCKER_DB_UNLOADS_PATH / DOCKER_SOLR_UNLOADS_PATH
    ├── research/                DOCKER_RESEARCH_PATH
    ├── blast/                   DOCKER_BLASTSERVER_BLAST_DATABASE_PATH, DOCKER_ABBLAST_PATH
    ├── downloads/               DOCKER_DOWNLOADS_PATH
    ├── loadUp/                  DOCKER_LOADUP_PATH
    ├── gff3/                    DOCKER_GFF3_PATH
    └── hh_atlas/                DOCKER_HHATLAS_PATH
```

One thing to back up, relocate, or delete.

---

## Worktrees: every checkout side by side

`worktrees/` holds every ZFIN checkout at one depth: the **main checkout**, `worktrees/main`,
and each feature beside it, `worktrees/<ticket>`. These are git's own terms -- the main worktree
owns `.git`, and each feature is a *linked* worktree whose `.git` is a file pointing into it.

That distinction is how the tooling tells them apart: **a feature is a linked worktree with a
provisioned `docker/.env`**. The main checkout has a `docker/.env` too, for its own stack, but
its `.git` is a directory, so `z feature ls` never lists it and `z feature rm main` or
`z feature new main` refuse.

The main checkout's role is to supply every stack's base `docker-compose.yml` and the
`docker/.env` each new feature's starts from. Keep it on `main`: the tooling does not require
it, but whatever branch is checked out there is the compose file every stack uses -- a branch
older than `x-zfin-compose-version` makes `z` refuse until you switch back. `docker/.env` is
untracked, so switching branches leaves it alone. Git allows a branch to be checked out in one
place at a time, so a feature cannot use the branch `main/` currently has.

`z` finds the main checkout by asking git, so this layout is a recommendation: a checkout kept
elsewhere works too, with the tree named in your user file (see Configuration).

---

## Configuration

These are **host settings**: how this machine runs the tooling, never kept in a ZFIN checkout's
`docker/.env`. They come from, in order:

1. the process environment, so a one-off `ZFIN_SEED=<tag> z feature new …` changes nothing;
2. the dev tree's `zfin-dev.env` -- this tree's settings;
3. your user file, `~/.config/zfin-build-orchestrator/env` (under `$XDG_CONFIG_HOME` when that is
   set) -- for running `z` outside any tree, such as from a checkout kept elsewhere. It may name
   `ZFIN_DEV_ROOT`, and holds your own defaults;
4. the default.

Manage them with `z config`:

```bash
z config                                   # every setting, its value and where it comes from
z config set ZFIN_ARCHIVE_DIR=/Volumes/backup/zfin-archive     # into this tree's zfin-dev.env
z config set --user ZFIN_DEV_ROOT=~/zfin-dev                   # find that tree from anywhere
z config unset ZFIN_ARCHIVE_DIR            # back to the next source
```

`ZFIN_DEV_ROOT` has no default: it is the tree you are in, or the one your user file names.
Everything else derives from it. Override individually only when something must live
elsewhere:

| variable | defaults to | override when |
|---|---|---|
| `ZFIN_WORKTREES_DIR` | `$ZFIN_DEV_ROOT/worktrees` | rarely |
| `ZFIN_CACHE_DIR` | `$ZFIN_DEV_ROOT/cache` | rarely (`z seed build` stages its inputs and worktree here) |
| `ZFIN_SEEDS_DIR` | `$ZFIN_DEV_ROOT/seeds` | **seeds on NFS, or shared between trees** |
| `ZFIN_ARCHIVE_DIR` | `$ZFIN_DEV_ROOT/archive` | **archives on NFS or an external disk** |

The `DOCKER_*_PATH` mounts stay individually configured, because they are often *shared*
with other tooling — Jenkins jobs, Ant tasks, other instances — rather than owned by this
tree. On a laptop, point them under `$ZFIN_DEV_ROOT/mounts`. On a server, leave them at
whatever the organisation already uses.

---

## Published ports

Every published port is an **offset on one address** (`ZFIN_FEATURE_BIND`, default
`127.0.0.1`), the scheme ZFIN's own Linux hosts use (cell publishes 8084/8447/8988/9503 on one
address):

| service | port | |
|---|---|---|
| httpd | `8443 + N` (https), `8080 + N` (http) | the stack's direct URL |
| db | `5432 + N` | Postgres cannot be routed by hostname; GUI clients need a port |
| tomcatdebug | `5000 + N` | JDWP carries no routing information at all |
| jenkins | `9499 + N` | convenience; also reachable at `<stack-url>/jobs` |

`N` is allocated per stack (`ZFIN_PORT_OFFSET` in its `.env`, `--port-offset` to pin it), and
skips any port in those ranges already published on the host. Solr is reached at
`<stack-url>/solr`. Offsets need no loopback aliases, so no `sudo`, and behave the same on macOS
and Linux.

---

## Offloading to NFS or an external disk

Two ways, and they are not equivalent:

**Override the setting** (preferred):

```bash
z config set ZFIN_ARCHIVE_DIR=/Volumes/backup/zfin-archive
z config set ZFIN_SEEDS_DIR=/Volumes/backup/zfin-seeds
```

**Symlink** the directory. Works on Linux. Under Docker Desktop for macOS a bind source that
symlinks outside the shared filesystem can fail — Docker resolves the link host-side and the
target may not be in its file-sharing config. Test it before relying on it.

Either way: an archive **at rest** on NFS or USB is fine; never run a database off one. I/O
latency and fsync semantics will corrupt PGDATA.

---

## What is deliberately NOT here

**Credentials.** The Claude sidecar token lives at `~/.zfin/claude-token`
(`ZFIN_CLAUDE_TOKEN_FILE`), and the development TLS certificate's key beside your user file
(`z cert`), both under `$HOME` rather than in this tree. On a shared host the dev
tree is group-writable so developers can collaborate on worktrees — a credential there would
hand one person's subscription to everyone with an account.

**Docker volumes.** Per-stack volumes live wherever the Docker daemon keeps them, and cannot
be relocated to NFS -- overlayfs needs a local upper directory, and a database must never run
off NFS regardless. What *can* live there is `seeds/` and `archive/`: ordinary tarballs, and
moving them is the whole point of the feature.

---

## Multiple checkouts on one host

`cache/` and the seeds under `archive/` are **per machine**, not per checkout: a seed is captured
once and restored everywhere. If a host carries several checkouts (`coral`, `cell`), they should
share one `ZFIN_DEV_ROOT` so they share that state -- and so their feature stacks draw port
offsets from one pool.
