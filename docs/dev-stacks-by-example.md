# Dev stacks, by example

What the dev-stack tooling does, shown as terminal sessions rather than described. Each
capture is constructed from what the code prints; the prose version is
[dev-stacks.md](dev-stacks.md) and [dev-tree-layout.md](dev-tree-layout.md).

Sizes, timings, container IDs and commit hashes in these captures are illustrative. `...`
marks elided output.

---

## 1. The tree, and the front door

```console
14:47:47 ryan@mac:.../source_roots/stacks$ pwd
/opt/zfin/source_roots/stacks
14:47:48 ryan@mac:.../source_roots/stacks$ tree -L 2
.
├── archive
│   ├── seeds
│   └── sessions
├── base
│   ├── ...
│   ├── docker
│   ├── source
│   ├── ...
│   └── z
├── cache
├── mounts
│   ├── blast
│   ├── downloads
│   ├── gff3
│   ├── hh_atlas
│   ├── loadUp
│   ├── research
│   └── unloads
└── worktrees
    ├── zfin-10453
    ├── zfin-10464
    ├── zfin-10485
    └── zfin-10510

26 directories, 1 file
14:47:52 ryan@mac:.../source_roots/stacks$ grep ZFIN_DEV_ROOT base/docker/.env
ZFIN_DEV_ROOT=/opt/zfin/source_roots/stacks
```

```console
14:48:10 ryan@mac:.../stacks/base$ z help
z -- ZFIN dev-stack tooling. Run it as `z <cmd>` from a checkout or worktree.

Stack ops (act on the stack that owns the working directory):
  z run  [svc] [args]   run a command in a fresh container (default svc: compile)
                        e.g. z run -c "gradle dirtydeploy"   (== zrun -c "...")
  z exec [svc] [args]   exec into the running container (default: compile)
  z up   [svc...]       start service(s)            (default: all)
  z stop [svc...]       stop service(s), keeping containers + data
  z down [svc...]       remove containers + network (-v also discards this stack's data)
  z pull [svc...]       pull image(s)
  z log  [svc...]       tail logs
  z restart [svc...]    restart service(s)
  z status [-v]         the active stack: url, dir, branch, links, what is running
                        (-v adds ports, compose/env files, and the container table)

Feature ops:
  z feature new [<ticket>] [opts]   provision a feature stack (prompts for the plan; -y
                                    takes the defaults)
  z feature ls                      list feature stacks (project, branch, data, up/down, url)
  z feature rm <ticket> [--force]   tear down a feature (down -v + worktree/branch/hosts)
  z feature refresh <t>|--all       backfill .env keys a stack was made too early to have
  z feature freeze <ticket> [opts]  park it: archive its volumes, down -v, keep the worktree
  z feature thaw <ticket> [opts]    bring a frozen stack back exactly as it was
  z feature session export|ls       archive the sidecar's session history (outlives `rm`)

CI / bootstrap (no activation needed):
  z build <phase...> [--build --test]   hands-free build/deploy (configure|load-db|
                                        load-solr|deploy-jenkins|deploy|all)
  z scaffold [--root DIR]               create the recommended dev-tree layout (see
                                        docs/dev-tree-layout.md)
  z fresh-install [--dry-run]           guided day-zero setup on a bare workstation

Seeds (a loaded stack's volumes, captured once and restored into many):
  z seed new|create [--from P] [--tag T] capture db+solr (+ app tier) from a loaded stack
  z seed build [--db F] [--solr D] [--tag T]
                                         build a seed from a dump: load + deploy in a
                                         throwaway stack, capture it, tear it down
  z shell-init [bash|zsh]                shell lines to put `z` on PATH + enable completion
  z seed ls / z seed rm <tag>            list / delete seeds

Shared data stack (read-mostly features share ONE db+solr copy):
  z shared up|down|status [--tag T]     run/stop the shared db+solr (project zfin_shared);
                                        attach features with `z feature new --shared-db`
  z shared freeze [--stop-sharers]      park the shared db+solr (refuses while sharers run)
  z shared thaw                         restore them and start the shared stack

  z help                                this help

stack here: zfin_org
```

### Putting `z` on PATH

```console
14:49:02 ryan@mac:.../stacks$ zfin-build-orchestrator/z shell-init
# ZFIN dev-stack tooling. These lines are shell -- run them, or keep them:
#
#   eval "$(/opt/zfin/source_roots/stacks/zfin-build-orchestrator/z shell-init)"        # this shell only
#   /opt/zfin/source_roots/stacks/zfin-build-orchestrator/z shell-init >> ~/.bashrc     # and every shell after
#
# `z` then works from any directory. There is nothing to activate per stack: it asks
# git which checkout owns your working directory, so one copy on PATH serves every
# checkout and worktree.

# Prepended only if absent, so this is safe to eval twice and safe in an rc file that
# something else also sources. POSIX `case`, so bash and zsh behave the same.
case ":$PATH:" in *":/opt/zfin/source_roots/stacks/zfin-build-orchestrator:"*) ;; *) export PATH="/opt/zfin/source_roots/stacks/zfin-build-orchestrator:$PATH" ;; esac

# Tab completion: subcommands, service names, and each subcommand's flags.
source "/opt/zfin/source_roots/stacks/zfin-build-orchestrator/lib/z-completion.bash"
14:49:10 ryan@mac:.../stacks$ zfin-build-orchestrator/z shell-init >> ~/.bashrc && source ~/.bashrc
14:49:12 ryan@mac:.../stacks/base$ type z
z is /opt/zfin/source_roots/stacks/zfin-build-orchestrator/z
14:49:15 ryan@mac:.../stacks/base$ z <TAB><TAB>
build          exec           help           pull           run            seed           shell-init     stop
down           feature        log            restart        scaffold       shared         status         up
fresh-install
14:49:18 ryan@mac:.../stacks/base$ z feature <TAB><TAB>
freeze   ls       new      refresh  rm       session  thaw
14:49:20 ryan@mac:.../stacks/base$ z build <TAB><TAB>
--build         --pull-missing  --test          all             configure       deploy          deploy-jenkins  load-db         load-solr
```

### A new machine: `z scaffold`

```console
09:02:11 ryan@laptop:~/zfin-dev/coral$ z feature ls
!! ZFIN_DEV_ROOT is not set.
   It is the parent directory holding the repo, worktrees, archives,
   caches and mounted data. Set it in docker/.env, e.g.
     ZFIN_DEV_ROOT=/Users/ryan/zfin-dev
   See docs/dev-tree-layout.md for the recommended structure.
09:02:30 ryan@laptop:~/zfin-dev/coral$ z scaffold --root ~/zfin-dev
>> dev tree root: /Users/ryan/zfin-dev
  created  worktrees
  created  archive
  created  archive/sessions
  created  cache
  created  mounts
  created  mounts/unloads
  created  mounts/unloads/db
  created  mounts/unloads/solr
  created  mounts/research
  created  mounts/blast
  created  mounts/downloads
  created  mounts/loadUp
  created  mounts/gff3
  created  mounts/hh_atlas

>> add this to /Users/ryan/zfin-dev/coral/docker/.env to make the tree findable:
     ZFIN_DEV_ROOT=/Users/ryan/zfin-dev

>> mounts/ is empty scaffolding. Point the DOCKER_*_PATH vars at it in docker/.env,
>> or leave them at whatever this host already uses -- those paths are often shared
>> with other tooling rather than owned by this tree. See docs/dev-tree-layout.md.
09:02:41 ryan@laptop:~/zfin-dev/coral$ echo 'ZFIN_DEV_ROOT=/Users/ryan/zfin-dev' >> docker/.env
09:02:44 ryan@laptop:~/zfin-dev/coral$ z scaffold
>> dev tree root: /Users/ryan/zfin-dev
  exists        worktrees
  ...
  exists        mounts/hh_atlas
>> nothing to do -- the tree is already in place

>> docker/.env already points here (ZFIN_DEV_ROOT=/Users/ryan/zfin-dev)

>> mounts/ is empty scaffolding. Point the DOCKER_*_PATH vars at it in docker/.env,
...
```

---

## 2. How a command finds its stack

```console
14:50:01 ryan@mac:.../stacks/base$ z status
>> targeting 'zfin_org' (base)
stack: zfin_org
  url      : https://zfin.org
  dir      : /opt/zfin/source_roots/stacks/base
  branch   : main
  jira     : https://zfin.atlassian.net/browse/main
  pr       : https://github.com/rtaylorzfin/zfin/pull/new/main
  running  : db httpd solr tomcat

14:50:05 ryan@mac:.../stacks/base$ cd source/org/zfin/marker && z status | head -2
>> targeting 'zfin_org' (base)
stack: zfin_org
  url      : https://zfin.org
14:50:09 ryan@mac:.../org/zfin/marker$ cd /tmp && z status
stack: none here -- cd into a checkout or feature worktree
14:50:12 ryan@mac:/tmp$ z run -c "gradle dirtydeploy"
!! z run: no stack found. Run this from inside a checkout or feature worktree
   whose docker/.env names a COMPOSE_PROJECT_NAME -- that file is what makes a
   directory a stack.
```

```console
# `z` belongs on the host. Inside the compile container it refuses rather than half-working.
14:50:30 ryan@mac:.../stacks/base$ z run
>> targeting 'zfin_org' (base)
gradle@3f1c0d9e2a7b:/opt/zfin/source_roots/zfin.org$ z status
!! z runs on the HOST, not inside a container (found /.dockerenv).
   Inside the compile container you already have the build tools -- run gradle/ant directly:
     gradle dirtydeploy
   Stack ops (z run/up/down/...) belong on the host, where the paths z resolves and the docker
   daemon agree. If this really is a containerized CI agent with the socket mounted, opt in:
     ZFIN_Z_IN_CONTAINER=1 z status ...
```

---

## 3. How a stack is reached

```console
# The repo runs no proxy. Every feature stack publishes httpd on its own port, 8443+N for its
# offset N, on ZFIN_FEATURE_BIND. Nothing to set up; the URL is the port.
14:52:00 ryan@mac:.../stacks/base$ grep -E '^ZFIN_(FEATURE|PROXY)' docker/.env
ZFIN_FEATURE_DOMAIN=zfin.test
ZFIN_FEATURE_BIND=127.0.0.1
14:52:05 ryan@mac:.../stacks/base$ curl -sk -o /dev/null -w '%{http_code}\n' https://127.0.0.1:8448/
200
```

```console
# An nginx-proxy that already runs on this host can route stacks by name instead. Name its
# network; each stack made from then on joins it and advertises <slug>.<ZFIN_FEATURE_DOMAIN>.
14:53:00 ryan@mac:.../stacks/base$ echo 'ZFIN_PROXY_NETWORK=ngproxy_net' >> docker/.env
14:53:04 ryan@mac:.../stacks/base$ z feature new zfin-3001 -y --up
>> seed: 2026-09-29
!! ZFIN_PROXY_NETWORK=ngproxy_net, but there is no such Docker network.
   Start the proxy that owns it, or unset ZFIN_PROXY_NETWORK in docker/.env to
   reach stacks on their published ports only. Nothing was created.
14:53:30 ryan@mac:.../stacks/base$ docker network ls --filter name=ngproxy_net --format '{{.Name}}'
ngproxy_net
14:53:40 ryan@mac:.../stacks/base$ z feature new zfin-3001 -y --up
>> seed: 2026-09-29
...
>> allocated port offset +9  (skipped in use: 1, 2, 3, 4, 5, 6, 7, 8)
>> reach it at: https://127.0.0.1:8452  and https://zfin-3001.zfin.test via the proxy on network 'ngproxy_net'
...
>> docker compose --project-name zfin-3001 ... -f .../docker-compose.overlay-feature.yml -f .../docker-compose.overlay-proxy-network.yml up -d db solr tomcat httpd
...
>> provisioned zfin-3001
     worktree : /opt/zfin/source_roots/stacks/worktrees/zfin-3001
     branch   : zfin-3001  (off main)
     project  : zfin-3001
     url      : https://zfin-3001.zfin.test   (direct: https://127.0.0.1:8452)
     ports    : https 127.0.0.1:8452   http 127.0.0.1:8089   db 127.0.0.1:5441   debug 127.0.0.1:5009   jenkins 127.0.0.1:9508
...
14:57:10 ryan@mac:.../stacks/base$ docker inspect zfin-3001-httpd-1 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep ^VIRTUAL_
VIRTUAL_HOST=zfin-3001.zfin.test
VIRTUAL_PORT=443
VIRTUAL_PROTO=https
14:57:20 ryan@mac:.../stacks/base$ curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: zfin-3001.zfin.test' http://127.0.0.1/
200
14:57:24 ryan@mac:.../stacks/base$ curl -sk -o /dev/null -w '%{http_code}\n' https://127.0.0.1:8452/
200
# A stack made without ZFIN_PROXY_NETWORK advertises nothing, so the proxy has no route for it.
14:57:30 ryan@mac:.../stacks/base$ curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: zfin-12345.zfin.test' http://127.0.0.1/
503
14:57:40 ryan@mac:.../stacks/base$ cd ../worktrees/zfin-3001 && z status | head -4
>> targeting 'zfin-3001' (zfin-3001)
stack: zfin-3001
  url      : https://zfin-3001.zfin.test
  direct   : https://127.0.0.1:8452
```

---

## 4. Once per host: a seed

```console
# Captured from a stack that has been DEPLOYED, so new stacks come up already serving.
# `z seed create` resolves the stack from the working directory like any stack op; --from overrides.
09:13:20 ryan@mac:.../stacks/base$ z seed create --from zfin-10510 --caches
>> targeting 'zfin_org' (base)
>> seed create: from=zfin-10510 release=main tag=2026-09-29 -> /opt/zfin/source_roots/stacks/archive/seeds/2026-09-29
>> stopping db (a41c9e07b2d3) for a consistent capture
a41c9e07b2d3
>> stopping solr (6be2f0c8d915) for a consistent capture
6be2f0c8d915
>> [trim] throwaway postgres on zfin-10510_pg_data (WAL reset)
0c3f7a9e...
seed-trim-48213
>> [trim] pg_resetwal to shed recycled WAL segments (safe after the clean shutdown above)
Write-ahead log reset
>> capturing zfin-10510_pg_data -> /opt/zfin/source_roots/stacks/archive/seeds/2026-09-29/pg_data.tgz (pigz -1)
>> capturing zfin-10510_solr_var -> /opt/zfin/source_roots/stacks/archive/seeds/2026-09-29/solr_var.tgz (pigz -1)
>> capturing zfin-10510_www_data -> /opt/zfin/source_roots/stacks/archive/seeds/2026-09-29/www_data.tgz (pigz -1)
>> capturing zfin-10510_catalina_base -> /opt/zfin/source_roots/stacks/archive/seeds/2026-09-29/catalina_base.tgz (pigz -1)
>> capturing zfin-10510_keystore -> /opt/zfin/source_roots/stacks/archive/seeds/2026-09-29/keystore.tgz (pigz -1)
>> capturing zfin-10510_tls_certs -> /opt/zfin/source_roots/stacks/archive/seeds/2026-09-29/tls_certs.tgz (pigz -1)
>> capturing zfin-10510_jenkins_data -> /opt/zfin/source_roots/stacks/archive/seeds/2026-09-29/jenkins_data.tgz (pigz -1)
>> capturing zfin-10510_gradle_cache -> /opt/zfin/source_roots/stacks/archive/seeds/2026-09-29/gradle_cache.tgz (pigz -1)
>> capturing zfin-10510_maven_cache -> /opt/zfin/source_roots/stacks/archive/seeds/2026-09-29/maven_cache.tgz (pigz -1)
>> capturing zfin-10510_npm_cache -> /opt/zfin/source_roots/stacks/archive/seeds/2026-09-29/npm_cache.tgz (pigz -1)
>> restarting db
a41c9e07b2d3
>> restarting solr
6be2f0c8d915
>> seed '2026-09-29' written: 8.9G across 10 volume(s) -> /opt/zfin/source_roots/stacks/archive/seeds/2026-09-29
>> use it with:  z feature new <ticket> --seed 2026-09-29

  seed '2026-09-29' timing
    stop data tier         11.2s     4%
    trim WAL               38.6s    14%
    capture volumes       191.3s    70%
    restart source          1.4s     1%
    checksum + manifest    31.7s    12%
    TOTAL                 274.2s
    per volume:
      pg_data             71.4s      3558 MB      50 MB/s
      solr_var            58.2s      3175 MB      55 MB/s
      gradle_cache        24.9s      1210 MB      49 MB/s
      npm_cache           12.2s       336 MB      28 MB/s
      maven_cache          9.8s       402 MB      41 MB/s
      catalina_base        6.1s       275 MB      45 MB/s
      jenkins_data         3.6s       118 MB      33 MB/s
      www_data             2.3s        52 MB      23 MB/s
      keystore             0.9s         0 MB       0 MB/s
      tls_certs            0.8s         0 MB       0 MB/s
```

```console
09:18:40 ryan@mac:.../stacks/base$ z seed ls
>> targeting 'zfin_org' (base)
TAG                    SIZE  CREATED             VOLUMES
2026-09-19             6.8G  2026-09-19 13:27:25 pg_data solr_var www_data catalina_base keystore tls_certs
2026-09-24             7.2G  2026-09-24 08:19:49 pg_data solr_var www_data catalina_base keystore tls_certs jenkins_data
2026-09-29             8.9G  2026-09-29 09:18:02 pg_data solr_var www_data catalina_base keystore tls_certs jenkins_data gradle_cache maven_cache npm_cache
mactest                6.5G  2026-09-18 14:39:32 pg_data solr_var www_data

in /opt/zfin/source_roots/stacks/archive/seeds -- use one with:  z feature new <ticket> --seed <tag>
09:18:52 ryan@mac:.../stacks/base$ z seed rm mactest
>> targeting 'zfin_org' (base)
delete seed 'mactest' (6.5G) from /opt/zfin/source_roots/stacks/archive/seeds/mactest? [y/N]: y
>> deleted seed 'mactest' (6.5G)
09:19:03 ryan@mac:.../stacks/base$ z seed create --tag 2026-09-24
>> targeting 'zfin_org' (base)
>> seed create: from=zfin_org release=main tag=2026-09-24 -> /opt/zfin/source_roots/stacks/archive/seeds/2026-09-24
!! seed '2026-09-24' already exists at /opt/zfin/source_roots/stacks/archive/seeds/2026-09-24
   Pick another --tag, or remove it:  z seed rm 2026-09-24
09:19:20 ryan@mac:.../stacks/base$ head -20 ../archive/seeds/2026-09-29/seed.json
{
    "tag": "2026-09-29",
    "created": "2026-09-29 09:18:02",
    "source_project": "zfin-10510",
    "release": "main",
    "pg_major": "18",
    "platform": "linux/arm64",
    "volumes": [
        {
            "name": "pg_data",
            "file": "pg_data.tgz",
            "bytes": 3730668740,
            "sha256": "3959fea6b76b119edbbd5e10e2756f739e72f5d17c8f227403fbed86cd55149d"
        },
        {
            "name": "solr_var",
            "file": "solr_var.tgz",
            "bytes": 3329225154,
            "sha256": "e0bacc08e74027d8d2074a216c77646141c8857328555335682dd2cb8445b772"
        },
```

```console
# Captured from a data-only project: the seed works, but its stacks cannot serve until built.
09:40:11 ryan@mac:.../stacks/base$ z seed create --from zfin_shared --tag shared-only
>> targeting 'zfin_org' (base)
>> seed create: from=zfin_shared release=main tag=shared-only -> /opt/zfin/source_roots/stacks/archive/seeds/shared-only
>> note: --app skipping absent volumes: www_data, catalina_base, keystore, tls_certs
...
>> seed 'shared-only' written: 6.6G across 2 volume(s) -> /opt/zfin/source_roots/stacks/archive/seeds/shared-only
>> use it with:  z feature new <ticket> --seed shared-only
!! this seed has no usable app tier (www_data, catalina_base empty or absent).
   Stacks made from it come up with db+solr only: httpd cannot start until
   $TARGETROOT is populated, and fails with an Apache config error that does
   not mention the cause. They need the full first build:
     z run -c "ant do && gradle make && ant deploy-catalina-base && ant deploy-no-tests-no-restart"
   To avoid that, capture from a stack that has been DEPLOYED -- an instance, or
   a feature stack you have built -- rather than from a data-only project.
...
```

### A seed from a dump: `z seed build`

```console
# Everything from a .bak and a snapshot, in a throwaway stack, inside tmux so it outlives the terminal.
18:02:00 ryan@mac:.../stacks/base$ z seed build --db ~/dumps/2026.09.28.1/zfindb.bak --solr ~/dumps/snapshot.2026.09.28-03.00 --tmux
>> seed build running in tmux session 'seedbuild-2026-09-29' (Ctrl-b d to detach; it keeps running)
```

```console
# ...inside tmux session 'seedbuild-2026-09-29'
>> seed build '2026-09-29'  project=seedbuild-2026-09-29  code=HEAD @ 15984f0b5d
>>   db   : /Users/ryan/dumps/2026.09.28.1/zfindb.bak
>>   solr : /Users/ryan/dumps/snapshot.2026.09.28-03.00
>>   both read in place, mounted read-only
>> checksumming zfindb.bak (recorded in the seed's manifest)
Preparing worktree (detached HEAD 15984f0b5d)
HEAD is now at 15984f0b5d ZFIN-10461: reproduce Load-NCBI-GFF3-File's real DB effects without the job (#2012)
>> instance: feature   env: /opt/zfin/source_roots/stacks/cache/seed-build/2026-09-29/stack.env

>> seed build [1/6] configure
>> stack=seedbuild-2026-09-29  phases=configure
>> configure: pull stock images this host does not have (--pull-missing)
...
>> done: configure

>> seed build [2/6] load-db
>> stack=seedbuild-2026-09-29  phases=load-db
>> load-db: up db + loaddb/make/liquibase
...
>> load-db [1/4]: gradle loaddb -DB='/opt/zfin/unloads/db/zfindb.bak'
Loading /opt/zfin/unloads/db/zfindb.bak into zfindb
...
>> load-db [4/4] ok (13.9s)
>> done: load-db

>> seed build [3/6] load-solr
...
Restoring Solr snapshot.2026.09.28-03.00 from /opt/zfin/unloads/solr
INFO  SolrAdminClient - restore triggered: /opt/zfin/unloads/solr/snapshot.2026.09.28-03.00
INFO  SolrAdminClient - restore complete: /opt/zfin/unloads/solr/snapshot.2026.09.28-03.00
...
>> seed build [4/6] deploy-jenkins
...
>> seed build [5/6] deploy
>> stack=seedbuild-2026-09-29  phases=deploy
>> deploy: build WAR, deploy catalina-base + app, (re)start app tier
...
>> deploy [1/3]: gradle make
...
BUILD FAILED in 4m 12s
!! deploy failed at step 1 of 3: gradle make
   Earlier steps in this phase succeeded. Re-run just this one with:
     z run -c "gradle make"

!! seed build '2026-09-29' stopped during deploy.
   The build stack is left as it was, so you can look at it, e.g.:
     COMPOSE_PROJECT_NAME=seedbuild-2026-09-29 COMPOSE_FILE=/opt/zfin/source_roots/stacks/base/docker/docker-compose.yml COMPOSE_ENV_FILES=/opt/zfin/source_roots/stacks/cache/seed-build/2026-09-29/stack.env z log tomcat
   carry on from deploy:  z seed build --tag 2026-09-29 --resume
   or discard it:        z seed build --tag 2026-09-29 --clean
```

```console
# The database stays loaded; --resume starts at the phase that failed.
19:40:10 ryan@mac:.../stacks/base$ z seed build --tag 2026-09-29 --resume
>> resuming seed build '2026-09-29' at deploy  (done: configure, load-db, load-solr, deploy-jenkins)
>> [1/6] configure -- done in an earlier run, skipping
>> [2/6] load-db -- done in an earlier run, skipping
>> [3/6] load-solr -- done in an earlier run, skipping
>> [4/6] deploy-jenkins -- done in an earlier run, skipping

>> seed build [5/6] deploy
...
>> done: deploy

>> seed build [6/6] capture seed '2026-09-29'
>> seed create: from=seedbuild-2026-09-29 release=main tag=2026-09-29 -> /opt/zfin/source_roots/stacks/archive/seeds/2026-09-29
...
>> seed '2026-09-29' written: 7.2G across 7 volume(s) -> /opt/zfin/source_roots/stacks/archive/seeds/2026-09-29
>> use it with:  z feature new <ticket> --seed 2026-09-29
...
>> down -v the build stack 'seedbuild-2026-09-29'
[+] down 12/12
...
>> removing the build worktree /opt/zfin/source_roots/stacks/cache/seed-build/2026-09-29/seedbuild-2026-09-29
>> seed '2026-09-29' is ready:  z feature new <ticket> --seed 2026-09-29

  seed build '2026-09-29' timing
    prepare             0.4s     0%
    deploy            291.7s    58%
    capture           188.9s    38%
    teardown           21.3s     4%
    TOTAL             502.3s
19:48:40 ryan@mac:.../stacks/base$ grep -A7 built_from ../archive/seeds/2026-09-29/seed.json
    "built_from": {
        "how": "z seed build",
        "db_dump": "zfindb.bak",
        "db_dump_sha256": "5c1f0a7e9b2d4c86a3e1f07d92b64e5a8c3d1b9f06e7a4d2c5b8e1f3a9d7c604",
        "solr_snapshot": "snapshot.2026.09.28-03.00",
        "ref": "HEAD",
        "commit": "15984f0b5d2c4e8f9a1b3d5e7f6a8c0b2d4e6f81"
    }
```

```console
# A seed for the amd64 VMs, built on an arm64 Mac: only the db runs amd64 (under Rosetta).
20:05:00 ryan@mac:.../stacks/base$ z seed build --tag vm-2026-09-29 --db-platform linux/amd64 --tmux
...
>> seed build 'vm-2026-09-29'  project=seedbuild-vm-2026-09-29  code=HEAD @ 15984f0b5d  db=linux/amd64
...
21:31:10 ryan@mac:.../stacks/base$ grep '"platform"' ../archive/seeds/vm-2026-09-29/seed.json
    "platform": "linux/amd64",
21:31:20 ryan@mac:.../stacks/base$ z feature new zfin-2020 -y --seed vm-2026-09-29
!! seed 'vm-2026-09-29' holds a linux/amd64 PostgreSQL data directory; this host's db image is linux/arm64.
...
21:32:00 ryan@mac:.../stacks/base$ rsync -a ../archive/seeds/vm-2026-09-29 cell:/opt/zfin-dev/archive/seeds/
```

```console
19:50:00 ryan@mac:.../stacks/base$ z seed build --tag 2026-09-29
!! seed '2026-09-29' already exists at /opt/zfin/source_roots/stacks/archive/seeds/2026-09-29
   Pick another --tag, or remove it:  z seed rm 2026-09-29
19:50:12 ryan@mac:.../stacks/base$ z seed build --tag next --solr ~/dumps/v9
!! --solr /Users/ryan/dumps/v9: expected a Solr 9 snapshot.* directory (what `gradle getsolr` fetches)
```

### The checkout's own stack: `z seed restore`

```console
# A seed restores into whatever stack owns the working directory -- here the base checkout's.
09:30:00 ryan@mac:.../stacks/base$ z seed restore 2026-09-29 --app
>> targeting 'zfin_org' (base)
>> restore seed '2026-09-29' into 'zfin_org': pg_data, solr_var, www_data, catalina_base, keystore, tls_certs, jenkins_data
>>   [1/7] keystore             0 MB in   0.8s
>>   [2/7] tls_certs            0 MB in   0.9s
>>   [3/7] www_data            52 MB in   3.9s
>>   [4/7] jenkins_data       207 MB in   4.4s
>>   [5/7] catalina_base      192 MB in   5.6s
>>   [6/7] solr_var          3175 MB in  70.1s
>>   [7/7] pg_data           3506 MB in 109.9s
>> restored seed '2026-09-29' into 'zfin_org'
>> start it:  z up db solr tomcat httpd

  seed restore '2026-09-29' timing
    restore volumes   110.0s   100%
    TOTAL             110.0s
09:32:10 ryan@mac:.../stacks/base$ z up db solr tomcat httpd
>> targeting 'zfin_org' (base)
...
09:33:40 ryan@mac:.../stacks/base$ z seed restore 2026-09-29
>> targeting 'zfin_org' (base)
>> restore seed '2026-09-29' into 'zfin_org': pg_data, solr_var
!! these volumes already exist: zfin_org_pg_data, zfin_org_solr_var
   Restoring REPLACES them, discarding what is there. To do that:
     z down
     z seed restore 2026-09-29 --force
09:33:50 ryan@mac:.../stacks/base$ z seed restore 2026-09-29 --force
>> targeting 'zfin_org' (base)
>> restore seed '2026-09-29' into 'zfin_org': pg_data, solr_var
!! containers still use those volumes: zfin_org-db-1, zfin_org-solr-1
   z down first (it removes containers, keeps volumes), then re-run.
```

---

## 5. A feature stack, interactively

```console
14:40:02 ryan@mac:.../stacks/base$ z feature new zfin-12345
New feature stack -- press Enter to accept [defaults].
  base branch [main]  (. = dev-stacks-slim):
  seed [2026-09-29]  (none = cold stack):
  share the zfin_shared db+solr instead of your own copy (read-mostly)? [y/N]:
  bring the stack up now (db+solr+tomcat+httpd)? [Y/n]:
  run npm ci now (gradle npmInstall; once per worktree, needed before dirtydeploy)? [y/N]: y
  deploy this branch on top of the snapshot (gradle dirtydeploy)? [Y/n]:
  apply this branch's schema deltas (gradle liquibasePostBuild)? [y/N]:
  spawn a tmux session 'zfin-12345' in the worktree? [Y/n]:
>> seed: 2026-09-29
>> warm app tier: yes (from /opt/zfin/source_roots/stacks/archive/seeds/2026-09-29)
>> warm build caches: yes (gradle + maven + npm, from /opt/zfin/source_roots/stacks/archive/seeds/2026-09-29)
>> plan: worktree + branch  ->  per-feature .env  ->  restore volumes from seed  ->  npm ci  ->  start db solr tomcat httpd  ->  gradle dirtydeploy  ->  tmux session 'zfin-12345'
>> allocated port offset +5  (skipped in use: 1, 2, 3, 4)
>> reach it at: https://127.0.0.1:8448
>> feature=zfin-12345 project=zfin-12345 host=zfin-12345.zfin.test ports=+5 tag=2026-09-29 base=main

>> [1/7] worktree + branch
Preparing worktree (new branch 'zfin-12345')
Updating files: 100% (7481/7481), done.
HEAD is now at 15984f0b5d ZFIN-10461: reproduce Load-NCBI-GFF3-File's real DB effects without the job (#2012)

>> [2/7] per-feature .env
>> instance: feature (DOMAIN_NAME follows zfin-12345.zfin.test; dev mail stays caught)

>> [3/7] restore volumes
>>   [1/10] tls_certs            0 MB in   1.2s
>>   [2/10] keystore             0 MB in   1.3s
>>   [3/10] www_data            52 MB in   3.4s
>>   [4/10] jenkins_data       118 MB in   4.8s
>>   [5/10] catalina_base      275 MB in   7.9s
>>   [6/10] maven_cache        402 MB in  12.6s
>>   [7/10] npm_cache          336 MB in  18.4s
>>   [8/10] gradle_cache      1210 MB in  29.7s
>>   [9/10] solr_var          3175 MB in  61.3s
>>   [10/10] pg_data           3558 MB in  78.6s
>> restore total: 79.4s

>> [4/7] npm ci
>> installing node deps in compile (gradle npmInstall / npm ci) -- one-time for this worktree...
[+] create 1/1
 ✔ Network zfin-12345_default  Created     0.0s
> Task :npmInstall
...
BUILD SUCCESSFUL in 1m 52s

>> [5/7] start db solr tomcat httpd
>> docker compose --project-name zfin-12345 --env-file /opt/zfin/source_roots/stacks/worktrees/zfin-12345/docker/.env -f /opt/zfin/source_roots/stacks/base/docker/docker-compose.yml -f /opt/zfin/source_roots/stacks/zfin-build-orchestrator/compose/docker-compose.overlay-feature.yml -f /opt/zfin/source_roots/stacks/zfin-build-orchestrator/compose/docker-compose.overlay-worktree.yml up -d db solr tomcat httpd
[+] up 4/4
 ✔ Container zfin-12345-db-1      Healthy    13.8s
 ✔ Container zfin-12345-solr-1    Started     0.9s
 ✔ Container zfin-12345-tomcat-1  Started    14.1s
 ✔ Container zfin-12345-httpd-1   Started    14.4s

>> [6/7] gradle dirtydeploy
...
BUILD SUCCESSFUL in 2m 37s

>> [7/7] tmux session 'zfin-12345'
>> tmux session 'zfin-12345' ready: cwd /opt/zfin/source_roots/stacks/worktrees/zfin-12345 (z resolves this stack from here)

>> provisioned zfin-12345
     worktree : /opt/zfin/source_roots/stacks/worktrees/zfin-12345
     branch   : zfin-12345  (off main)
     project  : zfin-12345
     url      : https://127.0.0.1:8448
     ports    : https 127.0.0.1:8448   http 127.0.0.1:8085   db 127.0.0.1:5437   debug 127.0.0.1:5005   jenkins 127.0.0.1:9504
     data     : own copy, restored from seed '2026-09-29'  + warm app tier
     use      : cd /opt/zfin/source_roots/stacks/worktrees/zfin-12345   (z commands there resolve to 'zfin-12345')
     tmux     : tmux attach -t zfin-12345   (session left running; Ctrl-b d to detach)
     ran      : npm ci  ->  up db+solr+tomcat+httpd  ->  dirtydeploy

next:
  # tmux session 'zfin-12345' is live in the worktree -- attaching below.
  # db+solr+tomcat+httpd already up -- serving main's deploy at https://127.0.0.1:8448
  # the app tier is already serving main's code from the warm snapshot.
  # THIS branch is deployed on top of it. Re-run after each edit:
  z run -c "gradle dirtydeploy"
  # this branch's schema/solr deltas on top of the seed (only if it changes them):
  z run -c "gradle liquibasePostBuild"

teardown:
  z feature rm zfin-12345                   # all of the below, automated (prompts first)
  # ...or by hand:
  z stop                             # just pause it: containers stopped, data kept (z up resumes)
  z down -v                          # remove containers + THIS stack's DB/Solr/app copy
  git worktree remove /opt/zfin/source_roots/stacks/worktrees/zfin-12345
  tmux kill-session -t zfin-12345           # drop this feature's shell

>> attaching to 'zfin-12345' (Ctrl-b d to detach; the stack keeps running)
```

```console
# ...now inside tmux session 'zfin-12345', sitting in the worktree.
14:47:20 ryan@mac:.../worktrees/zfin-12345$ z status
>> targeting 'zfin-12345' (zfin-12345)
stack: zfin-12345
  url      : https://127.0.0.1:8448
  dir      : /opt/zfin/source_roots/stacks/worktrees/zfin-12345
  branch   : zfin-12345
  jira     : https://zfin.atlassian.net/browse/zfin-12345
  pr       : https://github.com/rtaylorzfin/zfin/pull/new/zfin-12345
  seed     : 2026-09-29
  running  : db httpd solr tomcat

14:47:31 ryan@mac:.../worktrees/zfin-12345$ sed -n '/added by new-feature/,$p' docker/.env
# --- added by new-feature.groovy for zfin-12345 ---
COMPOSE_PROJECT_NAME=zfin-12345
DOCKER_SOURCE_ROOTS_PATH=/opt/zfin/source_roots/stacks/worktrees/zfin-12345
DOCKER_VIRTUAL_HOST=zfin-12345.zfin.test
ZFIN_PORT_OFFSET=5
DOCKER_DB_PORT=127.0.0.1:5437
DOCKER_JENKINS_HTTP_PORT=127.0.0.1:9504
DOCKER_TOMCATDEBUG_PORT=127.0.0.1:5005
DOCKER_HTTPD_HTTP_PORT=127.0.0.1:8085
DOCKER_HTTPD_HTTPS_PORT=127.0.0.1:8448
# feature stack -- see StackConfig.featureEnv (z feature refresh backfills these)
DOCKER_EXTERNAL_VHOST=
DOCKER_SOLR_MEM_LIMIT=6g
DOCKER_SOLR_HEAP=4g
DOCKER_GIT_COMMON_DIR=/opt/zfin/source_roots/stacks/base/.git
DOCKER_GIT_WORKTREE_DIR=/opt/zfin/source_roots/stacks/base/.git/worktrees/zfin-12345
DOCKER_INSTANCE=feature
ZFIN_COMPOSE_OVERLAYS=docker-compose.overlay-feature.yml
ZFIN_SEED=2026-09-29
14:47:40 ryan@mac:.../worktrees/zfin-12345$ curl -sk -o /dev/null -w '%{http_code}\n' https://127.0.0.1:8448/
200
```

---

## 6. Working in a stack

```console
14:52:03 ryan@mac:.../worktrees/zfin-12345$ vi source/org/zfin/marker/presentation/MarkerController.java
14:53:40 ryan@mac:.../worktrees/zfin-12345$ z run -c "gradle dirtydeploy"
>> targeting 'zfin-12345' (zfin-12345)
...
BUILD SUCCESSFUL in 41s
14:54:30 ryan@mac:.../worktrees/zfin-12345$ z restart tomcat
>> targeting 'zfin-12345' (zfin-12345)
[+] restart 1/1
 ✔ Container zfin-12345-tomcat-1  Started    10.6s
14:54:45 ryan@mac:.../worktrees/zfin-12345$ z restart tomcta
>> targeting 'zfin-12345' (zfin-12345)
!! no such service: tomcta
   services in this stack: base blast certbot claude compile db elasticsearch fail2ban filebeat httpd jbrowse jenkins kibana mailpit metricbeat ncbiload processgff solr tomcat tomcatdebug
14:55:01 ryan@mac:.../worktrees/zfin-12345$ z exec db -c "psql -U postgres zfindb -tAc 'select count(*) from marker'"
>> targeting 'zfin-12345' (zfin-12345)
591042
14:55:20 ryan@mac:.../worktrees/zfin-12345$ z run -u root -c "id -un"
>> targeting 'zfin-12345' (zfin-12345)
root
14:55:34 ryan@mac:.../worktrees/zfin-12345$ z log tomcat
>> targeting 'zfin-12345' (zfin-12345)
tomcat-1  | 29-Sep-2026 14:54:52.311 INFO [main] org.apache.catalina.startup.Catalina.start Server startup in [9874] milliseconds
^C
```

```console
14:56:10 ryan@mac:.../worktrees/zfin-12345$ z status -v
>> targeting 'zfin-12345' (zfin-12345)
stack: zfin-12345
  url      : https://127.0.0.1:8448
  dir      : /opt/zfin/source_roots/stacks/worktrees/zfin-12345
  branch   : zfin-12345
  ports    : https 127.0.0.1:8448   db 127.0.0.1:5437   debug 127.0.0.1:5005   jenkins 127.0.0.1:9504
  jira     : https://zfin.atlassian.net/browse/zfin-12345
  pr       : https://github.com/rtaylorzfin/zfin/pull/new/zfin-12345
  seed     : 2026-09-29
  compose  : /opt/zfin/source_roots/stacks/base/docker/docker-compose.yml:/opt/zfin/source_roots/stacks/zfin-build-orchestrator/compose/docker-compose.overlay-feature.yml:/opt/zfin/source_roots/stacks/zfin-build-orchestrator/compose/docker-compose.overlay-worktree.yml
  env-file : /opt/zfin/source_roots/stacks/worktrees/zfin-12345/docker/.env
  running  : db httpd solr tomcat

containers:
NAME                  IMAGE                           COMMAND                  SERVICE   CREATED          STATUS                    PORTS
zfin-12345-db-1       ghcr.io/zfin/zfin-db:main       "docker-entrypoint.s…"   db        16 minutes ago   Up 16 minutes (healthy)   127.0.0.1:5437->5432/tcp
zfin-12345-httpd-1    ghcr.io/zfin/zfin-httpd:main    "httpd-foreground"       httpd     16 minutes ago   Up 16 minutes             127.0.0.1:8085->80/tcp, 127.0.0.1:8448->443/tcp
zfin-12345-solr-1     ghcr.io/zfin/zfin-solr:main     "docker-entrypoint.s…"   solr      16 minutes ago   Up 16 minutes
zfin-12345-tomcat-1   ghcr.io/zfin/zfin-tomcat:main   "catalina.sh run"        tomcat    16 minutes ago   Up 2 minutes
```

```console
# A service that exits non-zero is called out; `z stop` pauses without losing anything.
15:02:14 ryan@mac:.../worktrees/zfin-12345$ z status
>> targeting 'zfin-12345' (zfin-12345)
stack: zfin-12345
  ...
  running  : db solr tomcat
  FAILED   : httpd (Exited (1) 8 seconds ago)

15:02:30 ryan@mac:.../worktrees/zfin-12345$ z stop
>> targeting 'zfin-12345' (zfin-12345)
[+] stop 4/4
 ✔ Container zfin-12345-httpd-1   Stopped     0.0s
 ✔ Container zfin-12345-tomcat-1  Stopped     1.2s
 ✔ Container zfin-12345-solr-1    Stopped     2.0s
 ✔ Container zfin-12345-db-1      Stopped     0.6s
15:02:41 ryan@mac:.../worktrees/zfin-12345$ z up
>> targeting 'zfin-12345' (zfin-12345)
[+] up 4/4
 ✔ Container zfin-12345-db-1      Healthy     6.1s
 ...
```

---

## 7. Feature stacks from a script, and the refusals

```console
# -y takes the defaults; flags pin the rest. The ticket is lowercased for the project, not the branch.
15:10:00 ryan@mac:.../stacks/base$ z feature new ZFIN-2001 -y --up
>> seed: 2026-09-29
>> warm app tier: yes (from /opt/zfin/source_roots/stacks/archive/seeds/2026-09-29)
>> warm build caches: yes (gradle + maven + npm, from /opt/zfin/source_roots/stacks/archive/seeds/2026-09-29)
>> plan: worktree + branch  ->  per-feature .env  ->  restore volumes from seed  ->  start db solr tomcat httpd
>> allocated port offset +6  (skipped in use: 1, 2, 3, 4, 5)
>> reach it at: https://127.0.0.1:8449
>> feature=ZFIN-2001 project=zfin-2001 host=zfin-2001.zfin.test ports=+6 tag=2026-09-29 base=main

>> [1/4] worktree + branch
Preparing worktree (new branch 'ZFIN-2001')
...

>> [2/4] per-feature .env
>> instance: feature (DOMAIN_NAME follows zfin-2001.zfin.test; dev mail stays caught)

>> [3/4] restore volumes
>>   [1/10] tls_certs            0 MB in   1.2s
...
>>   [10/10] pg_data           3558 MB in  78.6s
>> restore total: 79.4s

>> [4/4] start db solr tomcat httpd
>> docker compose --project-name zfin-2001 ... up -d db solr tomcat httpd
[+] up 5/5
...

>> provisioned ZFIN-2001
     worktree : /opt/zfin/source_roots/stacks/worktrees/zfin-2001
     branch   : ZFIN-2001  (off main)
     project  : zfin-2001
     url      : https://127.0.0.1:8449
     ports    : https 127.0.0.1:8449   http 127.0.0.1:8086   db 127.0.0.1:5438   debug 127.0.0.1:5006   jenkins 127.0.0.1:9505
     data     : own copy, restored from seed '2026-09-29'  + warm app tier
     use      : cd /opt/zfin/source_roots/stacks/worktrees/zfin-2001   (z commands there resolve to 'zfin-2001')
     ran      : up db+solr+tomcat+httpd

next:
  cd /opt/zfin/source_roots/stacks/worktrees/zfin-2001                           # z commands resolve to 'zfin-2001' from here
  # db+solr+tomcat+httpd already up -- serving main's deploy at https://127.0.0.1:8449
  # the app tier is already serving main's code from the warm snapshot.
  # Deploy THIS branch's changes on top (fast, incremental):
  z run -c "gradle dirtydeploy"
  # this branch's schema/solr deltas on top of the seed (only if it changes them):
  z run -c "gradle liquibasePostBuild"

teardown:
  z feature rm zfin-2001                   # all of the below, automated (prompts first)
  # ...or by hand:
  z stop                             # just pause it: containers stopped, data kept (z up resumes)
  z down -v                          # remove containers + THIS stack's DB/Solr/app copy
  git worktree remove /opt/zfin/source_roots/stacks/worktrees/zfin-2001


  provision 'zfin-2001' timing
    checks                         3.9s     3%
    worktree + branch              4.2s     3%
    per-feature .env               0.3s     0%
    restore volumes               79.4s    61%
    start db solr tomcat httpd    41.8s    32%
    TOTAL                        129.6s
    per volume (concurrent):
      pg_data             78.6s      3558 MB      45 MB/s
      solr_var            61.3s      3175 MB      52 MB/s
      gradle_cache        29.7s      1210 MB      41 MB/s
      npm_cache           18.4s       336 MB      18 MB/s
      maven_cache         12.6s       402 MB      32 MB/s
      catalina_base        7.9s       275 MB      35 MB/s
      jenkins_data         4.8s       118 MB      25 MB/s
      www_data             3.4s        52 MB      15 MB/s
      keystore             1.3s         0 MB       0 MB/s
      tls_certs            1.2s         0 MB       0 MB/s
```

```console
# Someone else's branch, known only to origin.
15:20:40 ryan@mac:.../stacks/base$ z feature new review-pr -y --existing-branch --branch someones-branch --up
>> seed: 2026-09-29
...
>> branch: new local 'someones-branch' tracking origin/someones-branch (--base ignored)
>> plan: worktree (existing branch)  ->  per-feature .env  ->  restore volumes from seed  ->  start db solr tomcat httpd
>> allocated port offset +7  (skipped in use: 1, 2, 3, 4, 5, 6)
>> reach it at: https://127.0.0.1:8450
>> feature=review-pr project=review-pr host=review-pr.zfin.test ports=+7 tag=2026-09-29 branch=someones-branch (existing)

>> [1/4] worktree (existing branch)
Preparing worktree (checking out 'someones-branch')
branch 'someones-branch' set up to track 'origin/someones-branch'.
...
>> provisioned review-pr
     worktree : /opt/zfin/source_roots/stacks/worktrees/review-pr
     branch   : someones-branch  (existing)
...
```

```console
15:25:02 ryan@mac:.../stacks/base$ z feature new zfin-10399 -y
>> seed: 2026-09-29
...
!! branch 'zfin-10399' already exists.
   set the stack up on it:  --existing-branch
   or cut a different one:  --branch <name>
15:26:05 ryan@mac:.../stacks/base$ z feature new zfin-2003 -y --seed vm-2026-09-20
!! seed 'vm-2026-09-20' holds a linux/amd64 PostgreSQL data directory; this host's db image is linux/arm64.
   Its pg_data is a PostgreSQL data directory, which is not portable between platforms.
   It would probably start and then return wrong results for text comparisons, because
   index ordering follows the glibc that wrote it. The app tier (www_data,
   catalina_base) and solr_var ARE portable -- the data tier is not.
   Make a seed on this host instead:  z seed create
   To override anyway:  ZFIN_ALLOW_PLATFORM_MISMATCH=1 <your command>
15:26:30 ryan@mac:.../stacks/base$ z feature new zfin-2003 -y --seed shared-only
!! seed 'shared-only' has no app tier (www_data, catalina_base empty or absent).
   It restores db+solr only. This stack will need a first build before it
   can serve -- see the end of this run for the command.
...
```

---

## 8. Sharing one db+solr

```console
15:30:00 ryan@mac:.../stacks/base$ z shared up
>> shared data tier is empty -- restoring seed '2026-09-29' (this is the one-time copy)
>>   [1/2] solr_var          3175 MB in  60.8s
>>   [2/2] pg_data           3558 MB in  77.2s
>> restored zfin_shared_pg_data (3558 MB) in 77.2s
>> restored zfin_shared_solr_var (3175 MB) in 60.8s
>> shared data stack 'zfin_shared' up (tag 2026-09-29) -> seeds ONE db+solr copy on network zfin_shared_net
[+] up 3/3
 ✔ Network zfin_shared_net        Created     0.0s
 ✔ Container zfin_shared-db-1     Started     0.7s
 ✔ Container zfin_shared-solr-1   Started     0.8s
>> attach features with: z feature new <ticket> --shared-db
15:33:10 ryan@mac:.../stacks/base$ z feature new ZFIN-2002 -y --shared-db --up
>> seed: 2026-09-29
>> shared db+solr: using the 'zfin_shared' stack (no per-feature copy)
>> warm app tier: yes (from /opt/zfin/source_roots/stacks/archive/seeds/2026-09-29)
>> warm build caches: yes (gradle + maven + npm, from /opt/zfin/source_roots/stacks/archive/seeds/2026-09-29)
>> plan: worktree + branch  ->  per-feature .env  ->  restore volumes from seed  ->  start tomcat httpd
>> allocated port offset +8  (skipped in use: 1, 2, 3, 4, 5, 6, 7)
>> reach it at: https://127.0.0.1:8451
>> feature=ZFIN-2002 project=zfin-2002 host=zfin-2002.zfin.test ports=+8 tag=2026-09-29 base=main
...
>> [3/4] restore volumes
>>   [1/8] tls_certs            0 MB in   1.1s
>>   [2/8] keystore             0 MB in   1.2s
>>   [3/8] www_data            52 MB in   3.1s
>>   [4/8] jenkins_data       118 MB in   4.5s
>>   [5/8] catalina_base      275 MB in   7.4s
>>   [6/8] maven_cache        402 MB in  12.1s
>>   [7/8] npm_cache          336 MB in  17.6s
>>   [8/8] gradle_cache      1210 MB in  28.9s
>> restore total: 29.0s

>> [4/4] start tomcat httpd
>> docker compose --project-name zfin-2002 ... -f .../docker-compose.overlay-shared-db.yml -f .../docker-compose.overlay-feature.yml up --no-start tomcat httpd
[+] create 3/3
 ✔ Network zfin-2002_default      Created     0.0s
 ✔ Container zfin-2002-tomcat-1   Created     0.1s
 ✔ Container zfin-2002-httpd-1    Created     0.3s
>> connect shared db -> zfin-2002_default (alias db)
>> connect shared solr -> zfin-2002_default (alias solr)
>> docker compose --project-name zfin-2002 ... start tomcat httpd
[+] start 2/2
 ✔ Container zfin-2002-tomcat-1   Started     0.2s
 ✔ Container zfin-2002-httpd-1    Started     0.4s

>> provisioned ZFIN-2002
     ...
     data     : SHARED zfin_shared db/solr (connected into zfin-2002_default)  + warm app (seed 2026-09-29)
     ...

next:
  cd /opt/zfin/source_roots/stacks/worktrees/zfin-2002                           # z commands resolve to 'zfin-2002' from here
  # tomcat+httpd up, serving main's deploy on the SHARED db/solr at https://127.0.0.1:8451
...
```

```console
15:40:02 ryan@mac:.../stacks/base$ z shared status
NAME                 IMAGE                         COMMAND                  SERVICE   CREATED          STATUS                    PORTS
zfin_shared-db-1     ghcr.io/zfin/zfin-db:main     "docker-entrypoint.s…"   db        10 minutes ago   Up 10 minutes (healthy)
zfin_shared-solr-1   ghcr.io/zfin/zfin-solr:main   "docker-entrypoint.s…"   solr      10 minutes ago   Up 10 minutes
>> features sharing this db: zfin-10453, zfin-10485, zfin-2002
15:40:15 ryan@mac:.../stacks/base$ z shared up
!! 3 feature(s) are attached to this shared data tier: zfin-10453, zfin-10485, zfin-2002
   If this recreates db/solr, their connection pools die and they serve 500s.
   Recover with:  z restart tomcat   (in each attached stack)
>> shared data stack 'zfin_shared' up (tag 2026-09-29) -> seeds ONE db+solr copy on network zfin_shared_net
[+] up 2/2
 ✔ Container zfin_shared-db-1     Running     0.0s
 ✔ Container zfin_shared-solr-1   Running     0.0s
>> attach features with: z feature new <ticket> --shared-db
```

```console
15:45:00 ryan@mac:.../stacks/base$ z shared freeze
!! 2 feature stack(s) are RUNNING on this shared data tier:
     zfin-10485
     zfin-2002
!! freezing would break them mid-flight.
   Stop them yourself, or authorise it:  z shared freeze --stop-sharers
15:45:20 ryan@mac:.../stacks/base$ z shared freeze --stop-sharers
!! 2 feature stack(s) are RUNNING on this shared data tier:
     zfin-10485
     zfin-2002
>> stopping zfin-10485's app tier before touching shared data
...
>> stopping zfin-2002's app tier before touching shared data
...
>> stopping shared db+solr (up to 120s for postgres to checkpoint)
...
>> shared db shut down cleanly (checkpoint complete)
>> capturing zfin_shared_pg_data -> /opt/zfin/source_roots/stacks/archive/zfin_shared/pg_data.tgz (pigz -1)
>> capturing zfin_shared_solr_var -> /opt/zfin/source_roots/stacks/archive/zfin_shared/solr_var.tgz (pigz -1)
>> down -v the shared stack (the archive is the copy now)
...
>> shared stack frozen: 6.6 GB in 128s -> /opt/zfin/source_roots/stacks/archive/zfin_shared
>> its sharers must be thawed/restarted after `z shared thaw`: zfin-10453, zfin-10485, zfin-2002
16:10:00 ryan@mac:.../stacks/base$ z shared thaw
>> thaw shared stack  frozen 2026-09-29 15:47:31  volumes=pg_data, solr_var
>>   [1/2] solr_var          3175 MB in  60.1s
>>   [2/2] pg_data           3558 MB in  76.9s
[+] up 3/3
...
>> shared data back up. Each sharer needs `z up` in its worktree to reconnect (was: zfin-10453, zfin-10485, zfin-2002)
16:12:30 ryan@mac:.../stacks/base$ cd ../worktrees/zfin-2002 && z up
>> targeting 'zfin-2002' (zfin-2002)
[+] create 2/2
 ✔ Container zfin-2002-tomcat-1   Created     0.0s
 ✔ Container zfin-2002-httpd-1    Created     0.0s
>> connect shared db -> zfin-2002_default (alias db)
>> connect shared solr -> zfin-2002_default (alias solr)
[+] start 2/2
 ✔ Container zfin-2002-tomcat-1   Started     0.3s
 ✔ Container zfin-2002-httpd-1    Started     0.5s
```

---

## 9. What is on this host

```console
16:20:00 ryan@mac:.../stacks/base$ z feature ls
PROJECT          BRANCH                 DATA   STATE  URL                                    WORKTREE
review-pr        someones-branch        own    up     https://127.0.0.1:8450                 review-pr
zfin-10453       zfin-10453             shared down   https://127.0.0.1:8447                 zfin-10453
zfin-10464       zfin-10464             own    partial https://127.0.0.1:8444                 zfin-10464
zfin-10485       zfin-10485             shared up     https://127.0.0.1:8445                 zfin-10485
zfin-10510       zfin-10510-v2          own    up     https://127.0.0.1:8446                 zfin-10510
zfin-12345       zfin-12345             own    up     https://127.0.0.1:8448                 zfin-12345
zfin-2001        ZFIN-2001              own    up     https://127.0.0.1:8449                 zfin-2001
zfin-2002        ZFIN-2002              shared up     https://127.0.0.1:8451                 zfin-2002

DATA: own = this stack's own db+solr copy; shared = the zfin_shared stack
STATE: partial = containers running but no httpd, so the URL will not answer (z up)
```

---

## 10. The Claude sidecar

```console
15:30:00 ryan@mac:.../worktrees/zfin-12345$ z run claude
>> targeting 'zfin-12345' (zfin-12345)
>> created an empty token file at /Users/ryan/.zfin/claude-token -- run `claude setup-token` on the HOST and save it there
[+] Building 38.4s (12/12) FINISHED
...
[+] create 1/1
 ✔ Volume zfin-12345_claude_home  Created     0.0s
!! No Claude token mounted, so `claude` will not be able to authenticate.
   On the HOST:  claude setup-token
   then save the token it prints to $ZFIN_CLAUDE_TOKEN_FILE
   (default ~/.zfin/claude-token, mode 600) and re-enter with: z run claude
...
15:31:10 ryan@mac:.../worktrees/zfin-12345$ claude setup-token
...
15:31:40 ryan@mac:.../worktrees/zfin-12345$ pbpaste > ~/.zfin/claude-token
15:31:45 ryan@mac:.../worktrees/zfin-12345$ z run claude
>> targeting 'zfin-12345' (zfin-12345)
╭──────────────────────────────────────────────╮
│ ✻ Welcome to Claude Code!                    │
│   cwd: /opt/zfin/source_roots/zfin.org       │
╰──────────────────────────────────────────────╯

> push this branch when you're done

⏺ Bash(git push origin zfin-12345)
  ⎿  Error: git push is blocked in the ZFIN sidecar: it has no SSH agent or keys, so a push cannot authenticate. Pushing is a human action on the host.
```

```console
# Any argument means "a shell, not the agent" -- and git works in there, worktree and all.
15:40:02 ryan@mac:.../worktrees/zfin-12345$ z run claude -c "git status -sb"
>> targeting 'zfin-12345' (zfin-12345)
## zfin-12345
 M source/org/zfin/marker/presentation/MarkerController.java
15:40:20 ryan@mac:.../worktrees/zfin-12345$ z status -v | sed -n '/one-off/,$p'
>> targeting 'zfin-12345' (zfin-12345)
one-off containers (z run):
  zfin-12345-claude-run-3f9c2a1b7d4e   Up 8 minutes   zfin-claude:main
```

```console
15:44:07 ryan@mac:.../worktrees/zfin-12345$ z feature session export
>> capturing zfin-12345_claude_home -> /opt/zfin/source_roots/stacks/archive/sessions/zfin-12345-20260929-154407.tgz (pigz -1)
>> transcripts -> /opt/zfin/source_roots/stacks/archive/sessions/zfin-12345-20260929-154407.transcripts.tgz (0.4 MB)
>> archived 3.1 MB -> /opt/zfin/source_roots/stacks/archive/sessions/zfin-12345-20260929-154407.tgz
15:44:20 ryan@mac:.../worktrees/zfin-12345$ z feature session ls
ARCHIVE                                          KIND      SIZE  ARCHIVED
zfin-12345-20260929-154407.transcripts.tgz       chat      0.4M  2026-09-29 15:44
zfin-12345-20260929-154407.tgz                   full      3.1M  2026-09-29 15:44
zfin-10485-20260922-101530.transcripts.tgz       chat      1.2M  2026-09-22 10:15
zfin-10485-20260922-101530.tgz                   full      5.8M  2026-09-22 10:15

in /opt/zfin/source_roots/stacks/archive/sessions
  chat = the conversation transcripts only (projects/**.jsonl) -- what you read
  full = the whole sidecar home, for resuming a session rather than reading it
  tar xzf <archive> -C <somewhere>
```

---

## 11. Parking a stack: freeze and thaw

```console
# No ticket needed inside the worktree.
16:00:10 ryan@mac:.../worktrees/zfin-12345$ z feature freeze
>> freeze 'zfin-12345'  project=zfin-12345  data=own
>> volumes  : pg_data, solr_var, www_data, catalina_base, keystore, tls_certs, claude_home, jenkins_data
>> archive  : /opt/zfin/source_roots/stacks/archive/zfin-12345
>> stopping the app tier (before the data tier, so nothing is mid-write)
[+] stop 2/2
 ✔ Container zfin-12345-httpd-1   Stopped     0.2s
 ✔ Container zfin-12345-tomcat-1  Stopped     1.3s
>> stopping the data tier (up to 120s for postgres to checkpoint)
[+] stop 2/2
 ✔ Container zfin-12345-solr-1    Stopped     2.1s
 ✔ Container zfin-12345-db-1      Stopped     0.7s
>> db shut down cleanly (checkpoint complete)
>> capturing zfin-12345_pg_data -> /opt/zfin/source_roots/stacks/archive/zfin-12345/pg_data.tgz (pigz -1)
>> capturing zfin-12345_solr_var -> /opt/zfin/source_roots/stacks/archive/zfin-12345/solr_var.tgz (pigz -1)
...
>> capturing zfin-12345_jenkins_data -> /opt/zfin/source_roots/stacks/archive/zfin-12345/jenkins_data.tgz (pigz -1)
>> down -v (removes containers + this stack's volumes; the archive is the copy now)
[+] down 13/13
...
>> removing 1 archived volume(s) `down -v` left behind (profile-scoped services): claude_home
>> frozen: 7.0 GB archived in 140s (pigz -1) -> /opt/zfin/source_roots/stacks/archive/zfin-12345
>> thaw with:  z feature thaw zfin-12345

  freeze 'zfin-12345' timing
    stop app tier               6.8s     4%
    stop data tier + verify     4.1s     3%
    capture volumes           140.0s    91%
    down -v                     3.2s     2%
    TOTAL                     154.1s
    per volume:
      pg_data             68.2s      3561 MB      52 MB/s
      solr_var            55.9s      3176 MB      57 MB/s
      catalina_base        6.4s       281 MB      44 MB/s
      jenkins_data         3.8s       121 MB      32 MB/s
      www_data             2.6s        61 MB      23 MB/s
      claude_home          1.3s         3 MB       2 MB/s
      keystore             0.9s         0 MB       0 MB/s
      tls_certs            0.9s         0 MB       0 MB/s
16:03:00 ryan@mac:.../worktrees/zfin-12345$ z feature freeze
!! 'zfin-12345' already has an archive at /opt/zfin/source_roots/stacks/archive/zfin-12345 (frozen already?).
   z feature thaw zfin-12345   to bring it back, or --force to overwrite the archive
16:03:10 ryan@mac:.../worktrees/zfin-12345$ git status -sb | head -2
## zfin-12345
 M source/org/zfin/marker/presentation/MarkerController.java
16:03:20 ryan@mac:.../worktrees/zfin-12345$ curl -sk -o /dev/null -w '%{http_code}\n' https://127.0.0.1:8448/
000
16:03:30 ryan@mac:.../worktrees/zfin-12345$ z feature ls | tail -2
DATA: own = this stack's own db+solr copy; shared = the zfin_shared stack
1 frozen stack(s) not shown -- z feature ls --frozen (or --all)
16:03:40 ryan@mac:.../worktrees/zfin-12345$ z feature ls --frozen
PROJECT          BRANCH                 DATA   STATE  URL                                    WORKTREE
zfin-12345       zfin-12345             own    frozen https://127.0.0.1:8448                 zfin-12345

DATA: own = this stack's own db+solr copy; shared = the zfin_shared stack
STATE: frozen = archived by `z feature freeze`, restore with `z feature thaw`
```

```console
# Build caches were not archived; thaw re-warms them from the stack's seed.
09:00:05 ryan@mac:.../worktrees/zfin-12345$ z feature thaw
>> thaw 'zfin-12345'  project=zfin-12345  frozen 2026-09-29 16:02:44  data=archived
>> branch   : zfin-12345 @ 7d3e9a1c42
>> restoring 8 volume(s) from /opt/zfin/source_roots/stacks/archive/zfin-12345
>>   [1/8] tls_certs            0 MB in   1.3s
>>   [2/8] keystore             0 MB in   1.4s
>>   [3/8] claude_home          3 MB in   1.9s
>>   [4/8] www_data            61 MB in   3.5s
>>   [5/8] jenkins_data       121 MB in   4.9s
>>   [6/8] catalina_base      281 MB in   8.1s
>>   [7/8] solr_var          3176 MB in  60.4s
>>   [8/8] pg_data           3561 MB in  77.9s
>> re-warming build caches from /opt/zfin/source_roots/stacks/archive/seeds/2026-09-29 (gradle_cache, maven_cache, npm_cache)
>>   [1/3] maven_cache        402 MB in  12.4s
>>   [2/3] npm_cache          336 MB in  18.1s
>>   [3/3] gradle_cache      1210 MB in  29.3s
[+] up 5/5
...
>> thawed. https://127.0.0.1:8448
>> (the archive at /opt/zfin/source_roots/stacks/archive/zfin-12345 is kept -- remove it by hand once you trust the thaw)

  thaw 'zfin-12345' timing
    manifest checks     1.8s     1%
    restore volumes    78.3s    52%
    re-warm caches     29.6s    20%
    start stack        39.7s    27%
    TOTAL             149.4s
    per volume:
      pg_data             77.9s      3561 MB      46 MB/s
      solr_var            60.4s      3176 MB      53 MB/s
      catalina_base        8.1s       281 MB      35 MB/s
      jenkins_data         4.9s       121 MB      25 MB/s
      www_data             3.5s        61 MB      17 MB/s
      claude_home          1.9s         3 MB       2 MB/s
      keystore             1.4s         0 MB       0 MB/s
      tls_certs            1.3s         0 MB       0 MB/s
```

```console
# Thawed weeks later, after :main moved under the same tags.
10:12:00 ryan@mac:.../worktrees/zfin-10510$ z feature thaw
>> thaw 'zfin-10510'  project=zfin-10510  frozen 2026-08-30 17:21:09  data=archived
>> branch   : zfin-10510-v2 @ 4be81c07aa
!! these images have changed since the freeze: tomcat (ghcr.io/zfin/zfin-tomcat:main), httpd (ghcr.io/zfin/zfin-httpd:main)
   The tags are the same but the images behind them moved. The stack will start;
   redeploy (gradle dirtydeploy) if behaviour looks unlike what you archived.
>> restoring 8 volume(s) from /opt/zfin/source_roots/stacks/archive/zfin-10510
...
```

---

## 12. Keeping an older stack's `.env` current

```console
16:30:00 ryan@mac:.../stacks/base$ z feature refresh --all --dry-run
  review-pr: up to date
  zfin-10453: would add DOCKER_GIT_COMMON_DIR, DOCKER_GIT_WORKTREE_DIR
  zfin-10464: would add DOCKER_GIT_COMMON_DIR, DOCKER_GIT_WORKTREE_DIR
  zfin-10485: up to date
  zfin-10510: up to date
  zfin-12345: up to date
  zfin-2001: up to date
  zfin-2002: up to date
>> --dry-run: nothing written
16:30:20 ryan@mac:.../stacks/base$ z feature refresh zfin-10453 zfin-10464
  zfin-10453: adding DOCKER_GIT_COMMON_DIR, DOCKER_GIT_WORKTREE_DIR
  zfin-10464: adding DOCKER_GIT_COMMON_DIR, DOCKER_GIT_WORKTREE_DIR
>> 2 .env file(s) updated. Recreate the affected containers to pick them up:
   cd <worktree> && z up -d
```

---

## 13. Tearing down

```console
17:00:00 ryan@mac:.../stacks/base$ z feature rm zfin-1234
!! no such feature: zfin-1234
   known: review-pr, zfin-10453, zfin-10464, zfin-10485, zfin-10510, zfin-12345, zfin-2001, zfin-2002
17:00:10 ryan@mac:.../stacks/base$ z feature rm zfin-12345
About to REMOVE feature 'zfin-12345' (destructive):
  - docker compose down -v            (containers + this feature's volumes/copies)
  - git worktree remove --force /opt/zfin/source_roots/stacks/worktrees/zfin-12345 + branch -D zfin-12345  (drops uncommitted work)
  - archiving the sidecar session first (--no-session to skip)
  - rm -rf /opt/zfin/source_roots/stacks/archive/zfin-12345   (7.0 GB freeze archive -- --keep-archive to spare it)
  - tmux kill-session -t zfin-12345        (the feature's shell)
Proceed? [y/N]: y
>> capturing zfin-12345_claude_home -> /opt/zfin/source_roots/stacks/archive/sessions/zfin-12345-20260930-170012.tgz (pigz -1)
>> transcripts -> /opt/zfin/source_roots/stacks/archive/sessions/zfin-12345-20260930-170012.transcripts.tgz (0.6 MB)
>> archived the sidecar session (3.4 MB) -> /opt/zfin/source_roots/stacks/archive/sessions/zfin-12345-20260930-170012.tgz
>> down -v the 'zfin-12345' stack (and its own built images)
[+] down 14/14
 ✔ Container zfin-12345-httpd-1     Removed     0.3s
 ✔ Container zfin-12345-tomcat-1    Removed     1.2s
 ...
 ✔ Volume zfin-12345_pg_data        Removed     0.9s
 ...
 ✔ Network zfin-12345_default       Removed     0.2s
>> removing 1 volume(s) `down -v` left behind: zfin-12345_claude_home
>> removing worktree /opt/zfin/source_roots/stacks/worktrees/zfin-12345
>> deleting branch zfin-12345
Deleted branch zfin-12345 (was 7d3e9a1c42).
>> killing tmux session 'zfin-12345'
>> removing freeze archive /opt/zfin/source_roots/stacks/archive/zfin-12345 (7.0 GB)
>> removed feature 'zfin-12345'
```

```console
# A branch that predates its stack survives it. And on Linux, container-owned files are reclaimed first.
09:15:00 ryan@cell:.../zfin-dev/cell$ z feature rm review-pr --force
>> down -v the 'review-pr' stack (and its own built images)
...
>> reclaiming container-owned files (e.g. /opt/zfin-dev/worktrees/review-pr/node_modules) -- chown -> 1003:1003
>> removing worktree /opt/zfin-dev/worktrees/review-pr
>> keeping branch someones-branch (pre-existing -- created before this feature stack)
>> removed feature 'review-pr'
09:16:30 ryan@cell:.../zfin-dev/cell$ echo | z feature rm zfin-2001
!! z feature rm: no TTY -- pass --force to confirm
```

---

## 14. CI and bootstrap

```console
# `z build` targets the stack you stand in, one command per container, and names the step that failed.
11:00:00 ryan@mac:.../worktrees/zfin-2001$ z build deploy
>> targeting 'zfin-2001' (zfin-2001)
>> stack=zfin-2001  phases=deploy
>> deploy: build WAR, deploy catalina-base + app, (re)start app tier
[+] stop 2/2
...
>> deploy [1/3]: gradle make
...
>> deploy [1/3] ok (214.6s)
>> deploy [2/3]: ant deploy-catalina-base
...
>> deploy [2/3] ok (18.2s)
>> deploy [3/3]: ant deploy-no-tests-no-restart
...
>> deploy [3/3] ok (47.9s)
[+] up 3/3
...
>> done: deploy
11:10:40 ryan@mac:.../worktrees/zfin-2001$ z build deploy
>> targeting 'zfin-2001' (zfin-2001)
...
>> deploy [2/3]: ant deploy-catalina-base
...
BUILD FAILED
!! deploy failed at step 2 of 3: ant deploy-catalina-base
   Earlier steps in this phase succeeded. Re-run just this one with:
     z run -c "ant deploy-catalina-base"
11:12:00 ryan@mac:.../worktrees/zfin-2001$ z build
>> targeting 'zfin-2001' (zfin-2001)
!! no phase given. Phases: configure, load-db, load-solr, deploy-jenkins, deploy (or 'all'). See --help.
```

```console
09:00:00 ryan@laptop:~/zfin-dev/coral$ z fresh-install --dry-run
>> checking the machine is ZFIN-fresh...
>>   fresh ✓ (no ZFIN volumes / images / containers)
>> checking init inputs...
   ✓ DOCKER_DB_UNLOADS_PATH                 /Users/ryan/zfin-dev/mounts/unloads/db
   ✓ DOCKER_SOLR_UNLOADS_PATH               /Users/ryan/zfin-dev/mounts/unloads/solr
   – DOCKER_BOWTIE_PATH                     (unset)   (optional, skipped)
   ✓ DOCKER_ABBLAST_PATH                    /Users/ryan/zfin-dev/mounts/blast
   ✓ DOCKER_BLASTSERVER_BLAST_DATABASE_PATH /Users/ryan/zfin-dev/mounts/blast
   ✓ DOCKER_LOADUP_PATH                     /Users/ryan/zfin-dev/mounts/loadUp
   ✓ db dump: loaddb will use 2026.09.27/zfindb.bak
   ✓ solr dump: getLatestSolrIndex will use zfindb/v9/snapshot.20260927031502123
>> --dry-run: using defaults (pull images; no first ticket)

Plan:
  $ /Users/ryan/zfin-dev/zfin-build-orchestrator/z build all
>> dry-run: not executing.
```

```console
# After editing anything in lib/.
11:30:00 ryan@mac:.../stacks/zfin-build-orchestrator$ ./check
compiling lib/:
  ok    FeatureFreeze
  ok    FeatureList
  ok    FeatureRefresh
  ok    FeatureRemove
  ok    FeatureSession
  ok    FeatureThaw
  ok    FreshInstall
  ok    NewFeature
  ok    Scaffold
  ok    Seed
  ok    SeedBuild
  ok    SharedStack
  ok    ShellInit
  ok    StackConfig
  ok    StackOps
  ok    Zbuild
  ok    ZfinUtil

unqualified helper calls:
  none

clean
```
