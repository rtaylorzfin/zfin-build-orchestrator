# Per-feature dev stacks

Run several ZFIN feature branches at once, each in its own isolated Docker stack that boots in
minutes from a **seed** — a captured copy of a loaded DB, Solr index and deployed app tier — with
no `getdb`/`loaddb`/`getsolr`/`loadsolr` and no first-time full deploy per feature. Each stack is
reachable on its own published port, and by name through an nginx-proxy when the host already
runs one. This tooling runs no proxy. This doc is the map: what the pieces are, how they fit, and how
to use them.

> Orientation only — the authoritative details live in each script's header comment
> and in the ZFIN repo's [build-and-docker.md](https://github.com/ZFIN/zfin/blob/main/reference/build-and-docker.md) /
> [deploying-changes.md](https://github.com/ZFIN/zfin/blob/main/reference/deploying-changes.md).

## Install

This tooling is opt-in and lives in its own checkout, beside your ZFIN checkouts rather than
inside one. Install it once per host:

```bash
cd ~/zfin-dev
git clone git@github.com:rtaylorzfin/zfin-build-orchestrator.git orchestrator
eval "$(./orchestrator/z shell-init)"    # z on PATH + tab completion, for this shell
z scaffold --root ~/zfin-dev             # the dev tree, and offers to clone ZFIN into worktrees/main
```

`eval` sets up the current shell only, so run it in each new shell, or append the same output
to your startup file once: `./orchestrator/z shell-init >> ~/.bashrc`.

Needs `groovy`, Docker with Compose v2, and git on the host. `z` then works from inside any
ZFIN checkout or feature worktree: it asks git which checkout you are standing in, and takes
the base `docker-compose.yml` and `docker/.env` from there. From anywhere else, set
`ZFIN_REPO=<checkout>`. Update the tooling with `git pull` in its checkout.

Host settings (where worktrees and archives live, which address and proxy network stacks use)
are kept in the dev tree's `zfin-dev.env` -- found by walking up from where you are, or named
by an exported `ZFIN_DEV_ROOT` -- and managed with `z config`; see
[dev-tree-layout.md](dev-tree-layout.md#configuration). A ZFIN checkout's `docker/.env` holds
only what its stack is (`ZFIN_RELEASE`, the `DOCKER_*` paths and ports), and is managed with
`z env` (`z env init` creates it from `docker/environment_mac|linux`).

**Version check.** The ZFIN repo's `docker/docker-compose.yml` declares its version as an
interface this tooling depends on, `x-zfin-compose-version: N`, and
`StackConfig.COMPOSE_VERSIONS_SUPPORTED` lists the versions this tooling works with. `z` refuses
a checkout outside that list the first time it resolves one: a checkout with no marker predates
the hooks this tooling needs (update it), and a newer one needs newer tooling (`git pull` here).
`ZFIN_SKIP_COMPOSE_VERSION_CHECK=1` proceeds anyway, with a warning.

---

## The idea in one picture

```
  z seed new       ──captures──▶  $ZFIN_SEEDS_DIR/<tag>/
  (once per host, from            pg_data.tgz  solr_var.tgz   (loaded DB + Solr index)
   a DEPLOYED stack)              www_data.tgz catalina_base.tgz ...  (the app tier)
                                  seed.json     (checksums + the postgres major)

  z feature new ZFIN-1234  ──▶  git worktree  +  per-feature docker/.env
                               +  its own ports: https 8443+N (db/debug/jenkins too)
                               +  optionally, a route through the host's nginx-proxy
                                  (ZFIN_PROXY_NETWORK): VIRTUAL_HOST=<slug>.<domain>
                               +  a Compose project whose volumes are RESTORED from the seed
                                  before anything starts

  z <cmd>  in that tree ──▶  every stack op targets THIS feature, read from its
                               docker/.env; nothing to activate, nothing to deactivate
```

Each feature is a **separate Compose project**, so Docker already gives it its own
network, volumes, and intra-network DNS (`db`/`solr` resolve to *this* feature's
containers). The tooling supplies the host-side pieces Compose can't: the data to restore,
ports that do not collide, and -- when the host has a proxy -- the network and vhost that let it
route the stack.

---

## What a stack costs, and why

A stack's data is restored from a seed rather than seeded by Docker from an image. `z seed new`
captures a loaded stack's volumes as compressed tarballs plus a manifest; `z feature new --seed`
extracts them into the new stack's volumes, in parallel, before anything starts.

```
  seed (once per host)      ~3.5G db + ~3.2G solr + ~0.3G app tier, compressed
  + per feature stack       ~17G db  + ~6G solr   (expanded, a full private copy)
  + build caches (optional) ~1-2G gradle/maven/npm
```

The per-stack copy is unavoidable on plain Docker: postgres and solr each own their data
directory, so two stacks cannot share one. What the seed changes is the *one-time* cost and where
it lives. The seed is ~7G of ordinary files, so it can sit on NFS. The same data baked into
images is ~30G that can only live in the local Docker image store, because overlayfs needs a
local upper directory -- which is why seeds are tarballs and not images.

It is also faster. Measured on a shared VM restoring the same ~21G: **79.9s** from tarballs
against **~170s** for Docker seeding a volume from an image, because tar streams where the daemon
copies file by file. The images had no offsetting advantage — layer sharing does not apply when
`VOLUME` forces a full copy into every stack anyway.

`z seed new` trims WAL before capturing (`pg_resetwal` after a clean shutdown), which on this
host took a snapshot from 32.6G to 19.7G. The reclaimed space was retained WAL: dead weight in a
frozen snapshot, having overshot `max_wal_size` during the load.

To reclaim a stack's disk without losing it, `z feature freeze` archives its volumes and
`z feature thaw` restores them — the same capture and restore code the seed uses. Inside a
feature worktree, `z freeze` / `z thaw` do the same for that feature.

Freeze works one volume at a time, smallest first: capture, read back, record, remove. So it
never needs the whole stack and the whole archive on disk at once, only about the largest
archive. It checks free space before it stops anything. A freeze that runs out of space or is
interrupted stops in a safe state that `z feature ls` shows as `freezing`: run `z freeze` to
finish it or `z thaw` to back out (`z up` refuses until you do one or the other).

Postgres keeps written WAL for reuse up to `max_wal_size`. Feature stacks cap that at 1GB
(`DOCKER_DB_MAX_WAL_SIZE` in a stack's .env overrides it); the base file's 10GB is for
instances. To shed WAL a database is already holding, `z resetwal` stops it, runs
`pg_resetwal` (which refuses unless the shutdown was clean) and starts it again. It acts on this
stack's db by default, or on a named feature or any postgres container, such as the shared one.
It warns and asks before doing anything; `-y` skips the question.

---

## The pieces

**`z`** is the single front door (Groovy). The command classes live in **`lib/`**, the compose
overlays and the sidecar's build context in **`compose/`**. Two kinds of path never mix: the
tool's own files are found from where `z` lives, and the ZFIN checkout from where you run it.

| File | Role |
|------|------|
| `z` | The front door (Groovy). Self-locates, builds one `ZfinUtil`, and routes each command to its class — run in-process. |
| `lib/ZfinUtil.groovy` | Shared class: process/logging helpers, canonical roots, and the volume capture/restore contract (`APP_VOLS`/`CACHE_VOLS`/`DATA_VOLS`) — the single source seed, freeze, thaw and new-feature all read. |
| `lib/StackOps.groovy` | The stack lifecycle family: `run`/`exec`/`up`/`stop`/`down`/`pull`/`log`/`restart`/`status`. |
| `lib/SharedStack.groovy` | The shared data stack (`z shared up`/`down`/`status`) that `--shared-db` features attach to. |
| `lib/NewFeature.groovy` | Provision a feature: worktree + `docker/.env` + boot (`z feature new`). |
| `lib/FeatureFreeze.groovy` / `FeatureThaw.groovy` | Park a stack's volumes to an archive, and restore them (`z feature freeze`/`thaw`). |
| `lib/FeatureSession.groovy` | Archive the Claude sidecar's history so it outlives the stack (`z feature session`). |
| `lib/Scaffold.groovy` | Create the recommended dev-tree layout (`z scaffold`). |
| `lib/StackConfig.groovy` | ZFIN-specific policy: image names, volume contracts, service roles. |
| `lib/FeatureList.groovy` | List feature stacks (`z feature ls`). |
| `lib/FeatureRemove.groovy` | Tear a feature down: down -v + worktree/branch/hosts (`z feature rm`). |
| `lib/SeedBuild.groovy` | Build a seed from a db dump and a solr snapshot: the full load and deploy in a throwaway stack, captured and torn down (`z seed new`). |
| `lib/Seed.groovy` | Capture a loaded stack's volumes as a reusable seed, live or from its freeze archive, and list/remove them (`z seed new --from\|ls\|rm`; `z seed new` without `--from` hands off to SeedBuild); WAL trimmed on every capture. |
| `lib/SeedTransfer.groovy` | Copy a seed between hosts over ssh, verified before it is renamed into place (`z seed push\|pull`). |
| `lib/Zbuild.groovy` | Non-interactive, phased build/deploy orchestrator — the CI engine (`z build`; what GoCD stages should call). |
| `lib/FreshInstall.groovy` | Guided day-zero setup on a bare workstation (`z fresh-install`). |
| `lib/z-completion.bash` | bash tab-completion for `z`; source it from `~/.bashrc`. |
| `check` | Static checks: compiles every command class, and flags helper calls that are neither aliased nor qualified. Run it after editing `lib/`. |

No command self-locates: `z` resolves its install dir once, then loads `ZfinUtil` + every
command class through one `GroovyClassLoader` (so `ZfinUtil` is a single `Class`), and calls
`cmd.run(args, zfinUtil)` in-process — the helpers + roots arrive as a typed parameter. Stack
ops auto-detect their target from the cwd; `z build`/`z scaffold`/`z fresh-install` need none (CI/bootstrap).

**Compose files.** The ZFIN checkout's base `docker-compose.yml` defines every service; small
overlays in `compose/` each encode one orthogonal choice:

| file | decides |
|---|---|
| `docker-compose.overlay-feature.yml` | this is a feature stack: certbot and fail2ban stay out, and it has a Claude sidecar (`z run claude`) |
| `docker-compose.overlay-worktree.yml` | the tree is a git worktree, so git in the containers needs the main repo mounted. Added by `z` whenever the tree's `.git` is a file; never recorded in `.env` |
| `docker-compose.overlay-proxy-network.yml` | its httpd joins an outside nginx-proxy's network |
| `docker-compose.overlay-shared.yml` | this project is the shared data **provider** (`zfin_shared`) |
| `docker-compose.overlay-shared-db.yml` | this stack **consumes** shared data instead of its own |

They cannot collapse into values or profiles: an `external` network named by a variable cannot
be conditionally attached (it passes `config` and fails at `up`), and a service suppressed by a
profile is the unit a value cannot express.

---

## How a stack is targeted

There is nothing to activate. Stack ops (`run`/`exec`/`up`/`stop`/`down`/`pull`/`log`/
`restart`/`status`) resolve the checkout root via git, read its **`docker/.env`**, and target
the stack that file describes for that one invocation — announced on stderr:

```bash
z run -c "gradle dirtydeploy"    # >> targeting 'zfin-10454' (zfin-10454)
```

One stack per checkout or worktree, so there is nothing to search for. An explicit
`COMPOSE_PROJECT_NAME`/`COMPOSE_FILE` in the environment (as GoCD sets) still wins.

### The per-feature `.env` is the whole record

A stack is described entirely by its own `docker/.env`:

| key | what it fixes |
|---|---|
| `COMPOSE_PROJECT_NAME` | which Docker project — and what makes the directory a stack at all |
| `ZFIN_COMPOSE_OVERLAYS` | which compose overlays it was built with, `:`-separated |
| `ZFIN_SEED` | the seed its volumes were restored from |
| `DOCKER_VIRTUAL_HOST` | its hostname, `<slug>.<ZFIN_FEATURE_DOMAIN>`: the name an outside proxy routes, if it joined one |
| `ZFIN_PORT_OFFSET` | its offset N for every published port |
| `DOCKER_HTTPD_HTTPS_PORT` | where it is reached directly: `<bind>:8443+N` |

`ZFIN_COMPOSE_OVERLAYS=` (present but **empty**) means base compose only. Omitting the key
gives the same to a checkout's own stack, and the feature overlay to a feature worktree (one
under the worktrees directory), so a feature whose `.env` predates the key still tears down
correctly.

The compose files themselves come from the checkout whose `z` you run, never from a copy in the
worktree: one source of truth, which cannot fall silently behind the tooling reading it.

---

## Reaching a stack

The repo runs no proxy. Three host settings in `docker/.env` decide how stacks are reached, and
each stack freezes what they were into its own `.env` when it is made:

| setting | default | what it does |
|---|---|---|
| `ZFIN_FEATURE_BIND` | `127.0.0.1` | the address every published port binds to |
| `ZFIN_FEATURE_DOMAIN` | `review.zfin.test` | stacks are named `<slug>.<domain>` |
| `ZFIN_PROXY_NETWORK` | unset | the host's own nginx-proxy network: stacks join it and advertise their name |
| `ZFIN_PROXY_HTTP_PORT` / `ZFIN_PROXY_HTTPS_PORT` | `80` / `443` | where `z proxy` listens, on `ZFIN_FEATURE_BIND` |

**Directly.** Every stack publishes httpd at `https://<bind>:8443+N` (and `http` 8080+N). Nothing
to set up. Relative links, which are most of them, work. A stack runs as the `feature` instance,
which the ZFIN repo's `all-properties.yml` does not list, so it gets the development defaults:
`DOMAIN_NAME` is `zfin.org`, and the few absolute links the app builds -- mostly in mail --
point at production. That mail goes to the stack's mailpit (`SMTP_HOST=mailpit`), never out.

**By name, through `z proxy`.** On a host with no proxy of its own:

```bash
z proxy up          # stock nginx-proxy (compose/proxy.yml) on ZFIN_FEATURE_BIND, ports 80/443
```

New stacks join its network and are served at `https://<slug>.<domain>`, with the host
development certificate (`z cert`), so the names need no browser exception once it is trusted.
A stack made before the proxy ran joins with `z proxy attach <ticket>` (or `--all`), which adds
the proxy-network overlay to its `.env` and recreates its httpd. `z proxy status` lists what it
routes; `z proxy down` stops it, and stacks stay reachable directly. The names must resolve to
the proxy: `up` and `status` check, and print the dnsmasq lines when they do not -- on macOS,
`address=/<domain>/127.0.0.1` in dnsmasq plus `/etc/resolver/<domain>`, once per host.

**Through a proxy the host already runs.** Set `ZFIN_PROXY_NETWORK` to a network that proxy
watches, and leave `z proxy` down: two nginx-proxies on one Docker socket would both claim every
stack. A stack made after that adds `docker-compose.overlay-proxy-network.yml` and advertises
`VIRTUAL_HOST=<slug>.<domain>` (`VIRTUAL_PROTO=https`, `VIRTUAL_PORT=443`: plain http to httpd
loops the admin paths and drops the `Secure` session cookie). Making the names resolve to that
proxy -- wildcard DNS, dnsmasq, `/etc/hosts` -- is the host's business. `z feature new` refuses
before creating anything when the network does not exist. Either way, the network a stack
joined is recorded in its `.env` (`ZFIN_PROXY_NETWORK`), since every compose run on it names it.

A stack made WITHOUT the setting advertises nothing: the feature overlay clears httpd's
`VIRTUAL_HOST`, so a proxy watching the Docker socket never publishes a route to a stack it
cannot reach. The proxy-network overlay, layered after it, sets the name back.

**TLS.** Every stack serves the same self-signed certificate: one per host and feature domain,
covering `zfin.org`, `*.<domain>`, `localhost` and `127.0.0.1`. `z feature new` installs it into
the stack's `tls_certs` (httpd) and `keystore` (tomcat) volumes, so trusting it once covers every
stack. `z cert` prints where it is and how to trust it; `z cert install [<ticket>]` puts it into a
stack made before it existed (then `z restart httpd tomcat`). It is kept in the dev tree, under
`config/certs/<domain>/`. A stack without it serves the
certificate its own compile container makes (the ZFIN repo's `generate_base.sh`), which differs
per stack.

**Without keycloak (`DISABLE_OIDC=true`).** In the base file the flag only opens httpd's gates.
On a stack with the feature overlay it reaches Jenkins too: a startup script
(`compose/jenkins/zfin-security-realm.groovy`) switches Jenkins from OIDC to its own user
database on its next start (`z up jenkins`), saving the OIDC configuration first and creating an
`admin` account if there is none. The password is `JENKINS_ADMIN_PASSWORD` from the `.env`, or
generated into `jenkins-home/secrets/initialAdminPassword`. Turn the flag off and the next start
restores the saved OIDC configuration.

It fails loudly, on purpose. While Jenkins is on local accounts, `jenkins_data` carries a copy of
the script (`init.groovy.d/zfin-security-realm-guard.groovy`), so a frozen stack, a seed, or a
volume started with plain `docker compose` still restores OIDC. If there is nothing to restore,
Jenkins prints how to redeploy `config.xml` and shuts down rather than staying on local accounts.

---

## Everyday workflow

```bash
# 0. once per host: capture a seed from a DEPLOYED stack (an instance, or a built feature)
z seed new --from coral --tag dev              # WAL always trimmed; --app is ON by default
                                               #   add --caches for warm gradle/maven/npm

# the checkout's OWN stack (the base checkout, an instance) takes a seed too -- restore it
# where you stand, then start the tier you want. Refuses to replace existing data without
# --force, and refuses even then while a container uses it (z down first).
z seed restore 2026-09-29 --app                # db+solr, plus the deployed app tier
z up db solr tomcat httpd

# 1. provision a feature -- on a TTY this PROMPTS for the whole plan (base, tag, shared
#    db, boot, hosts, npm ci, dirtydeploy, liquibase, tmux) and then RUNS it. Every
#    prompt is Enter-able and any flag you pass skips its own question.
z feature new ZFIN-1234                       # worktree, docker/.env, hostname, boot, deploy

# ...or say it all on the command line and skip the questions:
z feature new ZFIN-1234 -y --up --deploy --tmux

# a branch that already exists (yours, or a colleague's PR you're reviewing): --existing-branch
# checks it out instead of cutting a new one, and --base is moot. Interactively you're asked
# this whenever the branch exists, so the flag matters for -y / scripted runs. A branch known
# only to origin counts -- the worktree gets a local branch tracking it.
z feature new ZFIN-1234 -y --existing-branch --up
z feature new review-pr -y --existing-branch --branch someones-branch --up

# 2. work in it -- with --tmux you are ALREADY here: z attaches you to a session named
#    after the slug, sitting in the worktree. (This is the one step z can't do for your
#    current shell: `cd` changes the CALLING shell, which a child process cannot reach.
#    Hence the session.)
z run -c "gradle dirtydeploy"               # deploy this branch's delta (warm app)
# ...edit / dirtydeploy loop...

# ...without --tmux, just cd there -- every z command then targets this stack:
cd $ZFIN_DEV_ROOT/worktrees/zfin-1234

# 3. tear it down once the PR is merged (see below) -- `z feature rm` also kills the
#    tmux session, so nothing is left pointing at a worktree that no longer exists.
```

### Building a seed from a dump (`z seed new`)

`z seed new --from <project>` captures a stack that is already loaded. `z seed new` alone makes one from
nothing but a dump:

```bash
z seed new --db ~/dumps/2026.09.28.1/zfindb.bak --solr ~/dumps/snapshot.2026.09.28-03.00 --tmux
z seed new                          # the dump and snapshot loaddb/getLatestSolrIndex would pick
z seed new --resume                 # carry on after a failed phase (--tag T when several are going)
z seed new --clean                  # or discard it
```

Without `--tag`, the seed is named after what it is built from: `<dump>_<snapshot>_<commit>`,
e.g. `2026.10.26.1_2026.10.27-01.43_b3df0e2`. The same inputs give the same name, so a second
build with no new dump, snapshot or commit stops at once and says so instead of duplicating.

It runs `z build configure load-db load-solr deploy-jenkins deploy` in a Compose project of
its own (`seedbuild-<tag>`), then `z seed new --from` against it, then `down -v`. The choices that
shape it:

- **Its own project, invisible to the host.** Every published port is an ephemeral
  `127.0.0.1` port and every vhost key is empty, so it runs beside the base stack and every
  feature without colliding with them or being picked up by a proxy.
- **Its own worktree.** The code comes from a detached worktree at `--ref` (default: `HEAD` of
  the checkout you are in), not from your checkout. `gradle make` writes `build/` and
  `home/WEB-INF/zfin.properties` into the tree it builds, so sharing a checkout would overwrite
  that checkout's own stack's config. Uncommitted changes are therefore not in the build, and
  it says so.
- **Inputs read in place, read-only.** The dump's directory and the snapshot's parent are
  mounted read-only into the build stack (`DOCKER_UNLOADS_MODE=:ro`), and the gradle tasks are
  told the exact files (`z build load-db --dump`, `load-solr --snapshot`, i.e. `loaddb -DB=` and
  `getLatestSolrIndex -DsolrDump=`). Nothing is copied, `/research` works as an input, and a dump
  landing in that directory mid-build -- or before a `--resume` -- cannot change what is loaded.
  Without `--db`/`--solr` the build reads the newest in the configured unloads, pinned the same way.
- **Resumable.** `state.json` in the same directory records each finished phase; a failure
  leaves the stack and its volumes up and prints how to inspect them and how to `--resume`.
- **Provenance.** The seed's `seed.json` gains `built_from`: the dump's name and SHA-256, the
  snapshot, the ref and the commit whose code built the app tier.
- **The db platform comes from the checkout's `docker/.env`.** The build stack's env is a
  copy of it, so the seed's data directory is written by whatever db image that file names.
  An arm64 Mac set up like the VMs has `DOCKER_ARCH=-arm64` with `DOCKER_DB_ARCH=` (empty: the
  bare, amd64 tag) and `DOCKER_DB_PLATFORM=linux/amd64` -- the db alone runs amd64, so its seeds
  (and every stack's data) match the Linux VMs and move between them. `z env show` lists the
  three keys; `z env set` changes them. Postgres then runs under Rosetta -- measured with pgbench
  at 1.25x slower for bulk load and 1.7x for index builds -- while compile and tomcat stay
  native. `--db-platform linux/amd64|linux/arm64` overrides it for one build; a seed whose
  platform differs from a host's db image is refused there at restore.
- **This host's images.** `configure` pulls only images the host lacks (`--pull-missing`).
  Image tags are host-wide, so a plain pull would replace a locally built image for every
  stack here; the stacks restored from the seed run this host's images anyway. `--pull`
  forces it, `--build` builds them.

### The app tier, and why every volume restores the same way

A seed captures the **deployed** app volumes alongside the data, so a feature's `tomcat` and
`httpd` come up already serving rather than needing a 10-20 minute first build. `--app` is on by
default for exactly that reason; `--no-app` skips it.

This matters more than convenience, because Docker's implicit image-to-volume seeding could never
have done it. That mechanism is **first-mounter-wins** and copies from *that* container's image —
and these volumes are shared by many services at the same path:

| volume | mount | services |
|---|---|---|
| `www_data` | `/opt/zfin/www_homes/zfin.org` (TARGETROOT) | compile, db, httpd, certbot, tomcat, tomcatdebug, jenkins |
| `catalina_base` | `/opt/zfin/catalina_bases/zfin.org` | compile, tomcat, tomcatdebug, jenkins, filebeat |
| `keystore` | `/opt/apache/apache-tomcat/conf` | compile, tomcat, tomcatdebug |
| `tls_certs` | `/opt/zfin/tls` | compile, httpd |

For a shared volume the seeder is ambiguous and usually has nothing at that path, so it seeds
empty. Restoring from tarballs sidesteps the question entirely: `z feature new` creates each
volume and extracts into it *before* `up`, so every sharing container sees the same deployed app.
Once db and solr restore the same way, one mechanism covers all of them — and the images that
used to carry db/solr stop being a special case.

**A seed with no app tier still works, but costs you the first build.** Capturing from a
data-only project (`zfin_shared`, say) yields empty `www_data`, and httpd then dies at startup
with an Apache config error naming nothing useful — it includes
`$TARGETROOT/server_apps/apache/inc-redirect` and cannot find it. `z seed new` warns when it
sees this, and the fix is to capture from a stack that has been deployed.

**Version skew:** the captured deploy is the source branch's code at capture time. A feature boots
serving that, then `gradle dirtydeploy` overlays its own delta (and `gradle liquibasePostBuild`
any schema or Solr delta). It is a warm start, not the final state — re-capture as the base moves.

**Build caches (`--caches`):** the first `dirtydeploy` on a fresh feature otherwise re-downloads
every dependency into an empty `gradle_cache`/`maven_cache`. Capturing them makes the first deploy
fast too, at the cost of a larger seed — so it is opt-in, and restored regardless of `--up`, since
`compile` mounts them on demand rather than as part of the served stack.
A seed made without them can take them later, from any stack that has since built:
`z seed add-volumes <tag> --from <ticket>` captures that stack's caches into the seed and records
them in its manifest (run it with no arguments to be asked for each value).

**Integrity:** `seed.json` records each tarball's size and SHA-256. Every restore checks the
sizes of the tarballs it is about to use and refuses on a mismatch, which catches a copy cut
short. `z seed verify <tag>` re-hashes them all; run it after copying a seed to a new disk or
share, since it reads every byte. A tarball the manifest does not list is restored with a note
that it was not checked.

**Base branch:** `seed.json` may record `base_branch`, the branch whose schema and data the seed
holds. `z seed new --from` takes it from the source stack's checkout, `z seed new` from `--ref`
(or the checked-out branch) -- for a frozen stack, the frozen branch; `--base-branch B` sets it on
any of them. `z seed ls` shows it, and `z feature new` prints a note -- never a refusal -- when
the new feature neither is that branch nor starts from it: the stack gets that branch's tables,
and `--liquibase` applies only this branch's deltas on top.

### Live or frozen: `z seed new --from` takes either

`--from` looks at the stack and takes each part from where it is. **Live volumes** are captured
as above (db+solr stopped, WAL trimmed, started again). A **frozen** stack is not thawed: its
freeze archive is already a seed's shape -- one tarball per volume, written by the same capture
-- so the tarballs are **hard-linked** into the seed, which is instant and takes no extra disk;
only seed.json is new (hashed afresh, the postgres major read from the archive). Thawing or
removing the stack later drops only its own links. `--copy` copies instead, as does an archive
on a different filesystem from the seeds.

```bash
z seed new --from zfin-10562                   # frozen: linked from its archive, seconds
z seed new --from zfin-10562 --tag fish-fix --base-branch main
```

The flags mean the same either way: the app tier unless `--no-app`, the build caches only with
`--caches`. A frozen stack's seed carries **that stack's** data -- its schema changes and test
rows -- and its `base_branch` defaults to the frozen branch.

**`--shared-db` features** have no db or solr of their own. `--with-shared` (or yes at the
prompt) puts a copy of the shared stack's in the seed **as the seed's own**, taken the same way:

- **shared stack frozen** (`z shared freeze`): its tarballs are hard-linked too;
- **shared stack live:** its db and solr are stopped, the WAL trimmed (pg_resetwal, which also
  refuses an unclean shutdown), captured, and started again. Every stack running on them loses
  its db connections meanwhile -- the feature itself included -- so it asks first;
  `--stop-sharers` authorises it without a terminal. `z restart tomcat` in any that serve 500s.

Any mix works: a frozen feature on a live shared stack, a live one on a frozen shared stack. The
shared data is as it is **now** (or as of the shared freeze), with every sharer's writes in it,
not as of when the feature froze; `built_from.shared_data` in seed.json records which.

### Copying seeds between hosts (`z seed push` / `z seed pull`)

```bash
z seed push 2026-10-09 is-zfin-dkr-p02        # this host's seed -> that host
z seed pull is-zfin-dkr-p02 zfin-10562-2026-10-26   # that host's seed -> this host
```

Source first, destination second, like `scp`; both run from one side, because ssh often
reaches only one way (a Mac reaches a VM, not the reverse). The host is anything ssh takes.
It is rsync over ssh into `<seeds>/.incoming-<tag>/`, a SHA-256 check of every tarball against
seed.json at the destination, then a rename to `<seeds>/<tag>/` -- so a seed appears under its
name only once it has arrived whole. A transfer that stops leaves `.incoming-<tag>` (`z seed ls`
lists it); the same command again resumes it. The far side needs ssh, rsync and `sha256sum`
(or `shasum`), not `z`. Its seeds directory defaults to the same path as this host's; give
`--remote-dir /abs/path` when it differs. An existing seed of that tag is replaced only with
`--force`. `z feature new` on the far side still checks the seed's postgres major and platform
against that host's db image.

---

## Sharing db+solr across features (`--shared-db`)

Read-mostly features (UI/JSP/React work, browsing) don't each need their own ~19-32G db +
~9G solr copy. `--shared-db` points them at one shared copy:

```bash
z shared up                             # restore a seed into db+solr ONCE -- one copy for all sharers
z feature new ZFIN-1 --shared-db --up
z feature new ZFIN-2 --shared-db --up   # both reach the SAME db/solr
z shared status                         # what's attached;  z shared down [--rm-data] to stop
```

How it works: one shared stack runs db+solr, from the base compose file plus
`docker-compose.overlay-shared.yml`: no published ports, Solr at laptop sizing (8g limit, 4g
heap, unless the main checkout's `docker/.env` says otherwise). A `--shared-db` feature
uses `docker-compose.overlay-shared-db.yml`, which
profile-suppresses its own db/solr so no per-feature copy is seeded. To reach the shared
data, `z feature new --shared-db` (and `z up`) **connect the shared db/solr containers into
the feature's own default network** with aliases `db`/`solr`, so the webapp's `db`/`solr`
hostnames resolve to them. The feature's app tier stays *single-homed*, deliberately:
attaching it to the shared stack's network instead would multi-home the tomcat container, which
is fatal — catalina sets `-Djava.rmi.server.hostname=$(container ip)`, and with two networks
that expands to two IPs, the second leaking in as a bare java arg (`Could not find or load
main class 172.x`). Postgres/solr don't care about being on several networks, so we attach
*them* to each feature's network instead.

**Which stack, and why it is labelled.** The shared stack's Compose project is the
`ZFIN_SHARED_PROJECT` setting, default `zfin_shared`. Project names are global to the Docker
host, so two dev trees on one host should each set their own in their `zfin-dev.env`
(`z config set ZFIN_SHARED_PROJECT=<name>`), or they would share one stack. The same setting can
instead point features at a real instance's data. To tell the two apart, the shared overlay
labels its db and solr `zfin.devstack.shared-data=true` (and `z shared up|thaw` label the volumes
they restore, so a stack with data but no containers is still recognised): `z shared up|down|freeze|thaw` refuse a
project whose db lacks it -- a real instance, or a shared stack another tree or older tooling
runs -- and `z feature new --shared-db` warns that it is about to write a live database.

**The hard constraint — shared data == shared writes.** All sharers read/write the *same*
`zfindb` and solr index, so a `liquibasePostBuild` migration, a reindex, or a curation edit
by one feature is instantly visible to (and can break) the others. There is no cheap
write-isolation (a per-feature `DATABASE`/`TEMPLATE` is a full copy, which negates the
saving). So `--shared-db` is for **read-mostly** parallel work; a feature that needs its own
schema/writes should be a normal per-feature-copy stack.

## Listing + teardown

```bash
z feature ls                      # active feature stacks: project, branch, data (own/shared), up/down, url
z feature ls -v                   # ...plus frozen ones (-v = --all; --frozen for just those)
z feature rm <ticket>             # tear one down (prompts; --force to skip)
```

`z feature rm` automates the full teardown: `docker compose down -v` (containers + per-feature
volumes/copies), `git worktree remove --force`, `git branch -D` (skipped for a branch that
predates the stack, i.e. one provisioned with `--existing-branch`), the freeze archive if one
exists, and
(macOS) `ifconfig lo0 -alias <ip>` — deriving the project/ip/host/compose-files from the
worktree's `docker/.env`. It's **destructive** (discards the stack's copies *and* any
uncommitted work), so do it only once the feature's PR is merged; it prompts for confirmation
unless `--force` (and refuses without a TTY).

The equivalent by hand, if you prefer (or `new-feature` prints these at the end of provisioning):

```bash
docker compose down -v            # containers + network + per-feature volumes
deactivate
git worktree remove <worktree>    # add --force if dirty
sudo ifconfig lo0 -alias <ip>     # (macOS only) drop the loopback alias
```

---

## Platform notes

- **Architecture:** a seed carries data, not an engine, so it is far more portable than a baked
  image was. The server comes from this host's `zfin-db` image, and `seed.json` records the
  postgres major so a restore into an incompatible engine is refused rather than discovered when
  postgres will not start. `platform` in `seed.json` and `freeze.json` is the platform of the
  **db image that wrote the data**, not the Docker host's: an arm64 Mac running the amd64 image
  under Rosetta writes an amd64 data directory, and the restore check compares it with this
  host's db image (`zfin-db:<release>${DOCKER_DB_ARCH}`, falling back to `${DOCKER_ARCH}` when
  `DOCKER_DB_ARCH` is not in the `.env`). Moving a seed between arm64 and amd64 works in practice — both are
  little-endian with the same alignment, and the database runs in the same Debian container on
  both, so the usual collation hazard does not apply — but it is not something postgres
  guarantees, so verify with a query that uses a text index.
- **Ports:** stacks publish an offset on one address (`https 8443+N`, `http 8080+N`,
  `db 5432+N`, `tomcatdebug 5000+N`, `jenkins 9499+N`), so nothing needs `ifconfig lo0 alias` on
  macOS. The offset skips any port in those ranges already published on the host.
- **Memory:** a feature's solr heap grows on demand: it starts at `1g`, can reach `8g`, and
  gives memory back within about two minutes of going idle, inside a `12g` container limit
  (docker-compose.overlay-feature.yml). An idle stack costs about 1g; a full reindex (the
  Index-Faceted-Search jobs) gets the heap its DIH caches need. A fixed 4g heap ran out on
  the `expression` import, and Solr 9 then aborts itself (exit 134; `jvm_crash_<pid>.log` in
  `/var/solr/logs`). Override per stack in its `.env` with `DOCKER_SOLR_HEAP_MIN`,
  `DOCKER_SOLR_HEAP_MAX` and `DOCKER_SOLR_MEM_MAX`; the older `DOCKER_SOLR_HEAP` /
  `DOCKER_SOLR_MEM_LIMIT` keys do nothing under the feature overlay. The base stack keeps prod
  sizing (`12g`/`16g`). Two stacks reindexing at once can still squeeze a small host.
- **Ownership (Linux):** things the `compile` container writes into a worktree — `node_modules`,
  `build/` — belong to uid 1000 on the host too, so a developer with a different uid cannot
  delete them. `z feature rm` reclaims them in a root container first. Docker Desktop maps
  ownership, so this never arises on macOS.

---

## Data-sensitivity guardrail

Seeds carry a **real loaded ZFIN database**. They live on storage you control
(`$ZFIN_SEEDS_DIR`), and there is deliberately **no upload path**: keeping the data
off any registry is structural, not a policy someone has to remember. Do not add one.
`z seed push`/`pull` are not one: they copy a seed over ssh between hosts you can already log
in to, into the seeds directory there -- storage you control at both ends.

Freeze archives and sidecar session archives (`$ZFIN_ARCHIVE_DIR`) carry the same expectation.
Putting either on NFS is supported and encouraged; putting it anywhere
publicly reachable is not.

---

## Related docs

- [dev-stacks-by-example.md](dev-stacks-by-example.md) — every command above, shown as a terminal session
- ZFIN repo: [build-and-docker.md](https://github.com/ZFIN/zfin/blob/main/reference/build-and-docker.md) — Docker services, image layering, deploy pipeline
- ZFIN repo: [deploying-changes.md](https://github.com/ZFIN/zfin/blob/main/reference/deploying-changes.md) — what to run after editing X
