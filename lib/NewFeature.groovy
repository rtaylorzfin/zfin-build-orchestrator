#!/usr/bin/env groovy
// Provision an isolated feature dev stack: a git worktree plus its own Compose
// project (own network, volumes, hostname) whose data is restored from a seed.
// Several feature branches can then run in parallel without branch-switching or
// reloading data. See docs/dev-stacks.md.
//
// What Compose already handles per-project (no work here): the private network,
// per-project volumes, and intra-network DNS (`db`/`solr` resolve to THIS
// project's containers). What this script handles are the HOST-side concerns
// that escape the Docker network -- published ports and the loopback/hostname
// mapping -- plus the worktree + per-feature .env.
//
// Normally invoked as `z feature new [<ticket>] [opts]` from a checkout;
// runs standalone too. On a TTY it PROMPTS for the whole plan -- existing branch, base, tag,
// shared db, boot, hosts, npm, dirtydeploy, liquibase, tmux -- whether or not <name> was given:
// provisioning has more knobs than anyone wants to remember as flags. Every prompt is
// Enter-able, a flag on the command line SKIPS its own prompt, and `-y` (or no TTY, i.e.
// a script) takes the defaults wholesale. What you answer is then executed, not printed
// as homework: `--up --deploy --liquibase --tmux` leaves a booted, deployed stack and a
// tmux session sitting in the worktree.
//
// Usage:
//   z feature new [<name>] [-y] [--base BRANCH] [--branch NAME] [--existing-branch]
//                   [--seed TAG | --no-seed]
//                         [--tag TAG] [--port-offset N] [--up]
//                         [--shared-db] [--deploy] [--liquibase] [--tmux]
//
//   <name>          Feature id, e.g. ZFIN-9002 -> project "zfin-9002",
//                   worktree "worktrees/zfin-9002", host "zfin-9002.<ZFIN_FEATURE_DOMAIN>".
//                   Omit it (from a terminal) to be prompted.
//   -y, --yes       Don't prompt: take the defaults (and the flags given). What a script
//                   or a non-TTY invocation gets automatically.
//   --base BRANCH   Start point for the new branch (default: main; a warning fires
//                   if invoked from a secondary worktree so you don't base off it).
//                   Ignored with --existing-branch -- there's no new branch to start.
//   --branch NAME   Branch to create, or to check out with --existing-branch
//                   (default: <name>)
//   --existing-branch
//                   Set up the stack on a branch that ALREADY exists instead of cutting
//                   a new one: the worktree checks it out as-is. A branch that exists
//                   only on origin counts -- the worktree gets a local branch tracking
//                   it. Without this flag an existing branch is an error, so a typo'd
//                   ticket can't quietly land you on someone else's work. Interactively
//                   you're asked whenever the branch already exists, so the flag is
//                   really for -y / scripted runs.
//   --seed TAG      Which seed to restore this stack's db/solr (and warm app tier) from.
//                   Default: the newest on this host; override via $ZFIN_SEED. `z seed ls`
//                   lists them, `z seed create` captures one. (--tag is the old spelling.)
//   --no-seed       Take a COLD stack instead: empty db and solr, which you load afterwards
//                   with `z build load-db load-solr`. Interactively, answer the seed prompt
//                   `none`. There is no other way to decline -- `--seed ''` is falsy and
//                   falls through to the newest seed.
//   --port-offset N Pin the offset added to published ports (https 8443+N, http 8080+N,
//                   db 5432+N, debug 5000+N, jenkins 9499+N). Default: the first free offset.
//
// Reaching the stack. It publishes httpd on its own ports -- https://127.0.0.1:8443+N -- on the
// address ZFIN_FEATURE_BIND names (default 127.0.0.1). When the host setting
// ZFIN_PROXY_NETWORK is set, httpd also joins that network and advertises VIRTUAL_HOST=<host>, so an
// outside nginx-proxy watching it routes https://<host>. The repo runs no proxy either way.
//   --up            Bring up the restored data tier (db + solr) after provisioning.
//                   If the seed carries the app tier (z seed create does, by default),
//                   ALSO populate the deploy volumes and start tomcat/httpd so the stack
//                   comes up serving the source branch's deploy. Otherwise the app tier
//                   stays down until the webapp is built+deployed (see the next: block).
//   --no-app        Skip the warm app tier even if a snapshot exists (cold app tier:
//                   build + deploy yourself). No effect without a snapshot.
//   --no-caches     Skip restoring the gradle/maven build caches even if captured
//                   (z seed create --caches). No effect without them.
//   --shared-db     Share the `zfin_shared` stack's db+solr instead of seeding this
//                   feature's own copy (needs `z shared up` first). READ-MOSTLY only:
//                   writes/migrations/reindex are shared with every other --shared-db
//                   feature. Uses docker-compose.overlay-shared-db.yml.
//   --node          Run the one-time `gradle npmInstall` (npm ci) that populates the
//                   worktree's node_modules. OFF by default: it costs minutes, and a stack
//                   restored from a warm seed serves without it. Needed before the first
//                   `gradle dirtydeploy` (npmBuild -> webpack). (--no-node is the explicit off.)
//   --deploy        After bringing the stack up, run `gradle dirtydeploy` so the app tier
//                   serves THIS branch instead of the snapshot's. Only meaningful with a warm
//                   app tier (a cold one needs the full first build -- see the next: block).
//   --liquibase     After bringing the stack up, run `gradle liquibasePostBuild` to apply this
//                   branch's schema deltas on top of the seed. Needs the data tier up; with
//                   --shared-db it migrates the copy EVERY shared feature is using.
//   --tmux          Leave a detached tmux session named after the slug, rooted in the worktree
//                   sitting in the worktree, and attach to it at the end. This is
//                   the one part of the next: block a child process cannot do for you: `cd`
//                   and `source` change the CALLING shell, which z can't reach into.
//   Each of the above has a --no-<flag> twin (--no-up/--no-deploy/--no-tmux/...), so you can
//   pin an answer off as well as on and still be prompted for the rest.
//
// After provisioning, just `cd` into the worktree: every z command resolves the stack from
// the directory you are in, and the feature commands infer the ticket from it too. The
// stack's composition is recorded in its own docker/.env (ZFIN_COMPOSE_OVERLAYS), so there is
// nothing per-worktree to keep in step with the tooling.

// --- shared helpers + roots (via ZfinUtil, passed in by z) --------------------
// Roots anchor to the PRIMARY checkout (z resolves them once), so running from inside a
// worktree is fine -- provisioning never depends on where you happen to be.
class NewFeature {
    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return
        def die = zfinUtil.&die; def info = zfinUtil.&info; def runCommand = zfinUtil.&runCommand;
        def captureOutput = zfinUtil.&captureOutput
        def imageExists = zfinUtil.&imageExists
        def DOCKER = zfinUtil.DOCKER   // docker
        def REPO = zfinUtil.REPO     // primary checkout
        def WT_PARENT = new File(zfinUtil.worktreesDir())   // <ZFIN_DEV_ROOT>/worktrees

// --- defaults + args ---------------------------------------------------------
        def base = 'main'
        def baseArg = false
        def tagArg = null
        def noSeed = false
        def branch = ''
        def useExisting = false
        def doUp = false
        def doApp = true
        def doCaches = true
        def doSharedDb = false
        def portOffset = null    // --port-offset; auto-allocated when unset
        def doNode = false    // opt-in: several minutes, and most first sessions do not deploy
        def doDeploy = false
        def doLiquibase = false
        def doTmux = false
        def assumeYes = false
        def name = ''

// Which decisions the COMMAND LINE already made. Every prompt below is skipped for a
// decision that's in here, so a flag always wins over the interactive plan and
// `z feature new ZFIN-1 --shared-db` still asks about everything else. That's also why
// each yes/no flag has a --no- twin: without one you could only pin an answer to `true`,
// and pinning it to `false` would be indistinguishable from not answering.
        def explicit = [] as Set

        def argv = args as List
        for (int i = 0; i < argv.size(); i++) {
            switch (argv[i]) {
                case '--base': base = argv[++i]; baseArg = true; explicit << 'base'; break
                case '--branch': branch = argv[++i]; break
                case '--existing-branch': case '--existing': useExisting = true; explicit << 'existing'; break
                case '--no-existing-branch': case '--no-existing': useExisting = false; explicit << 'existing'; break
                case '--seed': case '--tag': tagArg = argv[++i]; explicit << 'seed'; break
                // A COLD stack: db and solr start empty and you load them yourself
                // (`z build load-db load-solr`). Without this there is no way to decline a seed
                // -- an empty --seed '' is falsy and falls straight through to the newest one.
                case '--no-seed': noSeed = true; tagArg = null; explicit << 'seed'; break
                case '--port-offset': portOffset = argv[++i]; break
                case '--up': doUp = true; explicit << 'up'; break
                case '--no-up': doUp = false; explicit << 'up'; break
                case '--no-app': doApp = false; break
                case '--no-caches': doCaches = false; break
                case '--shared-db': doSharedDb = true; explicit << 'shared'; break
                case '--no-shared-db': doSharedDb = false; explicit << 'shared'; break
                case '--node': doNode = true; explicit << 'node'; break
                case '--no-node': doNode = false; explicit << 'node'; break
                case '--deploy': doDeploy = true; explicit << 'deploy'; break
                case '--no-deploy': doDeploy = false; explicit << 'deploy'; break
                case '--liquibase': doLiquibase = true; explicit << 'liquibase'; break
                case '--no-liquibase': doLiquibase = false; explicit << 'liquibase'; break
                case '--tmux': doTmux = true; explicit << 'tmux'; break
                case '--no-tmux': doTmux = false; explicit << 'tmux'; break
                case '-y': case '--yes': case '--no-prompt': assumeYes = true; break
                default:
                    if (argv[i].startsWith('-')) die("unknown arg: ${argv[i]}", 2)
                    name = argv[i]
            }
        }

        def baseEnvFile = new File(DOCKER, '.env')
        if (!baseEnvFile.exists()) die(zfinUtil.noBaseEnv() + "\n   (each feature's .env starts as a copy of it)")

// Seed selection: --seed > $ZFIN_SEED > newest seed on this host. Picks WHICH captured
// snapshot to restore from (dated captures, full vs lean, a branch-specific one) -- a
// selector, not a constant; the default grabs the newest, so the common case needs neither.
// --no-seed declines all three and takes a cold stack.
        def tag = noSeed ? null : (tagArg ?: zfinUtil.setting('ZFIN_SEED') ?: zfinUtil.newestSeed())
// A seed with no app tier restores db+solr and nothing else, and the stack then fails long
// after provisioning "succeeds": tomcat cannot find server.xml, httpd cannot open
// inc-redirect. `z seed create` warns about this when it writes such a seed, but a seed
// outlives that session -- so say it again HERE, where someone is about to use one, and
// again in the next: block, because this warning scrolls away during a long provision.
        // A seed from another platform restores a PostgreSQL data directory this host cannot
        // safely use. Refused rather than warned: the failure is silent wrong answers, so a
        // warning in a long provisioning log is not enough.
        def platProblem = tag ? zfinUtil.seedPlatformProblem(tag) : null
        if (platProblem && !zfinUtil.allowPlatformMismatch()) zfinUtil.die(platProblem)
        def thinSeed = tag ? zfinUtil.thinAppTier(tag) : []
        if (thinSeed) {
            System.err.println("!! seed '$tag' has no app tier (${thinSeed.join(', ')} empty or absent).")
            System.err.println("   It restores db+solr only. This stack will need a first build before it")
            System.err.println("   can serve -- see the end of this run for the command.")
        }

// No <name> given -> interactive prompts (needs a TTY; piped input errors with usage).
        def askYesNo = { con, String prompt, boolean dflt ->
            def line = con.readLine("$prompt [${dflt ? 'Y/n' : 'y/N'}]: ")?.trim()?.toLowerCase()
            line ? line.startsWith('y') : dflt
        }
// Worktree awareness: a new feature bases on `main` off the PRIMARY checkout, NOT
// the current worktree's branch. If we're invoked from a secondary worktree, that's
// easy to forget -- so prompt (interactive) or warn (non-interactive) about the base.
// Branch existence is asked of the PRIMARY checkout -- worktrees share its refs. A branch
// that lives only on origin counts as existing: `git worktree add <path> <branch>` DWIMs a
// local branch tracking it, which is exactly what you want when picking up someone's PR.
        def gitRef = { String ref -> zfinUtil.runQuietly(['git', '-C', REPO.absolutePath, 'rev-parse', '--verify', '--quiet', ref]) == 0 }
        def localBranch = { String b -> b && gitRef("refs/heads/$b") }
        def remoteBranch = { String b -> b && gitRef("refs/remotes/origin/$b") }
        def branchExists = { String b -> localBranch(b) || remoteBranch(b) }

        def cwdTop = captureOutput(['git', '-C', new File('.').absolutePath, 'rev-parse', '--show-toplevel'])
        def inWorktree = cwdTop && new File(cwdTop).canonicalFile != REPO.canonicalFile
        // Computed even OUTSIDE a worktree: standing in the main checkout on a feature branch
        // and basing a new stack on `main` is how you get a worktree whose build files predate
        // the tooling running against them.
        def cwdBranch = captureOutput(['git', '-C', new File('.').absolutePath, 'rev-parse', '--abbrev-ref', 'HEAD'])

// Warm-snapshot probe, by seed: `z seed create` leaves one tarball per volume under
// $ZFIN_SEEDS_DIR/<tag>/. Defined up here (not just at the restore below) because
// the interactive plan has to know whether the app tier WILL be warm -- `gradle dirtydeploy`
// only makes sense on top of a warm snapshot, and the boot prompt names the services it'll
// actually start. Takes the tag as a parameter since the tag is still being decided.
        def haveTars = { String t, List vns -> t && vns.every { new File(zfinUtil.seedDir(t), "${it}.tgz").isFile() } }

// The interactive plan. A <name> on the command line does not suppress the prompts:
// provisioning has enough knobs -- where the data comes from, whether to boot, whether to
// deploy this branch on top, where to leave you afterwards -- that answering nine Enter-able
// questions beats memorising nine flags. `-y` and a non-TTY (a script, a pipe) take the
// defaults instead, so an unattended caller never blocks on stdin.
        def con = System.console()
        def interactive = con != null && !assumeYes
        if (!name && !con) die("usage: z feature new <name> [-y] [--base B] [--branch B] [--tag T] " +
                "[--port-offset N] [--up] [--shared-db] [--deploy] [--liquibase] [--tmux]", 2)

        if (interactive) {
            println "New feature stack -- press Enter to accept [defaults]."
            while (!name) {
                name = con.readLine("  ticket / feature id (e.g. ZFIN-789): ")?.trim()
                if (!name) System.err.println("    (required)")
            }
            // Existing branch: only worth a question when there IS one to reuse. Asked before
            // the base prompt because reusing a branch makes the base moot.
            if (!('existing' in explicit) && branchExists(branch ?: name)) {
                def b = branch ?: name
                def whereB = localBranch(b) ? 'exists' : 'exists on origin'
                useExisting = askYesNo(con, "  branch '$b' $whereB -- set the stack up on it (no new branch)?", true)
            }
            if (inWorktree && !baseArg && !useExisting)
                println "  (you're in worktree '${new File(cwdTop).name}' on '$cwdBranch'; new features usually base on 'main')"
            if (!('base' in explicit) && !useExisting) {
                // `.` for the branch checked out here. Offered because it is the common second
                // answer -- a feature that builds on work not yet merged has to base on it, and
                // typing the branch name is the step people skip.
                def hint = (cwdBranch && cwdBranch != base) ? "  (. = $cwdBranch)" : ''
                def bIn = (con.readLine("  base branch [$base]${hint}: ")?.trim())
                base = (bIn == '.') ? cwdBranch : (bIn ?: base)
            }
            // 'seed', not 'tag': --seed pushes 'seed', so checking 'tag' meant nothing ever
            // matched and --seed still prompted.
            if (!('seed' in explicit)) {
                def tIn = con.readLine("  seed [${tag ?: 'none captured yet'}]  (none = cold stack): ")?.trim()
                // Sets noSeed too, not just tag: the guard below has to tell "declined" from
                // "this host has no seeds", and a bare null cannot.
                if (tIn in ['none', '-']) { tag = null; noSeed = true }
                else if (tIn) tag = tIn
            }
            if (!('shared' in explicit))
                doSharedDb = askYesNo(con, "  share the ${zfinUtil.sharedProject()} db+solr instead of your own copy (read-mostly)?", false)
            // From here the questions are about what to RUN, so they're phrased in terms of
            // what will actually happen for this tag: a warm snapshot means db+solr+app come
            // up together and dirtydeploy is the natural next step; a cold one means neither.
            def warmPeek = doApp && haveTars(tag, StackConfig.APP_VOLS)
            def bootDesc = doSharedDb ? (warmPeek ? 'tomcat+httpd (data is shared)' : 'nothing yet (cold app tier)')
                                      : (warmPeek ? 'db+solr+tomcat+httpd' : 'db+solr')
            if (!('up' in explicit)) doUp = askYesNo(con, "  bring the stack up now ($bootDesc)?", true)
            if (!('node' in explicit))
                doNode = askYesNo(con, "  run npm ci now (gradle npmInstall; once per worktree, needed before dirtydeploy)?", false)
            // dirtydeploy is only offered against a warm app tier -- with a cold one the first
            // deploy is the full ant/gradle sequence in the next: block, not this task.
            // Default follows the npm answer. dirtydeploy runs npmBuild WITHOUT npmInstall, and
            // the worktree is always fresh here (these prompts run before it exists), so
            // declining npm ci and accepting this fails at `webpack: not found`. Defaulting Y
            // after a N would be offering a broken combination.
            if (!('deploy' in explicit) && warmPeek)
                doDeploy = askYesNo(con, "  deploy this branch on top of the snapshot (gradle dirtydeploy)?" +
                        (doNode ? "" : " -- needs the npm ci declined above"), doNode)
            // ...and liquibase only when there'll be a database to migrate. Default N either
            // way: most branches carry no schema delta, and on a shared db it hits everyone.
            if (!('liquibase' in explicit) && (doUp || doSharedDb))
                doLiquibase = askYesNo(con, "  apply this branch's schema deltas (gradle liquibasePostBuild)" +
                        (doSharedDb ? " -- WRITES TO THE SHARED DB" : "") + "?", false)
            if (!('tmux' in explicit))
                doTmux = askYesNo(con, "  spawn a tmux session '${name.toLowerCase()}' in the worktree?", true)
        } else if (inWorktree && !baseArg && !useExisting) {
            System.err.println("!! note: invoked from worktree '${new File(cwdTop).name}' (branch $cwdBranch) -- basing the new feature on '$base' off the primary checkout (pass --base to override)")
        }

        // No hard requirement here: a --shared-db stack needs no seed at all, and the
        // own-data path checks for one itself (below) before anything has been created.
        info(tag ? "seed: $tag"
                 : noSeed ? "seed: none -- COLD stack, db and solr start empty"
                 : doSharedDb ? "seed: none (--shared-db shares the ${zfinUtil.sharedProject()} data tier)"
                 : "seed: none found on this host")

// --shared-db: this feature runs NO local db/solr (see docker-compose.overlay-shared-db.yml), so it
// doesn't need a seed -- it needs the shared data stack up (its external network).
        // A proxy is optional: the host's own (ZFIN_PROXY_NETWORK) or `z proxy`'s, while it runs.
        // When one is named, its network must exist NOW:
        // `external: true` fails at `up`, after the worktree, .env and volumes already exist.
        def proxyNet = zfinUtil.proxyNetwork()
        if (proxyNet && zfinUtil.runQuietly(['docker', 'network', 'inspect', proxyNet]) != 0)
            die("ZFIN_PROXY_NETWORK=$proxyNet, but there is no such Docker network.\n" +
                "   Start the proxy that owns it (z proxy up, for z's own), or unset ZFIN_PROXY_NETWORK to\n" +
                "   reach stacks on their published ports only. Nothing was created.")
        // Warned, not refused: the stack works on its published port either way, and the fix
        // belongs to whoever runs the proxy.
        def proxyGap = proxyNet ? zfinUtil.proxyReachProblem(proxyNet) : null
        if (proxyGap) System.err.println("!! $proxyGap")

        if (doSharedDb) {
            // What must be true is that the shared db+solr are RUNNING -- not that a network
            // object exists. Those are different: the shared stack's network outlives `z shared down`
            // whenever a feature is still attached to it (Docker refuses to remove an in-use
            // network), so the old `network inspect` guard passed happily with no data tier at
            // all, and provisioning then "succeeded" into a stack that cannot reach a database.
            def sharedRunning = {
                StackConfig.DATA_SERVICES.every { svc ->
                    captureOutput(['docker', 'ps', '-q',
                                   '--filter', "label=com.docker.compose.project=${zfinUtil.sharedProject()}",
                                   '--filter', "label=com.docker.compose.service=$svc"])
                }
            }
            if (!sharedRunning()) {
                // `z shared up` starts only a shared stack it manages (labelled, or not yet
                // created). When ZFIN_SHARED_PROJECT names anything else -- a real instance like
                // `cell`, or a shared stack another tree runs -- it is not ours to start.
                if (!zfinUtil.managedSharedStack(zfinUtil.sharedProject()))
                    die("--shared-db points at the '${zfinUtil.sharedProject()}' project, which is not running.\n" +
                        "   It is not a shared stack z manages (a real instance, or another tree's), so\n" +
                        "   `z shared up` cannot start it. Bring it up yourself, or give this tree its own:\n" +
                        "     z config set ZFIN_SHARED_PROJECT=<name>\n" +
                        "   Nothing was created for this feature.")
                // Start it rather than refuse. Asking for --shared-db IS asking to use the
                // shared stack, so having it running is a precondition of the request, not a
                // separate decision -- and `z shared up` is idempotent and binds no host ports.
                // Auto-start what affects only itself; refuse for what takes a host-wide
                // resource, such as ports another stack may hold.
                def seeded = zfinUtil.volumeExists("${zfinUtil.sharedProject()}_pg_data")
                info(seeded ? "shared data stack is down -- starting it (volumes already seeded)"
                            : "shared data stack has never run -- starting it; this SEEDS ~19G db + ~9G solr once")
                new SharedStack().run(['up'] + (tag ? ['--tag', tag] : []), zfinUtil)
                if (!sharedRunning())
                    die("shared db+solr still not running after `z shared up` -- fix that first, " +
                        "then re-run. Nothing was created for this feature.")
            }
            def src = zfinUtil.sharedProject()
            info("shared db+solr: using the '$src' stack (no per-feature copy)")
            if (!zfinUtil.managedSharedStack(src)) {
                // Pointing at a real instance rather than a shared stack z runs. Worth more than
                // the usual shared-writes note: this is someone's working database, and a
                // liquibase migration or a curation edit from this branch lands in it.
                System.err.println("!! '$src' is not a shared stack z manages -- this feature will read AND WRITE")
                System.err.println("   a live instance's database. A schema migration (gradle liquibasePostBuild)")
                System.err.println("   or any curation edit here hits that instance. Read-mostly work only.")
            }
        } else {
            // Fail fast, BEFORE the worktree, .env and volumes exist -- a bad seed should
            // leave no partial state behind.
            def have = { -> (zfinUtil.seedsDir().listFiles() ?: []).findAll { it.isDirectory() }*.name.sort() }
            // No tag has two meanings and they need different answers: --no-seed (or `none` at
            // the prompt) is a deliberate cold stack with nothing to validate, while an
            // unintended null means this host has no seeds and the run should stop before it
            // creates anything.
            if (!tag && !noSeed) die("no seed to restore from.\n" +
                    "   Capture one from a loaded stack:  z seed create --from <project>\n" +
                    "   ...or take a cold stack:           z feature new <ticket> --no-seed\n" +
                    "   ...or share a db+solr copy:        z feature new <ticket> --shared-db")
            // Everything below inspects the seed, so it only applies when there is one.
            if (tag) {
                def seed = zfinUtil.seedDir(tag)
                StackConfig.DATA_VOLS.each { vn ->
                    if (!zfinUtil.archiveFileFor(seed, vn))
                        die("seed '$tag' has no $vn tarball ($seed).\n" +
                            (have() ? "   seeds here: ${have().join(', ')}" : "   no seeds yet -- z seed create"))
                }
                // A seed carries DATA; the engine comes from this stack's db image. PGDATA written
                // by one postgres major cannot be opened by another, and refusing here beats
                // discovering it when the server will not start on a stack that is already built.
                def sm = zfinUtil.readSeedManifest(seed)
                if (!sm) info("note: seed '$tag' has no ${StackConfig.SEED_MANIFEST} -- cannot check its postgres major")
                else if (sm.pg_major) {
                    def engine = captureOutput(['docker', 'run', '--rm', '--entrypoint', 'sh',
                            zfinUtil.dbImage(),
                            '-c', 'postgres --version'])?.find(/\d+/)
                    if (engine && engine != sm.pg_major)
                        die("seed '$tag' holds PostgreSQL ${sm.pg_major} data, but ${zfinUtil.dbImage()} " +
                            "runs ${engine}.\n" +
                            "   That stack would refuse its own data. Re-capture the seed against this\n" +
                            "   release, or point ZFIN_RELEASE at the one the seed was taken from.")
                }
                // Sizes only, and only the tarballs this run restores; z seed verify re-hashes.
                def restoring = StackConfig.DATA_VOLS + (doApp ? StackConfig.APP_VOLS + [StackConfig.JENKINS_VOL] : []) +
                                (doCaches ? StackConfig.CACHE_VOLS : [])
                def damaged = sm ? zfinUtil.seedProblems(seed, sm, restoring) : []
                if (damaged) die("seed '$tag' does not match its manifest:\n   " + damaged.join('\n   ') +
                                 "\n   z seed verify $tag checks the rest; a damaged seed has to be copied again or rebuilt.")
                def unchecked = sm ? zfinUtil.untrackedSeedVolumes(seed, sm).findAll { it in restoring } : []
                if (unchecked) info("note: ${unchecked.join(', ')} not in the seed's manifest, so restored unchecked " +
                                    "(z seed add-volumes records them)")
            }
        }

// Warm app tier: `z seed create` leaves the deployed-app volumes as tarballs in the seed
// directory. If all four are present (and not --no-app), we
// extract them into this feature's fresh volumes so tomcat/httpd come up already
// serving the source branch's deploy -- the feature then dirtydeploys just its delta.
        def appVols = StackConfig.APP_VOLS   // shared volume contract (single source, also read by Seed)
        def cacheVols = StackConfig.CACHE_VOLS
        def auxDir = tag ? zfinUtil.seedDir(tag) : new File('/nonexistent')
        def warmApp = doApp && haveTars(tag, appVols)
        // Caches are independent (gradle/maven/npm) -> warm whichever tarballs are present, so a
        // snapshot that predates a newly-added cache still warms the rest. (App stays
        // all-or-nothing: a partial deploy is broken.)
        def cachesPresent = doCaches ? cacheVols.findAll { new File(auxDir, "${it}.tgz").isFile() } : []
        def warmCaches = !cachesPresent.isEmpty()
        if (doApp && !warmApp && auxDir.isDirectory())
            info("note: $auxDir exists but is missing app tarballs -- app tier will NOT be warmed")
        info(warmApp ? "warm app tier: yes (from $auxDir)"
                : "warm app tier: no" + (doApp ? " (z seed create captures it by default)" : " (--no-app)"))
        if (warmCaches) info("warm build caches: yes (${cachesPresent.collect { it - '_cache' }.join(' + ')}, from $auxDir)")

        def slug = name.toLowerCase()          // Compose projects must be lowercase
        def project = slug
        branch = branch ?: name
        // Frozen into this feature's .env below, so a later change to ZFIN_FEATURE_DOMAIN never
        // renames a stack that already exists.
        def host = zfinUtil.featureHost(slug)
        def wt = new File(WT_PARENT, slug)
        def wtPath = wt.absolutePath
        // An existing directory is reused (the re-run path below) only when it is a linked
        // worktree. Anything else -- worktrees/main, the main checkout, above all -- is not a
        // feature, and reusing it would overwrite its docker/.env.
        if (wt.exists() && !ZfinUtil.isLinkedWorktree(wt))
            die("$wtPath exists and is not a feature worktree" +
                (wt.canonicalFile == REPO.canonicalFile ? " (it is the main checkout)" : '') +
                ".\n   Choose another ticket name.", 2)

// Settle the branch BEFORE anything is created (loopback alias, worktree, .env, volumes),
// so a wrong answer here costs nothing to retry. Skipped when the worktree already exists:
// that's the documented re-run path below, and its branch is checked out by definition.
        if (!wt.isDirectory()) {
            if (useExisting) {
                if (!branchExists(branch))
                    die("--existing-branch: no branch '$branch' locally or on origin.\n" +
                            "   drop --existing-branch to create it, or name it:  --branch <name>")
                // A branch can only be checked out in one worktree; git would refuse at
                // `worktree add`, after the loopback alias is already claimed.
                def holder = null, wtPathSeen = null
                captureOutput(['git', '-C', REPO.absolutePath, 'worktree', 'list', '--porcelain']).eachLine { line ->
                    if (line.startsWith('worktree ')) wtPathSeen = line.substring(9)
                    else if (line == "branch refs/heads/$branch") holder = wtPathSeen
                }
                if (holder) die("branch '$branch' is already checked out in $holder\n" +
                        "   use that tree, or pick another branch:  --branch <name>")
                info(localBranch(branch) ? "branch: existing '$branch' (no new branch, --base ignored)"
                        : "branch: new local '$branch' tracking origin/$branch (--base ignored)")
            } else if (localBranch(branch)) {
                die("branch '$branch' already exists.\n" +
                        "   set the stack up on it:  --existing-branch\n" +
                        "   or cut a different one:  --branch <name>")
            }
        }

// --- the plan, and progress against it ---------------------------------------
// Everything is decided by now, so settle the two answers that depend on the tag (you can
// ask for a deploy the app tier can't use, or a migration with no database to run it
// against) and turn the rest into a phase list. Several phases are slow and silent for
// minutes -- npm ci, dirtydeploy -- so the list gets printed up front and each phase
// stamps itself as it starts. Going quiet inside a gradle build with no idea which step
// you're on is the thing this is for.
        def services = (doSharedDb ? [] : StackConfig.DATA_SERVICES) + (warmApp ? StackConfig.APP_SERVICES : [])
        if (doDeploy && !warmApp)
            info("note: the app tier is cold -- `gradle dirtydeploy` alone won't produce a servable deploy (see the next: block for the first-time sequence)")
        // node_modules is git-ignored and absent from a fresh worktree, and dirtydeploy runs
        // npmBuild WITHOUT npmInstall -- so this combination fails deep inside gradle with a
        // bare `sh: 1: webpack: not found`. Say so here instead, while it still reads as a
        // choice you made.
        if (doDeploy && !doNode && !new File(wt, 'node_modules').isDirectory())
            info("note: dirtydeploy without npm ci in a fresh worktree fails at `webpack: not found` -- drop --no-node, or run `z run -c \"gradle npmInstall\"` first")
        if (doLiquibase && !(doUp || doSharedDb)) {
            info("note: skipping liquibasePostBuild -- it needs the data tier up (re-run with --up, or run it yourself after z up db)")
            doLiquibase = false
        }
        // What the restore step will extract. Decided here so the plan lists a restore only when
        // there is one: a cold stack (--no-seed) restores nothing.
        // No seed -> no data volumes; --shared-db -> the data is someone else's.
        def dataVols = (doSharedDb || !tag) ? [] : StackConfig.DATA_VOLS
        // jenkins_data is restored when the seed carries it, independently of warmApp: it is
        // not part of the warm-app test, so an older seed without it simply has none.
        def jenkinsPresent = (tag && zfinUtil.archiveFileFor(auxDir, StackConfig.JENKINS_VOL)) ?
                             [StackConfig.JENKINS_VOL] : []
        def toRestore = dataVols + (warmApp ? appVols : []) + jenkinsPresent + (warmCaches ? cachesPresent : [])
        def cold = !tag && !doSharedDb

        def plan = [useExisting ? 'worktree (existing branch)' : 'worktree + branch', 'per-feature .env']
        if (toRestore) plan << 'restore volumes from seed'
        if (doNode) plan << 'npm ci'
        if (doUp && services) plan << "start ${services.join(' ')}"
        if (doDeploy) plan << 'gradle dirtydeploy'
        if (doLiquibase) plan << 'gradle liquibasePostBuild'
        if (doTmux) plan << "tmux session '$slug'"
        info("plan: ${plan.join('  ->  ')}")
        int stepNo = 0
        def timer = zfinUtil.stepTimer()
        def openStep = [null]            // the step currently running, closed by the next step()
        def step = { String label ->
            timer.mark(openStep[0] ?: 'checks')
            openStep[0] = label
            println "\n>> [${++stepNo}/${plan.size()}] $label"
        }

// Allocate a free PORT OFFSET for this feature's published ports: one address
// (ZFIN_FEATURE_BIND, 127.0.0.1 by default, which exists on every platform) and an offset per
// stack, the scheme ZFIN's Linux hosts already use -- cell publishes 8084/8447/8988/9503 on one
// address. No loopback aliases, nothing to clean up at teardown.
//
// "Taken" is gathered so we never collide with the base stack, another feature, or whatever is
// bound right now: offsets recorded in existing stacks' .env, plus any db or httpd port
// currently published in our ranges, whoever owns it.
        int offset = 0
        if (!portOffset) {
            def taken = [0] as Set          // 0 is the base stack's own set; never hand it out
            (WT_PARENT.listFiles() ?: [] as File[]).findAll { it.isDirectory() }.each { d ->
                def v = zfinUtil.envField(new File(d, 'docker/.env'), 'ZFIN_PORT_OFFSET')
                if (v ==~ /\d+/) taken << (v as int)
            }
            // Anything already listening on a db port in our range, whoever owns it.
            def published = captureOutput(['docker', 'ps', '--format', '{{.Ports}}'])
            (published =~ /:(\d+)->5432\//).each { taken << ((it[1] as int) - 5432) }
            (published =~ /:(\d+)->443\//).each { m ->
                def n = (m[1] as int) - StackConfig.PORT_BASES.https
                if (n in 1..99) taken << n
            }

            offset = 1
            while (offset <= 99 && taken.contains(offset)) offset++
            if (offset > 99) die("no free port offset in [1, 99] (taken: ${taken.sort().join(', ')})")
            def skipped = taken.findAll { it > 0 }.sort()
            info("allocated port offset +$offset" + (skipped ? "  (skipped in use: ${skipped.join(', ')})" : ''))
        } else {
            if (!(portOffset ==~ /\d+/)) die("--port-offset must be a number 1-99 (got '$portOffset')", 2)
            offset = portOffset as int
            if (offset < 1 || offset > 99) die("--port-offset out of range: $offset (usable 1-99)", 2)
        }
        def dbPort    = StackConfig.PORT_BASES.db + offset
        def debugPort = StackConfig.PORT_BASES.debug + offset
        def jenkPort  = StackConfig.PORT_BASES.jenkins + offset
        def httpPort  = StackConfig.PORT_BASES.http + offset
        def httpsPort = StackConfig.PORT_BASES.https + offset
        def bind = zfinUtil.featureBind()
        def shown = bind == '0.0.0.0' ? 'localhost' : bind
        def directUrl = "https://$shown:$httpsPort"
        // The URL to hand out: the proxy's name when one routes this stack, else the port.
        def url = proxyNet ? "https://$host" : directUrl
        info("reach it at: $directUrl" + (proxyNet ? "  and https://$host via the proxy on network '$proxyNet'" : ''))

        info("feature=$name project=$project host=$host ports=+$offset tag=$tag " +
                (useExisting ? "branch=$branch (existing)" : "base=$base"))

// 1. worktree + branch (separate host path => its own mounted source tree)
        step(useExisting ? 'worktree (existing branch)' : 'worktree + branch')
        if (!wt.isDirectory()) {
            runCommand(useExisting ? ['git', '-C', REPO.absolutePath, 'worktree', 'add', wtPath, branch]
                                   : ['git', '-C', REPO.absolutePath, 'worktree', 'add', wtPath, '-b', branch, base])
        } else {
            info("worktree $wtPath already exists, reusing")
        }


// 2. per-feature .env: start from the base env, strip the keys we own, append our
//    overrides. DOCKER_INSTANCE is one of the keys we own: without it a feature inherits the
//    base env's instance (coral, say) and generates THAT host's zfin.properties. See
//    StackConfig.FEATURE_INSTANCE for why every feature runs as the same, unlisted instance.
        step('per-feature .env')
        def owned = ['COMPOSE_PROJECT_NAME', 'DOCKER_SOURCE_ROOTS_PATH', 'DOCKER_VIRTUAL_HOST',
                     'DOCKER_SOLR_MEM_LIMIT', 'DOCKER_SOLR_HEAP',
                     'DOCKER_HTTPD_HTTP_PORT', 'DOCKER_HTTPD_HTTPS_PORT', 'DOCKER_DB_PORT',
                     'DOCKER_SOLR_PORT', 'DOCKER_JENKINS_HTTP_PORT', 'DOCKER_TOMCATDEBUG_PORT',
                     'ZFIN_PORT_OFFSET', 'DOCKER_INSTANCE'] as Set

        def outEnv = new File(wt, 'docker/.env')
        zfinUtil.writeStackEnv(baseEnvFile, outEnv, owned, "added by new-feature.groovy for $name", [
                COMPOSE_PROJECT_NAME    : project,
                DOCKER_SOURCE_ROOTS_PATH: wtPath,
                DOCKER_VIRTUAL_HOST     : host,
                ZFIN_PORT_OFFSET        : offset,
                DOCKER_DB_PORT          : "$bind:$dbPort",
                DOCKER_JENKINS_HTTP_PORT: "$bind:$jenkPort",
                DOCKER_TOMCATDEBUG_PORT : "$bind:$debugPort",
                DOCKER_HTTPD_HTTP_PORT  : "$bind:$httpPort",
                DOCKER_HTTPD_HTTPS_PORT : "$bind:$httpsPort"])
// A branch we didn't create isn't ours to delete: teardown force-deletes the feature's
// branch, which would be a nasty surprise for work that predates the stack. Record the
// provenance here (the .env is the per-feature record `z feature rm` already reads) so
// rm keeps the branch instead.
        // The routed-feature keys, from the one definition both this and `z feature refresh`
        // use. See StackConfig.featureEnv for what each is and why it must be present.
        outEnv << "# feature stack -- see StackConfig.featureEnv (z feature refresh backfills these)\n"
        def (gitCommon, gitDir) = zfinUtil.gitDirs(wt)
        StackConfig.featureEnv(gitCommon, gitDir).each { k, v -> outEnv << "$k=$v\n" }
        if (useExisting) outEnv << "ZFIN_FEATURE_BRANCH_PREEXISTING=1\n"

        outEnv << "DOCKER_INSTANCE=${StackConfig.FEATURE_INSTANCE}\n"

// 2b. No per-worktree bundle. The stack's identity lives in its own docker/.env, and its
//     compose files come from THIS checkout -- so there is nothing to copy, nothing to go
//     stale, and nothing to refresh after the tooling changes.
        // An own-data stack needs NO data overlay now: its volumes are restored from a seed
        // before anything starts, so the stock db/solr images find their data already there.
        def dataOverlay = doSharedDb ? 'docker-compose.overlay-shared-db.yml' : null
        // The feature overlay after the data overlay, and the proxy network last, so their httpd
        // changes stay authoritative over any data overlay that grows an opinion about httpd.
        def overlays = ([dataOverlay] - null) + ['docker-compose.overlay-feature.yml'] +
                       (proxyNet ? ['docker-compose.overlay-proxy-network.yml'] : [])
        // Recorded in the .env so this stack's composition is knowable from the stack itself.
        // One line rather than a copied bundle of compose files, which could silently fall
        // behind the tooling it was copied from.
        outEnv << "ZFIN_COMPOSE_OVERLAYS=${overlays.join(':')}\n"
        // The network the proxy-network overlay names. Recorded per stack: it is what this stack
        // joined, and every later compose run on it must interpolate the same name.
        if (proxyNet) outEnv << "ZFIN_PROXY_NETWORK=${proxyNet}\n"
        if (tag) outEnv << "ZFIN_SEED=$tag\n"
        // A feature's tree is always a worktree, so it also gets the git mounts (stackSpec adds
        // the same overlay on every later command, from the tree itself).
        def composeFiles = ([new File(DOCKER, 'docker-compose.yml').absolutePath] +
                            (overlays + ['docker-compose.overlay-worktree.yml']).collect { new File(zfinUtil.COMPOSE, it).absolutePath }).join(':')

// 4. Compose command: the ORIGIN checkout's compose files plus this worktree's .env -- the
//    same pair `z` will resolve later from ZFIN_COMPOSE_OVERLAYS, so provisioning and every
//    later command act on an identical stack definition.
        def compose = ['docker', 'compose',
                       '--project-name', project,
                       '--env-file', outEnv.absolutePath] +
                      composeFiles.tokenize(':').collectMany { ['-f', it] }

// Warm volumes: populate the SHARED deploy volumes (and, with --caches, the build
// caches) from the tarballs BEFORE any container mounts them. Docker only seeds an
// EMPTY volume from an image on first mount, and no image carries this content, so
// these would otherwise come up empty (and tomcat/httpd would crash-loop). We
// pre-create each volume (with compose's labels so compose adopts it as a project
// volume) and extract the tarball into it, using the compile image as a tar-capable,
// root-runnable container (see tarImage below). Done regardless of --up so the volumes
// are warm whenever their services first start.
// The volumes are independent, so extract them CONCURRENTLY -- wall-clock drops toward the
// slowest single volume (gradle_cache) instead of the sum, and the per-container startups
// overlap. Each thread creates its volume + untars into it, capturing exit + merged output;
// results land in per-index slots (no contention). Timing/errors are printed after join, in
// order, so the concurrent output doesn't interleave. (Raw gzip is ~1s; the cost is
// container startup + writing many small files, which parallelism overlaps.)
        // Restore is ZfinUtil.restoreVolumes -- the same code `z feature thaw` uses, so a warm
        // snapshot and a freeze archive come back by one path. This wrapper only reports.
        def volSecs = [:]
        def restore = { List vns ->
            // restoreVolumes reports each volume as it lands; this only records the numbers
            // for the timing summary at the end.
            def results = zfinUtil.restoreVolumes(project, vns, auxDir)
            results.each { r -> volSecs[r.vn] = [secs: r.secs, mb: r.mb] }
            def failed = results.findAll { !it.ok }
            if (failed) die("warm restore failed: ${failed.collect { it.vn }.join(', ')}\n" + failed.collect { it.err }.join('\n'))
        }
        // ONE call, not three: restoreVolumes runs its list concurrently, so the ~16G pg_data
        // overlaps the small ones instead of following them. This is where an own-data stack's
        // database actually arrives (why tarballs rather than images: see Seed.groovy).
        def warmT0 = System.currentTimeMillis()
        if (toRestore) {
            step('restore volumes')
            restore(toRestore)
            info(String.format("restore total: %s", zfinUtil.dur((System.currentTimeMillis() - warmT0) / 1000.0)))
        }

        // Caches the seed did not carry start as empty volumes; see prepareCacheVolumes.
        zfinUtil.prepareCacheVolumes(project)

        // The host's development certificate, replacing whatever the seed carried, so one
        // trusted certificate covers every stack (z cert). Not fatal: without it the stack
        // still serves, with the certificate its compile container makes.
        step('TLS certificate')
        if (!zfinUtil.installDevCert(project))
            System.err.println("!! could not install the host certificate; the stack makes its own. Retry: z cert install $slug")

// node_modules is git-ignored (absent in a fresh worktree) and NOT in the warm TARGETROOT,
// and `gradle dirtydeploy` runs npmBuild (webpack) WITHOUT npmInstall -- so a fresh worktree
// needs `npm ci` once or dirtydeploy fails with "webpack: not found". Do it in the compile
// container (no db/solr needed); npmInstall is up-to-date-skipped on later runs. --no-node skips.
        if (doNode) {
            step('npm ci')
            info("installing node deps in compile (gradle npmInstall / npm ci) -- one-time for this worktree...")
            runCommand(compose + ['run', '--rm', StackConfig.BUILD_SERVICE, 'bash', '-l', '-c', 'gradle npmInstall'])
        }

// Bring up the restored data tier (instantly ready). With a warm app tier, also start
// tomcat/httpd -> the stack comes up serving. Without one, leave the app tier down: its
// volumes (TARGETROOT/CATALINA_BASE) are empty until the webapp is built+deployed, so it
// would crash-loop. Services like ncbiload/jenkins/blast are irrelevant to a feature.
// The compile container's first run sets up the TLS cert + tomcat config.
// --shared-db: the data tier is the external shared stack, so bring up only the app tier
// (and only if it's warm). Otherwise this feature's own restored db/solr + app tier.
        if (doUp && services) step("start ${services.join(' ')}")
        if (doUp && services && doSharedDb) {
            // Shared-db: create the app tier + this feature's default network WITHOUT starting, connect
            // the shared db/solr into that network (so `db`/`solr` resolve at tomcat startup), THEN
            // start. The app tier stays single-homed -- see ZfinUtil.connectSharedData for why.
            info("${compose.join(' ')} up --no-start ${services.join(' ')}")
            runCommand(compose + ['up', '--no-start'] + services)
            zfinUtil.connectSharedData(project)
            info("${compose.join(' ')} start ${services.join(' ')}")
            runCommand(compose + ['start'] + services)
        } else if (doUp && services) {
            info("${compose.join(' ')} up -d ${services.join(' ')}")
            runCommand(compose + ['up', '-d'] + services)
        } else if (doUp) {
            info("nothing to auto-up yet (shared data tier is external; build+deploy, then z up tomcat httpd)")
        }

// The two build steps the next: block used to just recommend. Both are NON-FATAL on
// purpose (check:false): by this point the worktree, .env, volumes and
// containers all exist and are usable, so a gradle failure is something to REPORT -- letting
// runCommand's die() unwind here would kill the summary that tells you where all of it is,
// and leave you with a provisioned stack and no idea of its url or how to activate it.
        def failed = []
        def gradleIn = { String task ->
            def code = runCommand(compose + ['run', '--rm', StackConfig.BUILD_SERVICE, 'bash', '-l', '-c', "gradle $task"],
                    [check: false])
            if (code != 0) failed << task
        }
        if (doDeploy) { step('gradle dirtydeploy'); gradleIn('dirtydeploy') }
        if (doLiquibase) { step('gradle liquibasePostBuild'); gradleIn('liquibasePostBuild') }

// tmux: the one line of the next: block a child process can never run for you. `cd` mutates
// the CALLING shell, and z is a JVM the shell forked -- it can't reach back up. So instead of
// asking you to type it, hand over a shell that has already done it: a DETACHED session named
// after the slug, rooted in the worktree. Detached-then-attach (rather than exec'ing into
// tmux) is what keeps
// the provisioning log on your terminal's scrollback and lets the session outlive z --
// Ctrl-b d gets out, `tmux attach -t <slug>` gets back in, `z feature rm` kills it.
        def tmuxReady = false
        if (doTmux) {
            step("tmux session '$slug'")
            if (!zfinUtil.onPath('tmux')) {
                // An optional integration missing is a hint, not a failure -- the stack is
                // fine, you just get the cd/source lines to type.
                info("skipping: tmux is not installed (${zfinUtil.installHint('tmux')}) -- use the next: block by hand")
            } else if (zfinUtil.runQuietly(['tmux', 'has-session', '-t=' + slug]) == 0) {
                // '-t=' is tmux's EXACT-match form for a target-SESSION; plain '-t' also
                // prefix-matches, so re-provisioning 'zfin-10' would find a running 'zfin-104'.
                info("tmux session '$slug' already exists -- reusing it")
                tmuxReady = true
            } else {
                runCommand(['tmux', 'new-session', '-d', '-s', slug, '-c', wtPath])
                // The `cd` is not redundant with new-session's -c. tmux sets the pane's start
                // directory, then the LOGIN shell runs .bash_profile on top -- and a profile
                // that ends in `cd ~/zfin` (this one does) silently lands you in the primary
                // checkout, where `z` would resolve the wrong tree. Sending the cd re-asserts it
                // after the profile has had its say.
                // send-keys takes a target-PANE, and '=' is not part of that grammar ("can't
                // find pane: =<slug>") -- a bare session name is the right target here, and it
                // is unambiguous anyway: has-session just told us this exact name was free.
                runCommand(['tmux', 'send-keys', '-t', slug,
                            "cd '$wtPath'", 'C-m'])
                info("tmux session '$slug' ready: cwd $wtPath (z resolves this stack from here)")
                tmuxReady = true
            }
            // status-left is "[#{session_name}] " truncated at status-left-length, which
            // defaults to 10 -- so "[zfin-12345] " loses its tail and every feature session
            // looks alike in the status bar. Widened for OUR session only: `set -g` would be
            // one character of tooling rewriting how every other session on this user's tmux
            // server looks, which is not ours to decide. Applied on the reuse path too, so a
            // session made before this existed gets fixed on the next provision. Cosmetic, so
            // check:false -- a tmux quirk here must not fail a provisioned stack.
            if (tmuxReady) runCommand(['tmux', 'set-option', '-t', slug,
                                       'status-left-length', '30'], [check: false])
        }
        def willAttach = tmuxReady && con != null
        // Close the last step here, before the summary and the attach: the report ends at the
        // last mark, and time spent attached to tmux is not provisioning.
        if (openStep[0]) { timer.mark(openStep[0]); openStep[0] = null }

// The printed next: block adapts to what this run actually DID. Telling you to cd into the
// worktree when a tmux session is already sitting in it, or to
// run dirtydeploy a second after we ran it, is the kind of noise that trains people to
// skip the block entirely -- so every line below is either something still left to do or
// something that failed and needs re-running.
        def imagesLine = doSharedDb
                ? "     data     : SHARED ${zfinUtil.sharedProject()} db/solr (connected into ${project}_default)" + (warmApp ? "  + warm app (seed $tag)" : "")
                : cold ? "     data     : own copy, COLD -- db and solr are empty until loaded"
                : (warmApp ? "     data     : own copy, restored from seed '$tag'  + warm app tier"
                : "     data     : own copy, restored from seed '$tag'")
        def bringUp = doSharedDb
                ? (warmApp
                ? (doUp ? "  # tomcat+httpd up, serving $base's deploy on the SHARED db/solr at $url"
                : "  z up tomcat httpd                  # app tier (data is the shared ${zfinUtil.sharedProject()} stack)")
                : "  # data tier is the shared ${zfinUtil.sharedProject()} stack (needs `z shared up`); build+deploy, then z up tomcat httpd")
                : cold
                ? "  z build load-db load-solr          # COLD: load db + solr first (starts them itself)"
                : (doUp
                ? (warmApp ? "  # db+solr+tomcat+httpd already up -- serving $base's deploy at $url"
                : "  # data tier (db + solr) is already up.")
                : (warmApp ? "  z up db solr tomcat httpd          # full stack: instant (data + warm app tier)"
                : "  z up db solr                       # data tier: already restored from the seed"))

// Built as a line list rather than one interpolated heredoc: which lines belong here now
// depends on warmApp x doDeploy x doLiquibase x did-it-fail, and nesting that many ternaries
// inside a multi-line GString is how the two variants silently drift apart.
        def dl = []
        if (warmApp) {
            dl << "  # the app tier is already serving $base's code from the warm snapshot."
            if (failed.contains('dirtydeploy')) dl << "  # !! dirtydeploy FAILED above -- fix, then re-run:"
            else if (doDeploy)                  dl << "  # THIS branch is deployed on top of it. Re-run after each edit:"
            else                                dl << "  # Deploy THIS branch's changes on top (fast, incremental):"
            dl << '  z run -c "gradle dirtydeploy"'
        } else {
            dl << (cold ? "  # first-time build + deploy, once the data is loaded;"
                        : "  # first-time build + deploy. The seed carries DB/Solr, so SKIP the load steps;")
            dl << "  # the compile container's first run also provisions the TLS cert (the ZFIN repo's reference/build-and-docker.md §1,§5):"
            dl << '  z run -c "ant do && gradle make && ant deploy-catalina-base && ant deploy-without-tests-and-tomcat-restart"'
            dl << "  z up tomcat httpd                  # app tier -> $url"
            dl << "  # fast edit -> see loop thereafter:"
            dl << '  z run -c "gradle dirtydeploy"'
        }
        if (doLiquibase && !failed.contains('liquibasePostBuild')) {
            dl << "  # this branch's schema/solr deltas are already applied (liquibasePostBuild ran)."
        } else {
            dl << (failed.contains('liquibasePostBuild')
                    ? "  # !! liquibasePostBuild FAILED above -- fix, then re-run:"
                    : cold ? "  # this branch's schema/solr deltas (only if it changes them):"
                    : "  # this branch's schema/solr deltas on top of the seed (only if it changes them):")
            dl << '  z run -c "gradle liquibasePostBuild"'
        }
        def deploySteps = dl.join('\n')

// How you get INTO the stack: a live tmux session if we made one (nothing to type), the
// cd + source pair otherwise.
        def enterLines = tmuxReady
                ? (willAttach ? "  # tmux session '$slug' is live in the worktree -- attaching below."
                              : "  tmux attach -t $slug                 # sitting in the worktree")
                : "  cd $wtPath                           # z commands resolve to '$project' from here"

        def ran = []
        if (doNode) ran << 'npm ci'
        if (doUp && services) ran << "up ${services.join('+')}"
        if (doDeploy) ran << 'dirtydeploy' + (failed.contains('dirtydeploy') ? ' (FAILED)' : '')
        if (doLiquibase) ran << 'liquibasePostBuild' + (failed.contains('liquibasePostBuild') ? ' (FAILED)' : '')

        println """
>> provisioned $name
     worktree : $wtPath
     branch   : $branch  ${useExisting ? '(existing)' : "(off $base)"}
     project  : $project
     url      : $url${proxyNet ? "   (direct: $directUrl)" : ''}
     ports    : https $bind:$httpsPort   http $bind:$httpPort   db $bind:$dbPort   debug $bind:$debugPort   jenkins $bind:$jenkPort
$imagesLine
     use      : cd $wtPath   (z commands there resolve to '$project')${tmuxReady ? "\n     tmux     : tmux attach -t $slug   (session left running; Ctrl-b d to detach)" : ''}${ran ? "\n     ran      : ${ran.join('  ->  ')}" : ''}

next:
$enterLines
$bringUp
$deploySteps

teardown:
  z feature rm $slug                   # all of the below, automated (prompts first)
  # ...or by hand:
  z stop                             # just pause it: containers stopped, data kept (z up resumes)
  z down -v                          # remove containers + THIS stack's DB/Solr/app copy
  git worktree remove $wtPath${tmuxReady ? "\n  tmux kill-session -t $slug           # drop this feature's shell" : ''}
"""
        if (failed)
            System.err.println("!! ${failed.size()} post-provision step(s) failed: ${failed.join(', ')} -- the stack itself is provisioned; re-run them from the next: block above.")

// Attach LAST, after the summary has been printed, so it stays on the terminal's scrollback
// behind the tmux screen. Inside tmux already (z run from a pane), attach-session would
// refuse to nest -- switch-client moves the existing client to the new session instead.
        if (willAttach) {
            if (System.getenv('TMUX')) {
                info("switching this tmux client to '$slug'")
                runCommand(['tmux', 'switch-client', '-t=' + slug], [check: false])
            } else {
                info("attaching to '$slug' (Ctrl-b d to detach; the stack keeps running)")
                runCommand(['tmux', 'attach-session', '-t=' + slug], [check: false])
            }
        } else if (tmuxReady) {
            info("no TTY to attach to -- `tmux attach -t $slug` when you're back at a terminal")
        }

        timer.report("provision '$slug' timing")
        if (volSecs) {
            // Restores run CONCURRENTLY, so these sum to more than the step they belong to --
            // that gap is the parallelism doing its job, not an accounting error.
            println "    per volume (concurrent):"
            volSecs.sort { -it.value.secs }.each { vn, v ->
                println String.format("      %-16s %11s  %8.0f MB  %6.0f MB/s",
                        vn, zfinUtil.dur(v.secs), v.mb, v.secs > 0 ? v.mb / v.secs : 0)
            }
        }
    }
}
