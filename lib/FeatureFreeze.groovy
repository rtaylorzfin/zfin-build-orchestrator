// FeatureFreeze -- `z feature freeze <ticket>` (or `z freeze` inside the worktree): park a
// stack. Tar its volumes to the archive dir, remove them, and leave the worktree, branch and
// .env in place so `z feature thaw` puts it back exactly as it was.
//
//   z feature freeze <ticket> [--caches] [--to DIR] [--force]
//
//   --caches   Also archive gradle/maven/npm caches (large, and regenerable). Off by default,
//              and then they are DROPPED before the capture starts, to make room for it.
//   --compress / --no-compress
//              Force compression on or off. The DEFAULT adapts: compress when the tar image
//              has pigz (parallel gzip), not otherwise -- because that is what decides whether
//              compression is cheap. Measured on a 21.7G stack: pigz -1 freezes in 81s to
//              6.9G where plain tar takes 132s to write 21.2G, so with pigz compressing is
//              both faster AND smaller; with single-threaded gzip it costs 270s, so it is not.
//              Archives live on cheap storage while the point of freezing is to reclaim FAST
//              local disk, so trading archive size for wall-clock is the right way round.
//              When this is on, gzip -1 is used, not the default -6 (87s more for 0.2G less).
//              Space overrides speed: when the free-space check (below) says an uncompressed
//              archive will not fit and a compressed one will, the default compresses anyway.
//   --to DIR   Archive somewhere other than $ZFIN_ARCHIVE_DIR for this run.
//   --force    Proceed past the safety checks: an archive that already exists (overwrite
//              it), a database that could not be confirmed cleanly shut down, and a
//              free-space estimate that says the archive will not fit.
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
//
// ONE VOLUME AT A TIME, SMALLEST FIRST. Capturing everything and only then `down -v` needs
// the whole stack AND the whole archive on disk at once -- nearly double the stack, when the
// archive shares its disk, which is the default ($ZFIN_DEV_ROOT/archive) and exactly the
// host that is short of space. So each volume is captured, read back, recorded in the
// manifest, and only then removed, before the next one starts. Smallest first, because the
// biggest volume (pg_data) is the one most likely to run out of room: by the time it goes,
// every smaller volume has already turned into its (smaller) archive and freed the difference.
// The peak is then roughly the biggest archive less that difference, not the stack's worth.
//
// WAL IS TRIMMED BEFORE pg_data IS CAPTURED. Postgres keeps written WAL for reuse up to
// max_wal_size -- 10GB in the base compose file -- and a cleanly shut-down cluster needs none
// of it: measured at 10G of a 25G pg_data on one feature. Archiving it costs space and time
// for nothing, so freeze runs pg_resetwal after the verified clean shutdown (ZfinUtil.trimWal:
// without -f, so pg_resetwal itself refuses an unclean cluster), as `z seed new` does.
//
// A FREEZE THAT STOPS PART-WAY IS SAFE AND RESUMABLE. The manifest says `state: partial` and
// lists what is `captured`; every volume is at all times either still in docker or archived
// and recorded. `z freeze` again picks up where it stopped; `z thaw` restores what was
// archived and backs out. `z up` refuses in between (ZfinUtil.freezeBlocksUp): it would
// create empty volumes in place of the archived ones, which a resumed freeze would then
// archive over the real data.
class FeatureFreeze {
    // Kept free on the archive's disk beyond the estimated peak: a disk filled to its last
    // byte takes the docker daemon (and on Docker Desktop, its VM) down with it.
    static final long SLACK = 1L << 30
    // gzip -1's ratio, for the space estimate only. Measured 0.32 over a whole stack; 0.5
    // leaves room for volumes that compress worse (npm tarballs, jars that already are zips).
    static final double GZ_RATIO = 0.5

    /** The most the archive's disk will hold beyond what it holds now, capturing `sizes` (volume
     *  bytes) in that order. `reclaim`: each volume removed after its capture frees its space on
     *  that same disk (false when the archive is elsewhere -- then it must hold every archive). */
    static long peakNeed(List<Long> sizes, double ratio, long credit, boolean reclaim) {
        long net = reclaim ? -credit : 0L
        long worst = 0L
        sizes.each { long v ->
            long a = (long) Math.ceil(v * ratio)
            worst = Math.max(worst, net + a)
            net += a - (reclaim ? v : 0L)
        }
        Math.max(worst, 0L) + SLACK
    }

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
        def prior = manifest.isFile() ? new groovy.json.JsonSlurper().parse(manifest) : null
        // A freeze that stopped part-way is finished, not refused: that is what re-running is for.
        def resume = prior?.state == 'partial'
        if (prior && !resume && !force)
            die("'$slug' already has an archive at $dest (frozen already?).\n" +
                "   z feature thaw $slug   to bring it back, or --force to overwrite the archive")

        if (!dest.exists() && !dest.mkdirs())
            die("cannot create $dest\n" +
                "   Point ZFIN_ARCHIVE_DIR at writable storage (z config set ZFIN_ARCHIVE_DIR=<dir>).")

        def compose = ['docker', 'compose', '-p', project]
        if (envF.isFile()) compose += ['--env-file', envF.absolutePath]
        if (spec?.compose) compose += spec.compose.tokenize(':').collectMany { ['-f', it] }
        else die("$wt has no readable docker/.env -- freeze needs it to know what this stack is.")

        def volName = { String vn -> "${project}_${vn}".toString() }
        def exists  = { String vn -> zfinUtil.volumeExists(volName(vn)) }

        // ---- what we are about to archive -------------------------------------------------
        def vols = []
        if (!shared) vols += StackConfig.DATA_VOLS
        vols += StackConfig.APP_VOLS
        if (doCaches) vols += StackConfig.CACHE_VOLS
        vols += StackConfig.CLAUDE_VOL
        vols += StackConfig.APP_COMPANION_VOLS
        // Only what actually exists: a stack that never started has no volumes, --caches on a
        // stack with no cache volumes should not fail, and claude_home is absent until the
        // sidecar lands. A resumed freeze keeps the list it started with.
        def plan = resume ? ((prior.volumes ?: []) as List) : vols.findAll { exists(it) }
        def done = resume ? ((prior.captured ?: []) as List) : []
        def missingData = !shared ? StackConfig.DATA_VOLS.findAll { !(it in plan) } : []

        info("freeze '$slug'  project=$project  data=${shared ? 'shared (not ours to archive)' : 'own'}")
        // Nothing to archive means this stack is already frozen, or was never started. Stop
        // BEFORE the shutdown sequence and before anything touches $dest: an empty capture
        // would write a manifest claiming no volumes and then prune every file "not in this
        // freeze" -- which is to say, a previous, perfectly good archive. Overwriting an
        // archive has to mean REPLACING it, never emptying it.
        if (!plan) {
            def why = prior
                ? "It looks already frozen: $dest has an archive from ${prior.frozenAt}.\n" +
                  "   z feature thaw $slug   to bring it back."
                : "Is it up? `z feature ls` shows its state; `cd ${wt} && z up` starts it."
            die("no volumes found for project '$project' -- there is nothing to archive.\n   $why")
        }

        if (resume) {
            info("resuming the freeze started ${prior.startedAt}: ${done.size()} of ${plan.size()} volume(s) already archived")
            def noFile = done.findAll { !zfinUtil.archiveFileFor(dest, it) }
            if (noFile) die("the manifest says ${noFile.join(', ')} were archived, but $dest has no archive for them.\n" +
                            "   Nothing more is touched until that is explained.")
            // Archived, then created again: NOT the archived data (that is in $dest) but, most
            // likely, empty volumes a `docker compose up` made in the meantime. Removing them
            // would be right, but z cannot be sure, and guessing wrong loses data.
            def again = zfinUtil.volumesCreatedSince(project, done, prior.startedAtMs as long)
            if (again) die("these volumes were archived by the interrupted freeze and then created AGAIN:\n" +
                           "     ${again.collect { volName(it) }.join(' ')}\n" +
                           "   Their real contents are the archives in $dest. If these are the empty ones a\n" +
                           "   `compose up` made, remove them (docker volume rm ...) and re-run z freeze.")
            def lost = (plan - done).findAll { !exists(it) }
            if (lost) {
                System.err.println("!! neither archived nor in docker: ${lost.collect { volName(it) }.join(', ')} -- removed outside freeze?")
                if (!force) die("cannot finish this freeze without them. --force records it without them.")
                System.err.println("!! --force given; the freeze will not include them.")
                plan -= lost
            }
        }
        def pending = plan - done
        info("volumes  : ${plan.join(', ')}${done ? "  (already archived: ${done.join(', ')})" : ''}")
        info("archive  : $dest")

        def timer = zfinUtil.stepTimer()

        // ---- 0. will it fit? ----------------------------------------------------------------
        // Before anything is stopped: refusing now leaves the stack exactly as it was. The
        // build caches freeze discards (not --caches) are counted as freed up front, since they
        // are dropped before the first capture. Sizes are an estimate -- a compressed archive
        // can come out bigger than GZ_RATIO allows -- which is acceptable only because running
        // out of space part-way is not a disaster (see the header): it stops, resumably.
        def discard = doCaches ? [] : StackConfig.CACHE_VOLS.findAll { !(it in plan) && exists(it) }
        def sizes = zfinUtil.volumeBytes((pending + discard).collect { volName(it) })
        // pg_data is sized as it will be captured: less the WAL the trim (step 3a) sheds,
        // which can be most of the difference between fitting and not.
        def trimPlanned = !shared && 'pg_data' in pending
        if (trimPlanned && sizes) {
            long wal = zfinUtil.pgWalBytes(volName('pg_data'))
            if (wal > (256L << 20)) {
                info(String.format("pg_wal   : %.1fG of WAL kept for reuse -- trimmed before capture", wal / 1073741824.0))
                sizes[volName('pg_data')] = Math.max(0L, (sizes[volName('pg_data')] as long) - wal + (64L << 20))
            }
        }
        def sizeOf = { String vn -> (sizes[volName(vn)] ?: 0L) as long }
        // Smallest first (see the header). Without sizes, plan order -- it lists pg_data first,
        // so reverse it to put the biggest last as best we can.
        def order = sizes ? pending.sort(false) { sizeOf(it) } : pending.reverse()
        if (compress == null && resume && prior.compressed != null) compress = prior.compressed as boolean
        def gb = { long b -> String.format('%.1fG', b / 1073741824.0) }
        if (sizes) {
            def shares = zfinUtil.volumesShareDiskWith(dest)
            long free = dest.usableSpace
            long credit = discard.sum(0L) { sizeOf(it) } as long
            def need = { boolean z -> peakNeed(order.collect { sizeOf(it) }, z ? GZ_RATIO : 1.0d, credit, shares != false) }
            boolean auto = compress == null
            if (auto) compress = zfinUtil.hasPigz()
            if (auto && !compress && need(false) > free && need(true) <= free) {
                info("uncompressed would need ~${gb(need(false))} and $dest has ${gb(free)} free -- compressing (gzip -1; slower, " +
                     "but it fits). --no-compress to insist.")
                compress = true
            }
            long want = need(compress)
            if (want > free) {
                System.err.println("!! not enough room: this freeze needs about ${gb(want)} free at its peak on the disk holding")
                System.err.println("   $dest, which has ${gb(free)}.")
                if (shares == false)
                    System.err.println("   That disk is not where docker keeps volumes, so removing them makes no room there:\n" +
                                       "   it must hold the whole archive.")
                if (!compress) System.err.println("   --compress would shrink it (est. ${gb(need(true))}).")
                System.err.println("   --to DIR (or z config set ZFIN_ARCHIVE_DIR=...) puts the archive on another disk.")
                if (!force) die("refusing before anything is stopped. --force to try anyway: running out part-way stops\n" +
                                "   the freeze safely, and `z freeze` resumes it once there is room.")
                System.err.println("!! --force given; trying anyway.")
            } else {
                info("space    : ~${gb(want)} needed at the peak, ${gb(free)} free" +
                     (shares == false ? ' (archive is on another disk: it holds the whole archive)' : ''))
            }
        } else {
            info("space    : could not size the volumes -- skipping the free-space check")
            if (compress == null) compress = zfinUtil.hasPigz()
        }
        def ext = compress ? 'tgz' : 'tar'

        // ---- 1. ordered shutdown ----------------------------------------------------------
        // A resumed freeze normally finds no containers (the first run removed them); if some
        // came back, the whole sequence runs again, verification included.
        def containers = {
            captureOutput(['docker', 'ps', '-aq', '--filter', "label=com.docker.compose.project=$project"])
                    .readLines().findAll { it }
        }
        def stackImages = resume ? ((prior.images ?: [:]) as Map) : [:]
        def platform = resume ? prior.platform : null
        def uncleanDb = false   // set when --force carried us past an unverified shutdown
        if (containers()) {
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

                // ---- 2. PROVE the database is down --------------------------------------
                // The one check that must not be skipped for speed. `docker stop` returning is
                // not the same as postgres having completed its shutdown checkpoint.
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
                        uncleanDb = true
                    }
                }
                timer.mark('stop data tier + verify')
            }

            // Record the app tier's image IDs too. ZFIN's images are tagged :main -- a MOVING
            // tag -- so a stack thawed months later can come up on a different tomcat than the
            // one that deployed its archived catalina_base. Nothing here can prevent that; the
            // point is that thaw can SAY so rather than letting it be discovered by strange
            // behaviour later. Read off the containers, so before they are removed below.
            def imageId = { String ref -> captureOutput(['docker', 'image', 'inspect', ref, '--format', '{{.Id}}']) }
            captureOutput(['docker', 'ps', '-a', '--filter', "label=com.docker.compose.project=$project",
                           '--format', '{{.Label "com.docker.compose.service"}}\t{{.Image}}'])
                    .readLines().findAll { it?.contains('\t') }.each {
                def (svc, img) = it.split('\t', 2)
                if (svc && img) stackImages[svc] = [image: img, id: imageId(img)]
            }
            platform = zfinUtil.dataPlatform(project)

            // ---- 3. remove the containers, keep the volumes -----------------------------
            // A volume cannot be removed while any container -- running or not -- refers to it,
            // and each one is removed the moment it is archived. `down` without -v; then
            // whatever it leaves (profile-scoped services like claude are outside its reach).
            info("removing the containers (volumes stay until each is archived)")
            runCommand(compose + ['down'], [check: false])
            def stragglers = containers()
            if (stragglers) runQuietly(['docker', 'rm', '-f'] + stragglers)

            // A --shared-db stack has the SHARED db/solr joined into its network; those foreign
            // containers keep `down` from removing it. Same cleanup z feature rm does.
            def net = "${project}_default".toString()
            if (runQuietly(['docker', 'network', 'inspect', net]) == 0) {
                captureOutput(['docker', 'network', 'inspect', net, '--format',
                               '{{range .Containers}}{{.Name}} {{end}}']).split().findAll { it }.each {
                    runQuietly(['docker', 'network', 'disconnect', '-f', net, it])
                }
                runQuietly(['docker', 'network', 'rm', net])
            }
            timer.mark('remove containers')
        }
        if (!platform) platform = zfinUtil.dataPlatform(project)

        // Archived by an interrupted run that stopped before removing them -- they are not
        // recreations (checked above), so the archive is the copy.
        done.findAll { exists(it) }.each { vn ->
            info("removing ${volName(vn)} (archived by the interrupted run)")
            runQuietly(['docker', 'volume', 'rm', volName(vn)])
        }
        if (discard) {
            info("dropping the build caches to make room (${discard.join(', ')}; thaw re-warms them from the seed)")
            discard.each { runQuietly(['docker', 'volume', 'rm', volName(it)]) }
        }

        // ---- 3a. trim pg_data's WAL (see the header) ------------------------------------------
        // Not after --force past an unclean shutdown: pg_resetwal would refuse anyway, and the
        // archive should then be exactly what postgres left, for crash recovery at thaw.
        if (trimPlanned && !uncleanDb) {
            def dbImg = (stackImages.db?.image ?: zfinUtil.dbImage()) as String
            info("trimming pg_data's WAL (pg_resetwal on the cleanly shut-down cluster)")
            if (!zfinUtil.trimWal(volName('pg_data'), dbImg))
                System.err.println("!! pg_resetwal declined (above) -- archiving pg_data with its WAL as it is.")
            timer.mark('trim WAL')
        }

        // ---- 4. manifest, before the first volume goes ------------------------------------
        // Enough to validate a thaw months later: what was captured, and what the stack was.
        // Rewritten after every volume, so at any moment it says exactly which volumes now
        // live only in the archive.
        def doc = [
            slug        : slug,
            project     : project,
            host        : host,
            branch      : captureOutput(['git', '-C', wt.absolutePath, 'rev-parse', '--abbrev-ref', 'HEAD']),
            commit      : captureOutput(['git', '-C', wt.absolutePath, 'rev-parse', 'HEAD']),
            worktree    : wt.absolutePath,
            data        : shared ? 'shared' : 'archived',
            state       : 'partial',
            volumes     : plan,
            captured    : done,
            compressed  : compress,
            seed        : zfinUtil.envField(envF, 'ZFIN_SEED') ?: '',
            images      : stackImages,
            zfinRelease : zfinUtil.env('ZFIN_RELEASE', ''),
            // The platform CONTAINERS ran on, so thaw can refuse a cross-architecture restore.
            // A freeze is per-stack and usually thaws where it froze -- but nothing stops the
            // archive dir being on NFS, or a host being rebuilt, and pg_data does not travel.
            platform    : platform,
            composeFiles: spec?.compose ?: '',
            startedAt   : resume ? prior.startedAt : new Date().format("yyyy-MM-dd HH:mm:ss"),
            startedAtMs : resume ? prior.startedAtMs : System.currentTimeMillis(),
            toolVersion : 2,
        ]
        zfinUtil.writeJsonAtomic(manifest, doc)

        // ---- 5. capture, verify, release -- one volume at a time --------------------------
        def resumeHint = "The freeze stopped part-way, safely: each volume is either still in docker or archived in\n" +
                         "   $dest (listed in ${manifest.name}).\n" +
                         "     z freeze   to finish it once fixed -- it picks up where it stopped\n" +
                         "     z thaw     to restore what it archived and back out"
        // Default: compress IF the tar image has pigz. Measured on this 21.7 GB stack --
        //     none      freeze 132s  thaw  55s  archive 21.2 GB
        //     pigz -1   freeze  81s  thaw 118s  archive  6.9 GB
        //     gzip -1   freeze 270s  thaw 114s  archive  6.9 GB
        // With pigz, compressing is FASTER to freeze than not (fewer bytes to write more than
        // pays for the CPU) and the round trip is within 6% -- for a third of the size. Without
        // pigz, single-threaded gzip doubles the round trip, so plain is right -- unless it
        // does not fit (step 0). --compress / --no-compress force it.
        def t0 = System.currentTimeMillis()
        def volSecs = [:]
        order.each { vn ->
            def vt = System.currentTimeMillis()
            // Clear this volume's earlier archive files FIRST, in both forms and half-written:
            // freezes with different compression otherwise leave `<vn>.tar` beside `<vn>.tgz`,
            // and archiveFileFor prefers .tar -- a stale file would silently win. It also frees
            // that space before the new archive needs it.
            ['tar', 'tgz'].each { e -> new File(dest, "${vn}.${e}").delete() }
            (dest.listFiles() ?: []).findAll { it.name.startsWith("${vn}.part-") }*.delete()
            def out  = new File(dest, "${vn}.${ext}")
            // Written under a name thaw ignores and renamed once read back, so an interrupted
            // capture can never pass for an archive. A fresh name per attempt, not a fixed
            // `.part`: on Docker Desktop, a file deleted on the host and recreated under the same
            // name from inside a container straight after fails (the VM's file-sharing layer
            // still has the old one cached: "Cannot open: No such file or directory") -- which
            // is exactly what resuming after an interrupted capture used to do.
            def part = new File(dest, "${vn}.part-${System.currentTimeMillis()}.${ext}")
            def fail = { String why -> part.delete(); die("$why\n   $resumeHint") }
            if (zfinUtil.captureVolume(volName(vn), part, compress, false) != 0)
                fail("capturing $vn failed${dest.usableSpace < SLACK ? " -- $dest is out of space" : ''}.")
            if (!zfinUtil.verifyArchive(part)) fail("the archive of $vn did not read back cleanly.")
            if (!part.renameTo(out)) fail("could not move $part into place.")
            doc.captured = doc.captured + [vn]
            zfinUtil.writeJsonAtomic(manifest, doc)
            // Only now: archived, read back, and claimed by the manifest.
            if (runQuietly(['docker', 'volume', 'rm', volName(vn)]) != 0)
                die("archived $vn, but could not remove ${volName(vn)} -- is something using it?\n   $resumeHint")
            volSecs[vn] = (System.currentTimeMillis() - vt) / 1000.0
            info(String.format("  %-16s archived, verified, removed  (%.0f MB, %.0f MB free)",
                    vn, out.length() / 1048576.0, dest.usableSpace / 1048576.0))
        }
        def secs = (System.currentTimeMillis() - t0) / 1000.0
        timer.mark('capture + verify volumes')
        def bytes = plan.sum(0L) { zfinUtil.archiveFileFor(dest, it)?.length() ?: 0L } as long

        doc.state      = 'complete'
        doc.bytes      = bytes
        doc.capturedIn = secs
        doc.frozenAt   = new Date().format("yyyy-MM-dd HH:mm:ss")
        zfinUtil.writeJsonAtomic(manifest, doc)

        // Prune anything the new manifest does not claim -- volumes archived by an earlier,
        // larger freeze (one taken with --caches, say) would otherwise linger and be restored
        // by a later thaw that had no business seeing them.
        // Guarded by `plan` being non-empty (checked above): pruning is only ever safe as
        // the tail of a capture that actually wrote something.
        def keep = plan.collect { zfinUtil.archiveFileFor(dest, it)?.name }.findAll { it } + [StackConfig.FREEZE_MANIFEST]
        (dest.listFiles() ?: []).findAll { it.isFile() && !(it.name in keep) }.each {
            info("pruning stale archive file ${it.name} (superseded by this freeze)")
            it.delete()
        }

        // ---- 6. release the rest ------------------------------------------------------------
        // What freeze does not keep (anything the compose file declares beyond the lists above).
        info("down -v (the volumes freeze does not keep; the archive is the copy of the rest)")
        runCommand(compose + ['down', '-v'], [check: false])
        timer.mark('down -v')

        info(String.format("frozen: %.1f GB archived in %s%s -> %s",
                bytes / 1073741824.0, zfinUtil.dur(secs),
                compress ? " (${zfinUtil.hasPigz() ? 'pigz' : 'gzip'} -1)"
                         : " (uncompressed; --compress to shrink it${zfinUtil.hasPigz() ? '' : ', though gzip here is single-threaded'})",
                dest))
        if (missingData) info("  note: expected data volumes were absent: ${missingData.join(', ')}")
        info("thaw with:  z feature thaw $slug")
        timer.report("freeze '$slug' timing")
        if (volSecs) {
            println "    per volume (capture + read-back):"
            volSecs.sort { -it.value }.each { vn, vs ->
                def mb = (zfinUtil.archiveFileFor(dest, vn)?.length() ?: 0L) / 1048576.0
                println String.format("      %-16s %11s  %8.0f MB  %6.0f MB/s", vn, zfinUtil.dur(vs), mb, vs > 0 ? mb / vs : 0)
            }
        }
    }
}
