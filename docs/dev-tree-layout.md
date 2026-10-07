# The dev tree: one parent for everything

Everything this tooling reads or writes lives under **one directory you choose**, declared
once as the `ZFIN_DEV_ROOT` host setting. `z` asks for it the first time it needs it and keeps
the answer in its config file (`z config path`).

There is deliberately **no default**. An absolute default like `/opt/zfin/dev` is a guess
about someone else's machine, and when it is wrong the symptom is a directory appearing
somewhere unexpected rather than an error.

Create it with:

```bash
z scaffold --root ~/zfin-dev      # or just `z scaffold` once ZFIN_DEV_ROOT is set
```

It creates what is missing and never clobbers, so it is safe to re-run on a partially
set-up tree, and it saves `--root` as `ZFIN_DEV_ROOT`.

---

## Recommended structure

```
$ZFIN_DEV_ROOT/                  e.g. ~/zfin-dev  or  /opt/zfin-dev
├── zfin-build-orchestrator/     this tooling: one install serves every checkout below.
├── <checkout>/                  the checkout — name it for the INSTANCE, not "zfin.org".
│                                Its location is not a convention the tooling enforces: `z`
│                                asks git where it is. It need not live here at all.
├── worktrees/
│   ├── zfin-10358/              one per feature; the directory name IS the ticket
│   └── zfin-10475/
├── archive/                     freeze archives + sidecar session history
│   ├── <ticket>/                a frozen stack's volumes
│   └── sessions/
├── seeds/                       (under archive/) captured stack volumes, restored into new stacks
└── mounts/                      the host paths bind-mounted into containers
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

## Why name the checkout `coral`, not `zfin.org`

ZFIN has a long-standing convention of hosting per-host checkouts as
`/path/to/zfin.org/{cell,coral,schlapp,...}`. Naming the checkout directory `zfin.org`
inverts that and reads as though the repo *is* the site rather than one host's copy of it.

**Name the checkout for the instance it runs as** — `coral`, `cell`, `schlapp`. The tooling
does not care: `z` asks git which checkout the working directory is in, so the directory can be
called anything. This is a readability convention, not a requirement.

(The container always sees the source at `/opt/zfin/source_roots/zfin.org` regardless —
that is a fixed mount *target*, unrelated to the host layout.)

---

## Worktrees: `worktrees/<ticket>`, no prefix

Earlier versions used `wt-<ticket>` directories as siblings of the checkout. With a handful
of tickets in flight that clutters the parent, and the prefix exists only to tell worktrees
apart from everything else beside them.

A dedicated `worktrees/` directory makes the prefix redundant, so the directory name is just
the ticket. A worktree is now identified by **having a provisioned `docker/.env`**, which is
a better test than a name prefix anyway — it cannot mistake a stray directory for a stack.

---

## Configuration

These are **host settings**: how this machine runs the tooling, kept in the tool's own config
file (`~/.config/zfin-build-orchestrator/env`, under `$XDG_CONFIG_HOME` when that is set) and
never in a ZFIN checkout's `docker/.env`. Manage them with `z config`:

```bash
z config                                   # every setting, its value and where it comes from
z config set ZFIN_ARCHIVE_DIR=/Volumes/backup/zfin-archive
z config unset ZFIN_ARCHIVE_DIR            # back to the default
```

The process environment overrides the file, so a one-off `ZFIN_SEED=<tag> z feature new …`
works without changing anything.

`ZFIN_DEV_ROOT` is the only one without a default; `z` asks for it on first use, suggesting the
value a checkout's `docker/.env` already carries if it has one. Everything else derives from it.
Override individually only when something must live elsewhere:

| variable | defaults to | override when |
|---|---|---|
| `ZFIN_WORKTREES_DIR` | `$ZFIN_DEV_ROOT/worktrees` | rarely |
| `ZFIN_CACHE_DIR` | `$ZFIN_DEV_ROOT/cache` | rarely (`z seed build` stages its inputs and worktree here) |
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
```

**Symlink** the directory. Works on Linux. Under Docker Desktop for macOS a bind source that
symlinks outside the shared filesystem can fail — Docker resolves the link host-side and the
target may not be in its file-sharing config. Test it before relying on it.

Either way: an archive **at rest** on NFS or USB is fine; never run a database off one. I/O
latency and fsync semantics will corrupt PGDATA.

---

## What is deliberately NOT here

**Credentials.** The Claude sidecar token lives at `~/.zfin/claude-token`
(`ZFIN_CLAUDE_TOKEN_FILE`), under `$HOME` rather than in this tree. On a shared host the dev
tree is group-writable so developers can collaborate on worktrees — a credential there would
hand one person's subscription to everyone with an account.

**Docker volumes.** Per-stack volumes live wherever the Docker daemon keeps them, and cannot
be relocated to NFS -- overlayfs needs a local upper directory, and a database must never run
off NFS regardless. What *can* live there is `archive/`, which holds both the freeze archives
and `seeds/`: those are ordinary tarballs, and moving them is the whole point of the feature.

---

## Multiple checkouts on one host

`cache/` and the seeds under `archive/` are **per machine**, not per checkout: a seed is captured
once and restored everywhere. If a host carries several checkouts (`coral`, `cell`), they should
share one `ZFIN_DEV_ROOT` so they share that state -- and so their feature stacks draw port
offsets from one pool.
