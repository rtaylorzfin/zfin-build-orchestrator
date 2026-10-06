// FeatureFreeze -- `z feature freeze <ticket>`: park a stack. Tar its volumes to the archive
// dir, `down -v`, and leave the worktree, branch and .env in place so `z feature thaw`
// puts it back exactly as it was.
//
//   z feature freeze <ticket> [--caches] [--to DIR] [--force]
//
//   --caches   Also archive gradle/maven/npm caches (large, and regenerable). Off by default.
//   --compress / --no-compress
//              Force compression on or off. The DEFAULT adapts: compress when the tar image
//              has pigz (parallel gzip), not otherwise -- because that is what decides whether
//              compression is cheap. Measured on a 21.7G stack: pigz -1 freezes in 81s to
//              6.9G where plain tar takes 132s to write 21.2G, so with pigz compressing is
//              both faster AND smaller; with single-threaded gzip it costs 270s, so it is not.
//              Archives live on cheap storage while the point of freezing is to reclaim FAST
//              local disk, so trading archive size for wall-clock is the right way round.
//              When this is on, gzip -1 is used, not the default -6 (87s more for 0.2G less).
//   --to DIR   Archive somewhere other than $ZFIN_ARCHIVE_DIR for this run.
//   --force    Proceed past the safety checks: an archive that already exists (overwrite
//              it), and a database that could not be confirmed cleanly shut down.
//
// WHY THIS IS NOT `z feature rm`: rm is terminal -- it drops the worktree and the branch, so
// you run it once the PR is merged. freeze is reversible: the ~28G of db+solr copy goes to
// cheap storage and everything identifying the feature stays on disk, so the stack can come
// back. It is the "parking for days or weeks" tier between `z stop` (fast, keeps the disk)
// and `rm` (frees everything, keeps nothing).
//
// ORDERING IS THE WHOLE GAME. The app tier must be fully stopped before the data tier, and
// the database must be verifiably shut down before it is tarred -- a torn PGDATA in an
// archive is the one failure here that cannot be recovered, and it is discovered at thaw,
// months later, when the branch it belonged to is gone. docker-compose.yml now declares
// depends_on for the served tier (compose stops in reverse dependency order), but compile and
// claude are deliberately outside that graph, so they are stopped explicitly first.
class FeatureFreeze {
    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return
        def die = zfinUtil.&die; def info = zfinUtil.&info
        def runCommand = zfinUtil.&runCommand; def captureOutput = zfinUtil.&captureOutput
        def runQuietly = zfinUtil.&runQuietly

        def name = null; def doCaches = false; def force = false; def toDir = null
        def compress = null    // null = auto (see below)
        for (int i = 0; i < args.size(); i++) {
            switch (args[i]) {
                case '--caches':  doCaches = true; break
                case '--compress': case '-z': compress = true; break
                case '--no-compress': compress = false; break
                case '--force': case '-f': force = true; break
                case '--to':      toDir = args[++i]; break
                default:
                    if (args[i].startsWith('-')) die("z feature freeze: unknown arg '${args[i]}'", 2)
                    name = args[i]
            }
        }
        // No ticket given: infer it from the worktree you are standing in.
        if (!name) name = zfinUtil.featureSlugFromCwd()
        if (!name) die("usage: z feature freeze <ticket> [--caches] [--to DIR] [--force]\n" +
                       "   (or run it from inside a feature worktree and the ticket is inferred)", 2)

        def slug = name.toLowerCase()
        zfinUtil.requireFeature(slug)   // before --force is offered
        def wt   = new File(zfinUtil.worktreesDir(), slug)
        if (!wt.isDirectory()) die("no worktree at $wt -- `z feature ls` shows what exists")
        def envF = new File(wt, 'docker/.env')
        def spec = zfinUtil.stackSpec(wt)
        def project = zfinUtil.envField(envF, 'COMPOSE_PROJECT_NAME') ?: slug
        def host    = zfinUtil.envField(envF, 'DOCKER_VIRTUAL_HOST')

        // Composition decides what there is to archive. A --shared-db stack has NO pg_data or
        // solr_var of its own: that data lives in the zfin_shared project and is emphatically
        // not this stack's to touch -- other features are live on it. Reuse the same test
        // `z feature ls` uses rather than re-deriving it.
        def shared = spec?.data == 'shared'

        def archiveRoot = new File(toDir ?: zfinUtil.archiveDir())
        def dest = new File(archiveRoot, slug)
        def manifest = new File(dest, StackConfig.FREEZE_MANIFEST)
        if (manifest.isFile() && !force)
            die("'$slug' already has an archive at $dest (frozen already?).\n" +
                "   z feature thaw $slug   to bring it back, or --force to overwrite the archive")

        if (!dest.exists() && !dest.mkdirs())
            die("cannot create $dest\n" +
                "   Point ZFIN_ARCHIVE_DIR (docker/.env, or the environment) at writable storage.")

        def compose = ['docker', 'compose', '-p', project]
        if (envF.isFile()) compose += ['--env-file', envF.absolutePath]
        if (spec?.compose) compose += spec.compose.tokenize(':').collectMany { ['-f', it] }
        else die("$wt has no readable docker/.env -- freeze needs it to know what this stack is.")

        // ---- what we are about to archive -------------------------------------------------
        def vols = []
        if (!shared) vols += StackConfig.DATA_VOLS
        vols += StackConfig.APP_VOLS
        if (doCaches) vols += StackConfig.CACHE_VOLS
        vols += StackConfig.CLAUDE_VOL
        vols += StackConfig.JENKINS_VOL
        // Only what actually exists: a stack that never started has no volumes, --caches on a
        // stack with no cache volumes should not fail, and claude_home is absent until the
        // sidecar lands.
        def present = vols.findAll { zfinUtil.volumeExists("${project}_${it}") }
        def missingData = !shared ? StackConfig.DATA_VOLS.findAll { !("${project}_$it" in present.collect { v -> "${project}_$v" }) } : []

        info("freeze '$slug'  project=$project  data=${shared ? 'shared (not ours to archive)' : 'own'}")
        // Nothing to archive means this stack is already frozen, or was never started. Stop
        // BEFORE the shutdown sequence and before anything touches $dest: an empty capture
        // would write a manifest claiming no volumes and then prune every file "not in this
        // freeze" -- which is to say, a previous, perfectly good archive. Overwriting an
        // archive has to mean REPLACING it, never emptying it.
        if (!present) {
            def why = manifest.isFile()
                ? "It looks already frozen: $dest has an archive from ${new groovy.json.JsonSlurper().parse(manifest).frozenAt}.\n" +
                  "   z feature thaw $slug   to bring it back."
                : "Is it up? `z feature ls` shows its state; `cd ${wt} && z up` starts it."
            die("no volumes found for project '$project' -- there is nothing to archive.\n   $why")
        }
        info("volumes  : ${present.join(', ')}")
        info("archive  : $dest")

        def timer = zfinUtil.stepTimer()

        // ---- 1. ordered shutdown ----------------------------------------------------------
        // App + build tier first, and wait for them to actually be gone. compile/claude are
        // outside the depends_on graph on purpose (an edge there would make `z run compile`
        // boot a database), so they are named here explicitly.
        def appTier = StackConfig.APP_SERVICES + ['tomcatdebug', 'jenkins', StackConfig.BUILD_SERVICE, 'claude']
        info("stopping the app tier (before the data tier, so nothing is mid-write)")
        runCommand(compose + ['stop'] + appTier, [check: false])
        timer.mark('stop app tier')

        if (!shared) {
            // -t 120, not the 10s default: postgres must finish its shutdown checkpoint, and a
            // 16G database will not always do that in ten seconds. Past the timeout docker
            // sends SIGKILL, which leaves a PGDATA needing crash recovery -- survivable, but
            // not something to bake into an archive on purpose.
            info("stopping the data tier (up to 120s for postgres to checkpoint)")
            runCommand(compose + ['stop', '-t', '120'] + StackConfig.DATA_SERVICES, [check: false])

            // ---- 2. PROVE the database is down ------------------------------------------
            // The one check that must not be skipped for speed. `docker stop` returning is not
            // the same as postgres having completed its shutdown checkpoint.
            def dbCid = captureOutput(['docker', 'ps', '-aq',
                    '--filter', "label=com.docker.compose.project=$project",
                    '--filter', 'label=com.docker.compose.service=db'])
            if (dbCid) {
                def running = captureOutput(['docker', 'inspect', dbCid, '--format', '{{.State.Running}}'])
                if (running == 'true')
                    die("db container $dbCid is still running after `compose stop` -- refusing to tar a " +
                        "live PGDATA. Stop it by hand and re-run.")
                // Poll, don't peek once. `compose stop` returns when the container has
                // exited, but the json-file log driver can still be flushing the final line,
                // and a single peek then reports "couldn't tell" for a clean shutdown.
                def marker = 'database system is shut down'
                def clean = false
                for (int i = 0; i < 15 && !clean; i++) {
                    if (zfinUtil.captureOutputMerged(['docker', 'logs', '--tail', '60', dbCid]).contains(marker)) clean = true
                    else Thread.sleep(1000)
                }
                if (clean) {
                    info("db shut down cleanly (checkpoint complete)")
                } else {
                    // Fail by default. The alternative is tarring a PGDATA that postgres was
                    // SIGKILLed out of, producing an archive that needs crash recovery -- and
                    // the discovery happens at thaw, possibly months later.
                    def tail = zfinUtil.captureOutputMerged(['docker', 'logs', '--tail', '15', dbCid])
                    System.err.println("!! never saw '$marker' in the db log after a 120s stop.")
                    System.err.println("   The archive would capture a PGDATA that postgres did not close cleanly.")
                    System.err.println("   Last lines:")
                    tail.readLines().each { System.err.println("     $it") }
                    if (!force) die("refusing to archive an unclean database. Investigate, or --force to proceed anyway.")
                    System.err.println("!! --force given; archiving anyway.")
                }
            }
        }

        if (!shared) timer.mark('stop data tier + verify')

        // ---- 3. capture -------------------------------------------------------------------
        // Clear any archive file for these volumes FIRST, in both forms. Freezes with different
        // compression settings otherwise leave `<vn>.tar` and `<vn>.tgz` side by side, and
        // archiveFileFor prefers .tar -- so a stale uncompressed file from an older freeze would
        // silently win over the .tgz this run just wrote. Per-volume rather than wiping the
        // directory up front, so an interrupted capture does not take the previous archive down
        // with it.
        def t0 = System.currentTimeMillis()
        // Default: compress IF the tar image has pigz. Measured on this 21.7 GB stack --
        //     none      freeze 132s  thaw  55s  archive 21.2 GB
        //     pigz -1   freeze  81s  thaw 118s  archive  6.9 GB
        //     gzip -1   freeze 270s  thaw 114s  archive  6.9 GB
        // With pigz, compressing is FASTER to freeze than not (fewer bytes to write more than
        // pays for the CPU) and the round trip is within 6% -- for a third of the size. Without
        // pigz, single-threaded gzip doubles the round trip, so plain is right. Hence: pick
        // whichever is actually cheaper here, and say which. --compress / --no-compress force it.
        if (compress == null) compress = zfinUtil.hasPigz()
        def ext = compress ? 'tgz' : 'tar'
        def volSecs = [:]
        present.each { vn ->
            ['tar', 'tgz'].each { e -> new File(dest, "${vn}.${e}").delete() }
            def vt = System.currentTimeMillis()
            zfinUtil.captureVolume("${project}_${vn}", new File(dest, "${vn}.${ext}"), compress)
            volSecs[vn] = (System.currentTimeMillis() - vt) / 1000.0
        }
        def secs = (System.currentTimeMillis() - t0) / 1000.0
        timer.mark('capture volumes')
        def bytes = present.sum(0L) { new File(dest, "${it}.${ext}").length() } as long

        // ---- 4. manifest ------------------------------------------------------------------
        // Enough to validate a thaw months later: what was captured, and what the stack was.
        def imageId = { String ref -> captureOutput(['docker', 'image', 'inspect', ref, '--format', '{{.Id}}']) }

        // Record the app tier's image IDs too. ZFIN's images are tagged :main -- a MOVING tag --
        // so a stack thawed months later can come up on a different tomcat than the one that
        // deployed its archived catalina_base. Nothing here can prevent that; the point is that
        // thaw can SAY so rather than letting it be discovered by strange behaviour later.
        // Captured before `down -v`, while the containers still exist.
        def stackImages = [:]
        captureOutput(['docker', 'ps', '-a', '--filter', "label=com.docker.compose.project=$project",
                       '--format', '{{.Label "com.docker.compose.service"}}\t{{.Image}}'])
                .readLines().findAll { it?.contains('\t') }.each {
            def (svc, img) = it.split('\t', 2)
            if (svc && img) stackImages[svc] = [image: img, id: imageId(img)]
        }
        def doc = [
            slug        : slug,
            project     : project,
            host        : host,
            branch      : captureOutput(['git', '-C', wt.absolutePath, 'rev-parse', '--abbrev-ref', 'HEAD']),
            commit      : captureOutput(['git', '-C', wt.absolutePath, 'rev-parse', 'HEAD']),
            worktree    : wt.absolutePath,
            data        : shared ? 'shared' : 'archived',
            volumes     : present,
            compressed  : compress,
            bytes       : bytes,
            capturedIn  : secs,
            seed        : zfinUtil.envField(envF, 'ZFIN_SEED') ?: '',
            images      : stackImages,
            zfinRelease : zfinUtil.env('ZFIN_RELEASE', ''),
            // The platform CONTAINERS ran on, so thaw can refuse a cross-architecture restore.
            // A freeze is per-stack and usually thaws where it froze -- but nothing stops the
            // archive dir being on NFS, or a host being rebuilt, and pg_data does not travel.
            platform    : zfinUtil.dataPlatform(project),
            composeFiles: spec?.compose ?: '',
            frozenAt    : new Date().format("yyyy-MM-dd HH:mm:ss"),
            toolVersion : 1,
        ]
        manifest.text = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(doc))

        // Prune anything the new manifest does not claim -- volumes archived by an earlier,
        // larger freeze (one taken with --caches, say) would otherwise linger and be restored
        // by a later thaw that had no business seeing them.
        // Guarded by `present` being non-empty (checked above): pruning is only ever safe as
        // the tail of a capture that actually wrote something.
        def keep = present.collect { "${it}.${ext}".toString() } + [StackConfig.FREEZE_MANIFEST]
        (dest.listFiles() ?: []).findAll { it.isFile() && !(it.name in keep) }.each {
            info("pruning stale archive file ${it.name} (superseded by this freeze)")
            it.delete()
        }

        // ---- 5. release the disk ----------------------------------------------------------
        info("down -v (removes containers + this stack's volumes; the archive is the copy now)")
        runCommand(compose + ['down', '-v'], [check: false])
        timer.mark('down -v')

        // A --shared-db stack has the SHARED db/solr joined into its network; those foreign
        // containers keep `down -v` from removing it. Same cleanup z feature rm does.
        def net = "${project}_default".toString()
        if (runQuietly(['docker', 'network', 'inspect', net]) == 0) {
            captureOutput(['docker', 'network', 'inspect', net, '--format',
                           '{{range .Containers}}{{.Name}} {{end}}']).split().findAll { it }.each {
                runQuietly(['docker', 'network', 'disconnect', '-f', net, it])
            }
            runQuietly(['docker', 'network', 'rm', net])
        }

        // `docker compose down -v` only removes volumes belonging to services in the ACTIVE
        // profile set, and `claude` sits behind profiles: [claude], so claude_home survives it.
        // Left on disk it defeats the point of freezing, and makes thaw refuse because a volume
        // it is about to restore already exists. Remove whatever we archived and compose did not.
        def leftover = present.findAll { zfinUtil.volumeExists("${project}_${it}") }
        if (leftover) {
            info("removing ${leftover.size()} archived volume(s) `down -v` left behind " +
                 "(profile-scoped services): ${leftover.join(', ')}")
            leftover.each { runQuietly(['docker', 'volume', 'rm', "${project}_${it}".toString()]) }
        }

        info(String.format("frozen: %.1f GB archived in %.0fs%s -> %s",
                bytes / 1073741824.0, secs,
                compress ? " (${zfinUtil.hasPigz() ? 'pigz' : 'gzip'} -1)"
                         : " (uncompressed; --compress to shrink it${zfinUtil.hasPigz() ? '' : ', though gzip here is single-threaded'})",
                dest))
        if (missingData) info("  note: expected data volumes were absent: ${missingData.join(', ')}")
        info("thaw with:  z feature thaw $slug")
        timer.report("freeze '$slug' timing")
        if (volSecs) {
            println "    per volume:"
            volSecs.sort { -it.value }.each { vn, vs ->
                def mb = new File(dest, "${vn}.${ext}").length() / 1048576.0
                println String.format("      %-16s %7.1fs  %8.0f MB  %6.0f MB/s", vn, vs, mb, vs > 0 ? mb / vs : 0)
            }
        }
    }
}
