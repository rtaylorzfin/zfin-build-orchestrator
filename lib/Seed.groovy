#!/usr/bin/env groovy
// Seed -- capture a loaded stack's volumes as a reusable SEED ARCHIVE, and manage the seeds.
//
//   z seed new|create [--from PROJECT] [--tag TAG] [--no-app] [--caches]
//   z seed build [--db DUMP] [--solr SNAPSHOT] [--tag TAG] ...   (see z seed build --help)
//   z seed ls
//   z seed rm <tag> [--force]
//   z seed restore [<tag>] [--app] [--caches] [--force]
//
//   restore          Restore a seed into the stack that owns the working directory -- the
//                    base checkout's own stack, typically, which `z feature new` never touches.
//                    db+solr by default; --app adds the deployed app tier and Jenkins home,
//                    --caches the build caches. Existing volumes are replaced only with --force,
//                    and only once no container uses them (`z down` first).
//
//   --from PROJECT   Compose project to capture from (default: $COMPOSE_PROJECT_NAME).
//   --tag TAG        Seed name (default: today, YYYY-MM-DD).
//   --no-app         Skip the deploy-target volumes. They are small (~0.7G) and without them
//                    every feature comes up cold, so they are captured by DEFAULT.
//   --caches         Also capture gradle/maven/npm. Several GB, and regenerable, so OFF by
//                    default -- worth it on a host that makes many stacks.
//
// WHAT A SEED IS. A directory of per-volume tarballs plus a manifest, at
// $ZFIN_ARCHIVE_DIR/seeds/<tag>/. `z feature new --seed <tag>` restores it into a new stack.
// It is the same artifact shape `z feature freeze` writes, produced by the same
// ZfinUtil.captureVolume and consumed by the same ZfinUtil.restoreVolumes -- one capture and
// one restore implementation for the whole tool.
//
// A SIBLING of the per-stack freeze archives, never inside one: `z feature rm` deletes a
// stack's freeze archive, and a seed must survive that.
//
// WHY NOT IMAGES. Baking the same tarballs into a db/solr image costs three extra full
// copies of the data -- into the build context, into a layer, out again on export -- for an
// image whose layers cannot be shared anyway,
// because postgres declares VOLUME and Docker therefore COPIES the baked content into every
// stack's volume. Measured on cell 2026-09-18: restoring ~21.3G from tarballs takes 79.9s
// against ~170s for Docker to seed the same data from images, because tar streams where the
// daemon copies per file. Seeds are also files, so they can live on NFS; an image store
// cannot (overlayfs needs a local upperdir).
//
// The data is REAL ZFIN DATA. Seeds stay on storage you control. There is deliberately no
// upload path (see "Data-sensitivity guardrail" in docs/dev-stacks.md).
class Seed {
    def run(List args, ZfinUtil zfinUtil) {
        // Before the help guard, so `z seed build --help` prints SeedBuild's own header.
        if (args && args[0] == 'build') { new SeedBuild().run(args.drop(1), zfinUtil); return }
        if (zfinUtil.helpRequested(args, this)) return
        def die = zfinUtil.&die

        def sub = args ? args[0] : ''
        def rest = args.drop(1)
        switch (sub) {
            // `new` and `create` both, because `z feature new` reads as the verb for this and
            // guessing which noun takes which verb is not a thing anyone should have to do.
            case 'create': case 'new': create(rest, zfinUtil); break
            case 'ls': case 'list': list(rest, zfinUtil); break
            case 'rm': case 'remove': remove(rest, zfinUtil); break
            case 'restore': restore(rest, zfinUtil); break
            default: die("z seed: unknown '${sub}' (new|create|build|restore|ls|rm). See z seed --help.", 2)
        }
    }

    // ---- z seed ls ---------------------------------------------------------------------
    private void list(List args, ZfinUtil zfinUtil) {
        def dir = zfinUtil.seedsDir()
        if (!dir.isDirectory()) { zfinUtil.info("no seeds yet ($dir)"); return }
        def seeds = (dir.listFiles() ?: []).findAll { it.isDirectory() }.sort { it.name }
        if (!seeds) { zfinUtil.info("no seeds yet ($dir)"); return }
        println String.format("%-16s %10s  %-19s %s", 'TAG', 'SIZE', 'CREATED', 'VOLUMES')
        seeds.each { d ->
            def m = zfinUtil.readSeedManifest(d)
            def bytes = (d.listFiles() ?: []).findAll { it.isFile() }.sum { it.length() } ?: 0L
            def vols = m?.volumes?.collect { it.name }?.join(' ') ?: '(no manifest)'
            println String.format("%-16s %9.1fG  %-19s %s", d.name, bytes / 1073741824.0,
                    m?.created ?: '?', vols)
        }
        println "\nin $dir -- use one with:  z feature new <ticket> --seed <tag>"
    }

    // ---- z seed restore ----------------------------------------------------------------
    private void restore(List args, ZfinUtil zfinUtil) {
        def die = zfinUtil.&die; def info = zfinUtil.&info; def captureOutput = zfinUtil.&captureOutput
        // The stack z resolved from the working directory. Deliberately no fallback to the base
        // project: restoring over a stack you are not standing in is not a default.
        def project = zfinUtil.childEnv['COMPOSE_PROJECT_NAME']
        if (!project) die("z seed restore: no stack here. Run it from inside a checkout or feature worktree\n" +
                          "   whose docker/.env names a COMPOSE_PROJECT_NAME.")
        boolean app = false, caches = false, force = false
        String tag = null
        args.each { a ->
            switch (a) {
                case '--app': app = true; break
                case '--caches': caches = true; break
                case '--force': case '-f': force = true; break
                default:
                    if (a.startsWith('-')) die("z seed restore: unknown arg '$a'", 2)
                    tag = a
            }
        }
        tag = tag ?: zfinUtil.newestSeed()
        if (!tag) die("no seeds on this host -- z seed create, or z seed build")
        def seed = zfinUtil.seedDir(tag)
        def manifest = zfinUtil.readSeedManifest(seed)
        if (!manifest) die("no seed '$tag' at $seed (z seed ls lists them)")

        // The same two refusals `z feature new` makes before restoring a seed.
        def plat = zfinUtil.seedPlatformProblem(tag)
        if (plat && !zfinUtil.allowPlatformMismatch()) die(plat)
        if (manifest.pg_major) {
            def engine = captureOutput(['docker', 'run', '--rm', '--entrypoint', 'sh', zfinUtil.dbImage(),
                                        '-c', 'postgres --version'])?.find(/\d+/)
            if (engine && engine != manifest.pg_major)
                die("seed '$tag' holds PostgreSQL ${manifest.pg_major} data, but ${zfinUtil.dbImage()} runs ${engine}.")
        }

        def has = { String vn -> zfinUtil.archiveFileFor(seed, vn) != null }
        def vols = [] + StackConfig.DATA_VOLS
        if (app) {
            def missing = StackConfig.APP_VOLS.findAll { !has(it) }
            if (missing) die("--app: seed '$tag' has no ${missing.join(', ')} -- it carries no app tier to restore")
            vols += StackConfig.APP_VOLS
            if (has(StackConfig.JENKINS_VOL)) vols << StackConfig.JENKINS_VOL
        }
        if (caches) vols += StackConfig.CACHE_VOLS.findAll { has(it) }
        def absent = StackConfig.DATA_VOLS.findAll { !has(it) }
        if (absent) die("seed '$tag' has no ${absent.join(', ')} tarball")

        info("restore seed '$tag' into '$project': ${vols.join(', ')}")
        def existing = vols.findAll { zfinUtil.volumeExists("${project}_$it") }
        if (existing) {
            if (!force)
                die("these volumes already exist: ${existing.collect { "${project}_$it" }.join(', ')}\n" +
                    "   Restoring REPLACES them, discarding what is there. To do that:\n" +
                    "     z down\n" +
                    "     z seed restore $tag${app ? ' --app' : ''}${caches ? ' --caches' : ''} --force")
            def users = existing.collectMany { vn ->
                captureOutput(['docker', 'ps', '-a', '--filter', "volume=${project}_$vn", '--format', '{{.Names}}'])
                        .readLines().findAll { it }
            }.unique()
            if (users) die("containers still use those volumes: ${users.join(', ')}\n" +
                           "   z down first (it removes containers, keeps volumes), then re-run.")
            existing.each { vn ->
                info("removing ${project}_$vn (replaced by the seed)")
                zfinUtil.runQuietly(['docker', 'volume', 'rm', "${project}_$vn".toString()])
            }
        }

        def timer = zfinUtil.stepTimer()
        def results = zfinUtil.restoreVolumes(project, vols, seed)
        def bad = results.findAll { !it.ok }
        if (bad) die("restore failed: ${bad.collect { it.vn }.join(', ')}\n" + bad.collect { it.err }.join('\n'))
        timer.mark('restore volumes')
        info("restored seed '$tag' into '$project'")
        if (!app) info("app tier untouched (--app restores the seed's deployed app too)")
        info("start it:  z up db solr tomcat httpd")
        timer.report("seed restore '$tag' timing")
    }

    // ---- z seed rm ---------------------------------------------------------------------
    private void remove(List args, ZfinUtil zfinUtil) {
        def die = zfinUtil.&die; def info = zfinUtil.&info
        def force = args.contains('--force') || args.contains('-f')
        def tag = args.find { !it.startsWith('-') }
        if (!tag) die("usage: z seed rm <tag> [--force]", 2)
        def d = zfinUtil.seedDir(tag)
        if (!d.isDirectory()) die("no seed '$tag' at $d")
        def bytes = (d.listFiles() ?: []).findAll { it.isFile() }.sum { it.length() } ?: 0L
        // A seed is the ONLY copy of a load that took hours. Confirm unless told not to, and
        // refuse without a TTY rather than guessing -- same stance as `z feature rm`.
        if (!force) {
            def con = System.console()
            if (!con) die("refusing to delete seed '$tag' without a TTY. Pass --force if you mean it.")
            def a = con.readLine(String.format("delete seed '%s' (%.1fG) from %s? [y/N]: ", tag, bytes / 1073741824.0, d))
            if (a?.trim()?.toLowerCase() != 'y') { info("kept"); return }
        }
        if (!d.deleteDir()) die("could not delete $d")
        info(String.format("deleted seed '%s' (%.1fG)", tag, bytes / 1073741824.0))
    }

    // ---- z seed create -----------------------------------------------------------------
    private void create(List args, ZfinUtil zfinUtil) {
        def die = zfinUtil.&die; def info = zfinUtil.&info; def runCommand = zfinUtil.&runCommand
        def runQuietly = zfinUtil.&runQuietly
        def captureOutput = zfinUtil.&captureOutput

        // The stack `z` resolved from the working directory wins. It has to be read from
        // childEnv rather than env(): env() treats THIS checkout's docker/.env as authoritative,
        // and that file names the base stack -- so standing in a feature worktree would still
        // capture zfin_org, which is exactly the trap this default exists to avoid. Falls back
        // to the ambient environment, then to the base stack when the cwd owns no stack at all.
        def project = zfinUtil.childEnv['COMPOSE_PROJECT_NAME'] ?:
                      zfinUtil.env('COMPOSE_PROJECT_NAME', StackConfig.BASE_PROJECT)
        def tag = java.time.LocalDate.now().toString()   // YYYY-MM-DD
        def app = true       // small, and a cold stack is a worse default than a bigger seed
        def caches = false   // several GB and regenerable

        for (int i = 0; i < args.size(); i++) {
            switch (args[i]) {
                case '--from': case '--project': project = args[++i]; break
                case '--tag': tag = args[++i]; break
                case '--app': app = true; break
                case '--no-app': app = false; break
                case '--caches': caches = true; break
                case '--no-caches': caches = false; break
                default: die("z seed create: unknown arg '${args[i]}'", 2)
            }
        }

        def release = zfinUtil.env('ZFIN_RELEASE')
        if (!release) die("ZFIN_RELEASE must be set (from docker/.env or the environment)")

        // Stock db image: the pg engine matching this data, used for the WAL-trim throwaway
        // postgres (which MUST be the db image). The tar capture below uses zfinUtil.tarImage()
        // (the compile image) -- the same source the restore uses. Both are local (no pull).
        // The image that WROTE this data, not this host's default: a --db-platform build runs
        // an amd64 db on an arm64 host, and its data must be trimmed by the same engine.
        def stockDb = zfinUtil.projectDbImage(project) ?: zfinUtil.dbImage()
        def stockPlat = zfinUtil.imagePlatform(stockDb)
        def platArgs = stockPlat ? ['--platform', stockPlat] : []

        def pgVol = "${project}_pg_data"
        def solrVol = "${project}_solr_var"
        def out = zfinUtil.seedDir(tag)

        info("seed create: from=$project release=$release tag=$tag -> $out")
        if (out.isDirectory() && (out.listFiles() ?: []).any { it.isFile() })
            die("seed '$tag' already exists at $out\n" +
                "   Pick another --tag, or remove it:  z seed rm $tag")

        // Both named volumes must exist, or the capture would silently produce empties.
        [pgVol, solrVol].each { v ->
            if (runQuietly(['docker', 'volume', 'inspect', v]) != 0)
                die("volume '$v' not found -- is project '$project' loaded? (try --from)")
        }

        def appVols = StackConfig.APP_VOLS
        def cacheVols = StackConfig.CACHE_VOLS
        def present = { List vns -> vns.findAll { runQuietly(['docker', 'volume', 'inspect', "${project}_${it}"]) == 0 } }
        def appPresent = app ? present(appVols) : []
        def cachesPresent = caches ? present(cacheVols) : []
        // Jenkins home travels with the app tier: a stack restored without it has no jobs and a
        // different admin secret, so `jenkins-cli` stops working against it.
        def jenkinsPresent = app ? present([StackConfig.JENKINS_VOL]) : []
        if (app && appPresent.size() < appVols.size())
            info("note: --app skipping absent volumes: ${(appVols - appPresent).join(', ')}")

        // Timings, reported at the end like freeze and thaw. Capture dominates, but the WAL
        // trim is a minute of throwaway-postgres that is invisible without this, and the split
        // is what tells you whether a slow run was disk or postgres.
        def timer = zfinUtil.stepTimer()
        def volSecs = [:]

        // Failure-recovery state shared with the shutdown hook (registered below): the trim
        // container to force-remove, and a flag that a clean run has finished.
        def state = [completed: false, trim: null]

        // Trim the captured snapshot before tarring it.
        //   ALWAYS: shed recycled WAL. In a frozen snapshot the pg_wal segments are
        //     pre-allocated for reuse -- near-zero real data, yet they cost their full ~16MB
        //     each on disk (hundreds of segments = several GB). Collapsing them is a safe,
        //     unconditional win: the copy is started fresh in every feature, so there's
        //     nothing to recover. A plain CHECKPOINT won't shrink them (segment-retention
        //     decays only ~2%/checkpoint), so we pg_resetwal.
        // Mechanism: briefly run a throwaway postgres on the volume so the clean docker-stop
        // afterward gives pg_resetwal its required clean-shutdown precondition. Runs while the
        // real db is stopped (single writer on the volume). The throwaway start skips initdb
        // (data dir is non-empty).
        def trimSnapshot = {
            def img = stockDb
            def name = "seed-trim-${ProcessHandle.current().pid()}"
            info("[trim] throwaway postgres on $pgVol (WAL reset)")
            runCommand(['docker', 'run', '-d', '--name', name] + platArgs + [
                        '-e', 'POSTGRES_HOST_AUTH_METHOD=trust',
                        '-v', "${pgVol}:/var/lib/postgresql", img])
            state.trim = name    // track so the shutdown hook can force-remove it if we die below

            def ready = false
            for (int i = 0; i < 60 && !ready; i++) {
                if (runQuietly(StackConfig.dbHealthCheck(name)) == 0) ready = true else sleep(1000)
            }
            if (!ready) {
                runCommand(['docker', 'logs', '--tail', '30', name], [check: false])
                runQuietly(['docker', 'rm', '-f', name]); state.trim = null
                die("[trim] postgres not ready")
            }

            // -t 60: give postgres time for a clean fast-shutdown (the default 10s grace risks a
            // SIGKILL -> unclean state, which pg_resetwal -f would then paper over).
            runCommand(['docker', 'stop', '-t', '60', name])
            runQuietly(['docker', 'rm', name]); state.trim = null
            info("[trim] pg_resetwal to shed recycled WAL segments (safe after the clean shutdown above)")
            runCommand(['docker', 'run', '--rm'] + platArgs + ['-u', 'postgres', '-v', "${pgVol}:/var/lib/postgresql",
                        '--entrypoint', 'bash', img, '-c', 'pg_resetwal -f "$PGDATA"'])
        }

        // Quiesce db/solr so the tarred on-disk state is consistent (not mid-write).
        // Stop ONLY the services that are actually running, remember their container IDs,
        // and restart exactly those after capture -- a down stack stays down, and the trim
        // step always gets a stopped db regardless of starting state. Detected via compose
        // labels so no compose file / -f flags are needed.
        def runningContainer = { String svc ->
            captureOutput(['docker', 'ps', '-q',
                           '--filter', "label=com.docker.compose.project=$project",
                           '--filter', "label=com.docker.compose.service=$svc"])
        }
        def stopped = [:]   // service -> container id we stopped

        // Restart-on-failure net. From the first stop until the restart loop below, db/solr are
        // down; any die (trim, pg_resetwal, a capture() failing on low disk mid-tar) would leave
        // the dev's stack down -- and die() calls System.exit, which skips try/finally. So a JVM
        // shutdown hook (registered BEFORE we stop anything) restarts exactly what we stopped and
        // force-removes a leaked trim container, unless state.completed says we finished cleanly.
        Runtime.runtime.addShutdownHook(new Thread({
            if (state.completed) return
            if (state.trim) runQuietly(['docker', 'rm', '-f', state.trim])
            stopped.each { svc, cid ->
                System.err.println("!! [cleanup] restarting $svc after an incomplete seed")
                runQuietly(['docker', 'start', cid])
            }
        } as Runnable))

        StackConfig.DATA_SERVICES.each { svc ->
            def cid = runningContainer(svc)
            if (cid) {
                info("stopping $svc ($cid) for a consistent capture")
                runCommand(['docker', 'stop', cid])
                stopped[svc] = cid
            }
        }

        timer.mark('stop data tier')
        // Read while the source's db container still exists: its image wrote the data.
        def dataPlatform = zfinUtil.dataPlatform(project)
        trimSnapshot()   // sheds recycled WAL from the frozen snapshot
        timer.mark('trim WAL')

        // Read the postgres major straight out of the trimmed data, so the manifest can refuse a
        // restore into an engine that cannot open it. This is the 2026-09-18 near-miss made
        // impossible: PGDATA written by one release, baked onto a server from another.
        def pgMajor = captureOutput(['docker', 'run', '--rm', '-u', '0', '-v', "${pgVol}:/d:ro",
                                     '--entrypoint', 'sh', zfinUtil.tarImage(),
                                     '-c', 'find /d -name PG_VERSION -exec cat {} + | head -1'])?.trim()
        if (!pgMajor) info("note: could not read PG_VERSION -- the manifest will not record a major version")

        out.mkdirs()
        // The SAME capture `z feature freeze` uses, so a seed and a freeze archive are the same
        // artifact. Names match StackConfig's volume names, because restoreVolumes reads
        // <dir>/<vn>.tgz into <project>_<vn> -- that symmetry is what makes a seed restorable
        // with no translation layer.
        def vns = StackConfig.DATA_VOLS + appPresent + jenkinsPresent + cachesPresent
        vns.each { vn ->
            def t = System.currentTimeMillis()
            zfinUtil.captureVolume("${project}_${vn}", new File(out, "${vn}.tgz"))
            volSecs[vn] = (System.currentTimeMillis() - t) / 1000.0
        }
        timer.mark('capture volumes')

        // Restart exactly what we stopped (leave an already-down stack down), then mark the run
        // complete so the shutdown hook stands down -- the stack is back up from here.
        stopped.each { svc, cid ->
            info("restarting $svc")
            runCommand(['docker', 'start', cid])
        }
        state.completed = true
        timer.mark('restart source')

        def manifest = zfinUtil.writeSeedManifest(out, [
                tag: tag, source_project: project, release: release, pg_major: pgMajor ?: '',
                platform: dataPlatform,
                volumes: vns])
        timer.mark('checksum + manifest')
        def total = (out.listFiles() ?: []).findAll { it.isFile() }.sum { it.length() } ?: 0L
        info(String.format("seed '%s' written: %.1fG across %d volume(s) -> %s", tag, total / 1073741824.0, vns.size(), out))
        info("use it with:  z feature new <ticket> --seed $tag")

        // A seed whose APP TIER is empty makes stacks that cannot serve until they are built,
        // and the failure points nowhere near the cause: httpd includes
        // $TARGETROOT/server_apps/apache/inc-redirect (out of www_data) at startup, so an empty
        // www_data kills it with "Syntax error on line 52 ... Could not open configuration
        // file". Observed capturing from zfin_shared, which holds data and no deployed app.
        // Only www_data and catalina_base are checked: keystore and tls_certs are legitimately
        // a few KB, so a size floor would cry wolf on every healthy seed.
        if (app) {
            def thin = StackConfig.APP_TIER_VOLUMES.findAll { vn ->
                def f = zfinUtil.archiveFileFor(out, vn)
                !f || f.length() < StackConfig.APP_TIER_MIN_BYTES
            }
            if (thin) {
                System.err.println("!! this seed has no usable app tier (${thin.join(', ')} empty or absent).")
                System.err.println("   Stacks made from it come up with db+solr only: httpd cannot start until")
                System.err.println("   \$TARGETROOT is populated, and fails with an Apache config error that does")
                System.err.println("   not mention the cause. They need the full first build:")
                System.err.println("     z run -c \"ant do && gradle make && ant deploy-catalina-base && ant deploy-no-tests-no-restart\"")
                System.err.println("   To avoid that, capture from a stack that has been DEPLOYED -- an instance, or")
                System.err.println("   a feature stack you have built -- rather than from a data-only project.")
            }
        }

        timer.report("seed '$tag' timing")
        if (volSecs) {
            println "    per volume:"
            volSecs.sort { -it.value }.each { vn, vs ->
                def f = zfinUtil.archiveFileFor(out, vn)
                def mb = (f?.length() ?: 0L) / 1048576.0
                println String.format("      %-16s %7.1fs  %8.0f MB  %6.0f MB/s", vn, vs, mb, vs > 0 ? mb / vs : 0)
            }
        }
    }
}
