// SeedBuild -- `z seed build`: make a seed from a db dump (and a solr snapshot) by running the
// whole build in a throwaway stack, capturing the result, and throwing the stack away.
//
//   z seed build [--db PATH|latest] [--solr PATH|latest] [--tag TAG] [--ref REF]
//                [--db-platform PLATFORM] [--caches] [--build | --pull] [--keep] [--tmux]
//   z seed build --tag TAG --resume     carry on with a build that stopped
//   z seed build --tag TAG --clean      discard one: its stack, worktree and staging
//
//   --db PATH     a .bak file, or a directory holding one. Default (or `latest`): the dump
//                 `gradle loaddb` would pick from DOCKER_DB_UNLOADS_PATH.
//   --solr PATH   a snapshot.* directory. Default (or `latest`): the newest snapshot under
//                 DOCKER_SOLR_UNLOADS_PATH/zfindb, which is what getLatestSolrIndex restores.
//   --tag TAG     the seed's name (default: today, YYYY-MM-DD).
//   --ref REF     the commit whose code builds the app tier (default: HEAD of the checkout you
//                 are standing in). Committed code only: the build runs in its own worktree.
//   --db-platform PLATFORM
//                 run the db alone on PLATFORM (linux/amd64 or linux/arm64) instead of this
//                 host's. On an arm64 Mac, linux/amd64 writes the data directory with the same
//                 amd64 image the Linux VMs run, so the seed restores there; the seed records it
//                 as its `platform`. Postgres runs under Rosetta then -- roughly 1.3-1.7x slower
//                 for a load -- while compile and tomcat stay native.
//   --caches      also capture gradle/maven/npm into the seed (as z seed create --caches).
//   --build       build the stock images instead of pulling them (z build configure --build).
//   --pull        pull the stock images even when this host has them. Off by default: image
//                 tags are host-wide, so a pull replaces a locally built image for every stack
//                 here -- and the stacks restored from this seed run this host's images anyway.
//   --keep        leave the build stack up after capturing, to look at it; --clean removes it.
//   --tmux        run in a detached tmux session and attach to it, so the build outlives the
//                 terminal that started it.
//
// WHAT RUNS. The phases GoCD runs -- `z build` configure, load-db, load-solr, deploy-jenkins,
// deploy -- then `z seed create` against the result. No second build recipe, and the capture
// gets the WAL trim, checksums and app-tier check every seed gets. The manifest also records
// what the seed was built from (`built_from`: dump, its checksum, snapshot, commit), which a
// seed captured from an existing stack cannot know.
//
// WHERE. A Compose project of its own, seedbuild-<tag>. It publishes its ports on ephemeral
// 127.0.0.1 ports and advertises no hostname to any proxy, so it runs beside the base stack
// and every feature without touching them. Its source tree is a detached git worktree under
// the staging directory rather than your checkout, because `gradle make` writes build/ and
// home/WEB-INF/zfin.properties into the tree it builds: sharing a checkout would overwrite that
// checkout's own stack's generated config.
//
// INPUTS. Read in place, never copied: the build stack mounts the dump's directory and the
// snapshot's parent directory READ-ONLY (DOCKER_UNLOADS_MODE=:ro) and names the exact files to
// the gradle tasks (`z build load-db --dump`, `load-solr --snapshot`), so the choice does not
// depend on which entry in that directory is newest -- a dump landing there mid-build, or before
// a --resume, changes nothing. $ZFIN_CACHE_DIR/seed-build/<tag>/ (default $ZFIN_DEV_ROOT/cache)
// holds only the stack's env file, the worktree (seedbuild-<tag>/) and state.json.
//
// RESUMING. The build takes hours and can fail in many places. state.json records each finished
// phase, and a failed phase leaves the stack and its volumes as they were, so --resume carries
// on from the phase that stopped instead of reloading the database.
class SeedBuild {
    static final List<String> BUILD_PHASES = ['configure', 'load-db', 'load-solr', 'deploy-jenkins', 'deploy']

    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return
        def die = zfinUtil.&die; def info = zfinUtil.&info
        def runCommand = zfinUtil.&runCommand; def captureOutput = zfinUtil.&captureOutput
        def runQuietly = zfinUtil.&runQuietly

        def dbArg = null, solrArg = null, tag = null, ref = null, dbPlatform = null
        boolean caches = false, buildImages = false, pull = false, keep = false, tmux = false, resume = false, clean = false
        for (int i = 0; i < args.size(); i++) {
            switch (args[i]) {
                case '--db':     dbArg = args[++i]; break
                case '--solr':   solrArg = args[++i]; break
                case '--tag':    tag = args[++i]; break
                case '--ref':    ref = args[++i]; break
                case '--db-platform': dbPlatform = args[++i]; break
                case '--caches': caches = true; break
                case '--build':  buildImages = true; break
                case '--pull':   pull = true; break
                case '--keep':   keep = true; break
                case '--tmux':   tmux = true; break
                case '--resume': resume = true; break
                case '--clean':  clean = true; break
                default: die("z seed build: unknown arg '${args[i]}' (see z seed build --help)", 2)
            }
        }
        tag = tag ?: java.time.LocalDate.now().toString()
        if (!(tag ==~ /[A-Za-z0-9][A-Za-z0-9._-]*/)) die("--tag: letters, digits, '.', '_' and '-' only (got '$tag')", 2)
        if (resume && clean) die("--resume and --clean are opposites; pick one", 2)
        if (dbPlatform != null && StackConfig.archSuffix(dbPlatform) == null)
            die("--db-platform: linux/amd64 or linux/arm64 (got '$dbPlatform')", 2)

        def project = 'seedbuild-' + tag.toLowerCase().replaceAll(/[^a-z0-9_-]/, '-')
        def stage = new File(zfinUtil.cacheDir().replaceFirst('^~', System.getProperty('user.home')), "seed-build/$tag")
        def stateF = new File(stage, 'state.json')
        def envF = new File(stage, 'stack.env')
        // Named after the project, not `src`: git registers a worktree under its directory's
        // name, and `git worktree list` should say what this one is.
        def src = new File(stage, project)
        def baseEnv = new File(zfinUtil.DOCKER, '.env')
        // Base compose plus the worktree overlay: the build runs in its own git worktree, and the
        // ant build needs git. Colon-joined, as COMPOSE_FILE takes it (set below).
        def composeFile = [new File(zfinUtil.DOCKER, 'docker-compose.yml'),
                           new File(zfinUtil.COMPOSE, 'docker-compose.overlay-worktree.yml')]*.absolutePath.join(':')
        def compose = ['docker', 'compose', '-p', project, '--env-file', envF.absolutePath] +
                      composeFile.tokenize(':').collectMany { ['-f', it] }
        def cwd = new File('.').canonicalFile
        def repoTop = captureOutput(['git', '-C', cwd.absolutePath, 'rev-parse', '--show-toplevel']) ?: zfinUtil.REPO.absolutePath
        def inspectHint = "COMPOSE_PROJECT_NAME=$project COMPOSE_FILE=$composeFile COMPOSE_ENV_FILES=$envF z log tomcat"

        // ---- --tmux: re-run this same command inside a session, then attach -----------------
        if (tmux && !System.getenv('ZFIN_SEED_BUILD_IN_TMUX')) {
            if (!zfinUtil.onPath('tmux')) die("--tmux: tmux is not installed (${zfinUtil.installHint('tmux')})")
            if (runQuietly(['tmux', 'has-session', '-t=' + project]) == 0)
                die("a tmux session '$project' already exists -- it may be this build, still running:\n" +
                    "   tmux attach -t $project")
            // --tag pinned, so a build started just before midnight does not change its name.
            def inner = (args - ['--tmux']) + (args.contains('--tag') ? [] : ['--tag', tag])
            def q = { String a -> "'" + a.replace("'", "'\\''") + "'" }
            def cmd = "ZFIN_SEED_BUILD_IN_TMUX=1 ${q(new File(zfinUtil.HOME, 'z').absolutePath)} seed build " +
                      inner.collect { q(it as String) }.join(' ') +
                      "; echo; read -r -p '[z seed build finished -- Enter closes this window] ' _"
            runCommand(['tmux', 'new-session', '-d', '-s', project, '-c', cwd.absolutePath, cmd])
            info("seed build running in tmux session '$project' (Ctrl-b d to detach; it keeps running)")
            if (System.console() == null) { info("reattach with:  tmux attach -t $project"); return }
            runCommand(System.getenv('TMUX') ? ['tmux', 'switch-client', '-t=' + project]
                                             : ['tmux', 'attach-session', '-t=' + project], [check: false])
            return
        }

        // Everything else targets the build stack. Replace the stack z resolved from the working
        // directory rather than adding to it: Zbuild and Seed read these, and a leftover
        // ZFIN_SEED or the cwd's project would point them at the wrong stack.
        ['ZFIN_STACK_PROJECT', 'ZFIN_STACK_DIR', 'ZFIN_STACK_HOST', 'ZFIN_SEED'].each { zfinUtil.childEnv.remove(it) }
        zfinUtil.childEnv['COMPOSE_PROJECT_NAME'] = project
        zfinUtil.childEnv['COMPOSE_FILE'] = composeFile
        zfinUtil.childEnv['COMPOSE_ENV_FILES'] = envF.absolutePath

        def teardown = {
            if (envF.isFile()) {
                info("down -v the build stack '$project'")
                runCommand(compose + ['down', '-v', '--rmi', 'local'], [check: false])
                zfinUtil.sweepProjectVolumes(project)
            }
            if (src.isDirectory()) {
                zfinUtil.reclaimOwnership(src)
                info("removing the build worktree $src")
                runCommand(['git', '-C', repoTop, 'worktree', 'remove', '--force', src.absolutePath], [check: false])
            }
            if (stage.isDirectory() && !stage.deleteDir()) System.err.println("!! could not remove $stage -- remove it by hand")
            // After the delete: a worktree whose directory is gone is what prune clears.
            runQuietly(['git', '-C', repoTop, 'worktree', 'prune'])
        }

        // ---- --clean -------------------------------------------------------------------------
        if (clean) {
            if (!stage.isDirectory() && !zfinUtil.volumeExists("${project}_pg_data")) {
                info("nothing to clean for '$tag' (no $stage, no $project volumes)"); return
            }
            teardown()
            info("discarded the seed build for '$tag' (no seed was touched)")
            return
        }

        // ---- state: a new build, or the one we are resuming ----------------------------------
        def save = { Map st -> stateF.text = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(st)) }
        def allPhases = BUILD_PHASES + ['capture', 'teardown']
        def state = stateF.isFile() ? new LinkedHashMap(new groovy.json.JsonSlurper().parse(stateF) as Map) : null
        def nextPhase = { Map st -> allPhases.find { !(it in (st.done ?: [])) } }
        if (state && !resume)
            die("a seed build for '$tag' already exists at $stage (next phase: ${nextPhase(state)}).\n" +
                "   carry on with it:  z seed build --tag $tag --resume\n" +
                "   or discard it:     z seed build --tag $tag --clean")
        if (resume && !state) die("no seed build for '$tag' to resume (looked for $stateF)")

        def timer = zfinUtil.stepTimer()
        if (state) {
            state.done = new ArrayList(state.done ?: [])
            caches = caches || state.caches
            buildImages = buildImages || state.build
            info("resuming seed build '$tag' at ${nextPhase(state)}  (done: ${state.done ? state.done.join(', ') : 'nothing yet'})")
            // A phase marked done is only worth skipping if what it produced is still there.
            if ('load-db' in state.done && !zfinUtil.volumeExists("${project}_pg_data"))
                die("the build stack's database volume is gone (${project}_pg_data), so the finished\n" +
                    "   phases cannot be trusted. Start over:  z seed build --tag $tag --clean")
        } else {
            state = prepare(tag, project, dbArg, solrArg, ref, dbPlatform, caches, buildImages, stage, envF, src,
                            baseEnv, repoTop, zfinUtil)
            save(state)
        }
        timer.mark('prepare')

        // If the JVM exits before the end -- a phase dies, or ^C -- say where it stopped and how
        // to carry on. die() is System.exit, so this is the only place that message can live.
        def running = [phase: null, finished: false]
        Runtime.runtime.addShutdownHook(new Thread({
            if (running.finished || !running.phase) return
            System.err.println("")
            System.err.println("!! seed build '$tag' stopped during ${running.phase}.")
            System.err.println("   The build stack is left as it was, so you can look at it, e.g.:")
            System.err.println("     $inspectHint")
            System.err.println("   carry on from ${running.phase}:  z seed build --tag $tag --resume")
            System.err.println("   or discard it:        z seed build --tag $tag --clean")
        } as Runnable))

        // ---- the build ----------------------------------------------------------------------
        zfinUtil.prepareCacheVolumes(project)
        BUILD_PHASES.eachWithIndex { ph, i ->
            def label = "[${i + 1}/${BUILD_PHASES.size() + 1}] $ph"
            if (ph in state.done) { info("$label -- done in an earlier run, skipping"); return }
            println "\n>> seed build $label"
            running.phase = ph
            def extra = ph == 'configure' ? (buildImages ? ['--build'] : (pull ? [] : ['--pull-missing']))
                      : ph == 'load-db' && state.db?.container ? ['--dump', state.db.container]
                      : ph == 'load-solr' && state.solr?.container ? ['--snapshot', state.solr.container]
                      : []
            new Zbuild().run([ph] + extra, zfinUtil)
            state.done << ph; save(state)
            timer.mark(ph)
        }

        // ---- capture --------------------------------------------------------------------------
        def label = "[${BUILD_PHASES.size() + 1}/${BUILD_PHASES.size() + 1}] capture seed '$tag'"
        // seed.json is written last, so its presence means an earlier run captured completely.
        if (!('capture' in state.done) && zfinUtil.readSeedManifest(zfinUtil.seedDir(tag))) {
            state.done << 'capture'; save(state)
        }
        if ('capture' in state.done) {
            info("$label -- done in an earlier run, skipping")
        } else {
            println "\n>> seed build $label"
            running.phase = 'capture'
            zfinUtil.seedProvenance = [
                    how           : 'z seed build',
                    db_dump       : state.db.name,
                    db_dump_sha256: state.db.sha256,
                    solr_snapshot : state.solr.name,
                    ref           : state.ref,
                    commit        : state.commit]
            new Seed().run(['create', '--from', project, '--tag', tag] + (caches ? ['--caches'] : []), zfinUtil)
            state.done << 'capture'; save(state)
            timer.mark('capture')
        }

        // ---- teardown -------------------------------------------------------------------------
        running.phase = 'teardown'
        if (keep) {
            running.finished = true
            info("--keep: the build stack '$project' is still up. Look at it with, e.g.:")
            println "     $inspectHint"
            info("remove it when done:  z seed build --tag $tag --clean")
        } else {
            teardown()
            running.finished = true
            timer.mark('teardown')
        }

        info("seed '$tag' is ready:  z feature new <ticket> --seed $tag")
        timer.report("seed build '$tag' timing")
    }

    /** Preflight, stage the inputs, make the worktree and the env file. Everything that can
     *  refuse runs before anything is created, so a bad argument leaves nothing behind. */
    private Map prepare(String tag, String project, String dbArg, String solrArg, String ref,
                        String dbPlatform, boolean caches, boolean buildImages, File stage, File envF, File src,
                        File baseEnv, String repoTop, ZfinUtil zfinUtil) {
        def die = zfinUtil.&die; def info = zfinUtil.&info
        def captureOutput = zfinUtil.&captureOutput; def runCommand = zfinUtil.&runCommand
        def expand = { String p -> new File(p.replaceFirst('^~', System.getProperty('user.home'))).absoluteFile }

        if (!zfinUtil.env('ZFIN_RELEASE')) die("ZFIN_RELEASE must be set (from docker/.env or the environment)")
        if (zfinUtil.readSeedManifest(zfinUtil.seedDir(tag)) || (zfinUtil.seedDir(tag).listFiles() ?: []).any { it.isFile() })
            die("seed '$tag' already exists at ${zfinUtil.seedDir(tag)}\n" +
                "   Pick another --tag, or remove it:  z seed rm $tag")
        if (zfinUtil.volumeExists("${project}_pg_data"))
            die("volumes for '$project' already exist but there is no build state in $stage.\n" +
                "   Clear them first:  z seed build --tag $tag --clean")

        // ---- the db dump --------------------------------------------------------------------
        File dump; boolean configuredDb
        if (!dbArg || dbArg == 'latest') {
            def root = zfinUtil.envPath('DOCKER_DB_UNLOADS_PATH')
            def pick = zfinUtil.newestDbDump(root)
            if (pick.error) die("db dump: ${pick.error}")
            dump = pick.file; configuredDb = true
        } else {
            def f = expand(dbArg)
            if (f.isDirectory()) f = ((f.listFiles() ?: []) as List).findAll { it.isFile() }.sort { it.name }.with { it ? it.last() : null }
            if (!f?.isFile()) die("--db $dbArg: no dump there (a .bak file, or a directory holding one)")
            if (f.length() == 0) die("--db $f is empty")
            dump = f; configuredDb = false
        }

        // ---- the solr snapshot --------------------------------------------------------------
        File snap; boolean configuredSolr
        if (!solrArg || solrArg == 'latest') {
            def root = zfinUtil.envPath('DOCKER_SOLR_UNLOADS_PATH')
            def snaps = root ? zfinUtil.solrSnapshots(root) : []
            if (!snaps) die("solr: no snapshot.* dir under ${root ? new File(root, 'zfindb') : 'DOCKER_SOLR_UNLOADS_PATH (unset)'}.\n" +
                            "   Name one:  --solr <path>/snapshot.<timestamp>")
            snap = snaps[0]; configuredSolr = true
        } else {
            def d = expand(solrArg)
            if (!d.isDirectory() || !d.name.startsWith('snapshot.'))
                die("--solr $solrArg: expected a Solr 9 snapshot.* directory (what `gradle getsolr` fetches)")
            snap = d; configuredSolr = false
        }

        // ---- the code -----------------------------------------------------------------------
        def commit = captureOutput(['git', '-C', repoTop, 'rev-parse', '--verify', '--quiet', "${ref ?: 'HEAD'}^{commit}"])
        if (!commit) die("--ref ${ref ?: 'HEAD'}: not a commit in $repoTop")
        if (!ref && captureOutput(['git', '-C', repoTop, 'status', '--porcelain', '--untracked-files=no']))
            System.err.println("!! $repoTop has uncommitted changes. They are NOT in this build -- it builds ${commit.take(10)}.")

        // ---- room for the result ------------------------------------------------------------
        // The seed lands in the archive dir, compressed to very roughly half the inputs.
        // A warning, not a refusal: the estimate is loose and the archive may be on NFS
        // whose free space the JVM reports poorly.
        def dirBytes = { File d -> long n = 0; d.eachFileRecurse(groovy.io.FileType.FILES) { n += it.length() }; n }
        long want = (dump.length() + dirBytes(snap)) / 2
        def archive = new File(zfinUtil.archiveDir())
        def probe = archive; while (probe && !probe.exists()) probe = probe.parentFile
        if (probe && probe.usableSpace < want)
            System.err.println(String.format("!! %s has %.1fG free; this seed will need roughly %.1fG.",
                    probe, probe.usableSpace / 1073741824.0, want / 1073741824.0))

        info("seed build '$tag'  project=$project  code=${ref ?: 'HEAD'} @ ${commit.take(10)}" +
             (dbPlatform ? "  db=$dbPlatform" : ''))
        info("  db   : $dump${configuredDb ? '  (newest in the configured unloads)' : ''}")
        info("  solr : $snap${configuredSolr ? '  (newest in the configured unloads)' : ''}")
        info("  both read in place, mounted read-only")

        // The worktree first: which loaddb it builds decides how the dump can be mounted.
        runCommand(['git', '-C', repoTop, 'worktree', 'add', '--detach', src.absolutePath, commit])

        // ---- mount the inputs read-only; the tasks are told the exact file -------------------
        // The dump's GRANDPARENT, not its directory: loaddb scans for the newest dated
        // subdirectory before it looks at -DB, and in code that predates skipping that scan
        // (which --ref can name) a directory holding the .bak itself crashes it. Mounted one
        // level up, the dump sits in the dated-directory layout the scan expects, so any commit
        // works -- provided the scan itself would not fail there.
        def dbRoot = dump.parentFile.parentFile
        def skipsScan = new File(src, 'build.gradle').with { it.isFile() && it.text.contains('newest-directory scan below is then skipped') }
        if (!skipsScan) {
            def scan = zfinUtil.newestDbDump(dbRoot)
            if (scan.error)
                die("--db: ${commit.take(10)}'s loaddb scans ${dbRoot} for its newest dump before it reads\n" +
                    "   -DB, and that scan would fail:\n   ${scan.error}\n" +
                    "   Put the dump in a dated directory of its own there, or build from a --ref whose\n" +
                    "   build.gradle skips the scan when -DB is given.")
        }
        def paths = [DOCKER_DB_UNLOADS_PATH  : dbRoot.absolutePath,
                     DOCKER_SOLR_UNLOADS_PATH: snap.parentFile.absolutePath,
                     DOCKER_UNLOADS_MODE     : ':ro']
        stage.mkdirs()

                info("checksumming ${dump.name} (recorded in the seed's manifest)")
        def sha = zfinUtil.sha256(dump)

        // ---- the worktree + env -------------------------------------------------------------
        def (gitCommon, gitDir) = zfinUtil.gitDirs(src)
        // Ports: `127.0.0.1:` makes compose's "${VAR}:5432" read 127.0.0.1::5432 -- an EPHEMERAL
        // host port on loopback, so this stack can never collide with another. The vhost key is
        // present but EMPTY so no proxy on the host discovers it.
        def ephemeral = '127.0.0.1:'
        zfinUtil.writeStackEnv(baseEnv, envF, [], "z seed build '$tag'", [
                COMPOSE_PROJECT_NAME    : project,
                DOCKER_SOURCE_ROOTS_PATH: src.absolutePath,
                DOCKER_VIRTUAL_HOST     : zfinUtil.featureHost(project),
                DOCKER_EXTERNAL_VHOST   : '',
                DOCKER_DB_PORT          : ephemeral,
                DOCKER_SOLR_PORT        : ephemeral,
                DOCKER_HTTPD_HTTP_PORT  : ephemeral,
                DOCKER_HTTPD_HTTPS_PORT : ephemeral,
                DOCKER_JENKINS_HTTP_PORT: ephemeral,
                DOCKER_TOMCATDEBUG_PORT : ephemeral,
                DOCKER_SOLR_MEM_LIMIT   : StackConfig.FEATURE_SOLR_MEM,
                DOCKER_SOLR_HEAP        : StackConfig.FEATURE_SOLR_HEAP,
                DOCKER_GIT_COMMON_DIR   : gitCommon,
                DOCKER_GIT_WORKTREE_DIR : gitDir,
                DOCKER_INSTANCE         : StackConfig.FEATURE_INSTANCE,
                ZFIN_COMPOSE_OVERLAYS   : ''] + paths +
                (dbPlatform ? [DOCKER_DB_ARCH: StackConfig.archSuffix(dbPlatform), DOCKER_DB_PLATFORM: dbPlatform] : [:]))
        info("instance: ${StackConfig.FEATURE_INSTANCE}   env: $envF")

        [tag    : tag, project: project, created: new Date().format('yyyy-MM-dd HH:mm:ss'),
         ref    : ref ?: 'HEAD', commit: commit, repo: repoTop,
         // `container`: the same file as the build stack's containers see it.
         db     : [path: dump.absolutePath, name: dump.name, sha256: sha,
                   container: "/opt/zfin/unloads/db/${dump.parentFile.name}/${dump.name}"],
         solr   : [path: snap.absolutePath, name: snap.name, container: "/opt/zfin/unloads/solr/${snap.name}"],
         dbPlatform: dbPlatform ?: '', caches : caches, build: buildImages, done: []]
    }
}
