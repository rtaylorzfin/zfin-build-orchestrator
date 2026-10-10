// FeatureThaw -- `z feature thaw <ticket>`: bring a frozen stack back. Recreate its volumes
// from the archive, start it, and (for a --shared-db stack) reattach the shared data tier.
//
//   z feature thaw <ticket> [--from DIR] [--no-up] [--force]
//
//   --from DIR  Read the archive from somewhere other than $ZFIN_ARCHIVE_DIR.
//   --no-up     Restore the volumes but leave the stack stopped.
//   --no-caches Skip re-warming the gradle/maven/npm caches (see below).
//   --force     Proceed despite a failed manifest check (see below). Last resort.
//
// The manifest checks are the point of this command, not ceremony. A freeze is read back
// weeks or months later, and an archive whose volumes are partly missing -- restoring 3 of 4
// -- gives a stack that starts and is subtly broken, which is worse than one that does not
// start. So is a pg_data written on another architecture, which is refused the same way.
class FeatureThaw {
    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return
        def die = zfinUtil.&die; def info = zfinUtil.&info
        def runCommand = zfinUtil.&runCommand; def captureOutput = zfinUtil.&captureOutput
        def runQuietly = zfinUtil.&runQuietly

        def name = null; def fromDir = null; def doUp = true; def force = false; def doCaches = true
        for (int i = 0; i < args.size(); i++) {
            switch (args[i]) {
                case '--from':   fromDir = args[++i]; break
                case '--no-up':  doUp = false; break
                case '--no-caches': doCaches = false; break
                case '--force': case '-f': force = true; break
                default:
                    if (args[i].startsWith('-')) die("z feature thaw: unknown arg '${args[i]}'", 2)
                    name = args[i]
            }
        }
        if (!name) name = zfinUtil.featureSlugFromCwd()
        if (!name) die("usage: z feature thaw <ticket> [--from DIR] [--no-up] [--no-caches] [--force]\n" +
                       "   (or run it from inside a feature worktree and the ticket is inferred)", 2)

        def slug = name.toLowerCase()
        def src  = new File(new File(fromDir ?: zfinUtil.archiveDir()), slug)
        def manifestFile = new File(src, StackConfig.FREEZE_MANIFEST)
        if (!manifestFile.isFile())
            die("no archive for '$slug' at $src\n" +
                "   (looked for ${StackConfig.FREEZE_MANIFEST}; --from DIR if it was archived elsewhere)")

        def m = new groovy.json.JsonSlurper().parse(manifestFile)
        // Before anything is restored: this archive's pg_data may have been written by a
        // different architecture. Same hazard as a cross-platform seed, same refusal.
        def platProblem = zfinUtil.platformProblem("freeze archive for '$slug'", m.platform,
                "Rebuild the stack here instead:  z feature rm $slug && z feature new $slug")
        if (platProblem && !zfinUtil.allowPlatformMismatch()) die(platProblem)
        def project = m.project ?: slug
        def wt = new File(m.worktree ?: new File(zfinUtil.worktreesDir(), slug).absolutePath)
        def envF = new File(wt, 'docker/.env')
        def spec = zfinUtil.stackSpec(wt)

        def timer = zfinUtil.stepTimer()
        info("thaw '$slug'  project=$project  frozen ${m.frozenAt}  data=${m.data}")
        if (m.branch) info("branch   : ${m.branch}${m.commit ? " @ ${m.commit.take(10)}" : ''}")

        if (!wt.isDirectory())
            die("the worktree is gone: ${wt}\n" +
                "   freeze deliberately keeps it, so this stack was removed (z feature rm) after freezing.\n" +
                "   The archived volumes are still at $src, but there is no stack to attach them to.\n" +
                "   Recreate the worktree first:  z feature new $slug --existing-branch --branch ${m.branch ?: slug}")

        // ---- manifest checks --------------------------------------------------------------
        def problems = []

        // 1. Every volume the manifest claims must actually be here.
        def vols = (m.volumes ?: []) as List
        // Either form: uncompressed `<vn>.tar` or gzipped `<vn>.tgz` (see freeze's --compress).
        def missing = vols.findAll { !zfinUtil.archiveFileFor(src, it) }
        if (missing) problems << "archive is missing tarballs for: ${missing.join(', ')}"

        // 2. Image drift. Not a problem to refuse over -- the stack will start, and the right
        //    response is usually just to redeploy -- but ZFIN tags images :main, so a stack
        //    thawed months later can come up on a different tomcat than the one that deployed
        //    its archived catalina_base. Silent drift is the thing worth not having.
        def drifted = []
        ((m.images ?: [:]) as Map).each { svc, rec ->
            def now = captureOutput(['docker', 'image', 'inspect', rec.image as String, '--format', '{{.Id}}'])
            if (now && rec.id && now != rec.id) drifted << "${svc} (${rec.image})"
        }
        if (drifted) {
            System.err.println("!! these images have changed since the freeze: ${drifted.join(', ')}")
            System.err.println("   The tags are the same but the images behind them moved. The stack will start;")
            System.err.println("   redeploy (gradle dirtydeploy) if behaviour looks unlike what you archived.")
        }

        if (problems) {
            problems.each { System.err.println("!! $it") }
            if (!force) die("refusing to thaw. Re-run with --force only if you understand what changes.", 1)
            System.err.println("!! --force given; continuing despite the above.")
        }

        def compose = ['docker', 'compose', '-p', project]
        if (envF.isFile()) compose += ['--env-file', envF.absolutePath]
        if (spec?.compose) compose += spec.compose.tokenize(':').collectMany { ['-f', it] }
        else die("$wt has no readable docker/.env -- cannot tell what this stack is.")

        // Volumes must not already exist, or the extract lands on top of whatever is there.
        def clash = vols.findAll { zfinUtil.volumeExists("${project}_${it}") }
        if (clash && !force)
            die("these volumes already exist: ${clash.collect { "${project}_$it" }.join(', ')}\n" +
                "   This stack is not frozen -- it has live data that thaw would overwrite.\n" +
                "   `z down -v` in the worktree first if you really mean to replace it.")

        timer.mark('manifest checks')

        // ---- restore ----------------------------------------------------------------------
        def restored = []
        if (vols) {
            info("restoring ${vols.size()} volume(s) from $src")
            def results = zfinUtil.restoreVolumes(project, vols, src)   // reports as it goes
            def failed = results.findAll { !it.ok }
            if (failed) die("restore failed: ${failed.collect { it.vn }.join(', ')}\n" +
                            failed.collect { it.err }.join('\n'))
            restored = results
        } else {
            info("no volumes in the archive (shared-db stack) -- nothing to restore")
        }
        timer.mark('restore volumes')

        // ---- re-warm the build caches -------------------------------------------------------
        // freeze does not archive gradle/maven/npm (they are large and regenerable), so a
        // thawed stack would otherwise meet an empty cache and spend a minute re-resolving
        // every dependency on the first gradle call. Re-warm from the seed `z feature new`
        // restored, when it was captured with --caches: those tarballs are already on disk, so
        // this costs no archive space at all. It is the seed's cache rather than this branch's
        // exact one -- the delta is usually a couple of dependencies, which gradle fetches.
        if (doCaches) {
            def tag = spec?.tag ?: zfinUtil.envField(envF, 'ZFIN_SEED') ?: zfinUtil.setting('ZFIN_SEED', '')
            def auxDir = tag ? zfinUtil.seedDir(tag) : null
            def absent = StackConfig.CACHE_VOLS.findAll { !zfinUtil.volumeExists("${project}_${it}") }
            def warmable = auxDir?.isDirectory() ? absent.findAll { zfinUtil.archiveFileFor(auxDir, it) } : []
            if (warmable) {
                info("re-warming build caches from $auxDir (${warmable.join(', ')})")
                // Reported per volume by restoreVolumes. A cache that fails to warm is not
                // fatal -- it is regenerable -- so nothing is checked here.
                zfinUtil.restoreVolumes(project, warmable, auxDir)
            } else if (!absent) {
                info("build caches already present -- nothing to re-warm")
            } else if (auxDir?.isDirectory()) {
                info("seed '$tag' carries no build caches (z seed create --caches) -- first gradle run will re-resolve dependencies")
            } else {
                info("no warm-cache snapshot for tag '${tag ?: '?'}' -- first gradle run will re-resolve dependencies")
            }
            timer.mark('re-warm caches')
        }

        // ---- bring it back ----------------------------------------------------------------
        if (!doUp) {
            info("volumes restored; --no-up, so the stack is left stopped.")
            info("start it with:  cd ${wt} && z up")
            timer.report("thaw '$slug' timing")
            return
        }

        def sharedStack = spec?.data == 'shared'
        if (sharedStack) {
            // Same dance as StackOps.up: the shared data tier must exist and be joined into
            // this stack's network before the app tier starts looking for `db`.
            if (!StackConfig.DATA_SERVICES.every { svc ->
                    captureOutput(['docker', 'ps', '-q',
                                   '--filter', "label=com.docker.compose.project=${zfinUtil.sharedProject()}",
                                   '--filter', "label=com.docker.compose.service=$svc"]) }) {
                info("shared data stack is down -- starting it (this stack shares it)")
                new SharedStack().run(['up'], zfinUtil)
            }
            runCommand(compose + ['up', '--no-start'], [check: false])
            zfinUtil.connectSharedData(project)
            runCommand(compose + ['start'], [check: false])
        } else {
            runCommand(compose + ['up', '-d'], [check: false])
        }

        // An outside proxy rediscovers the stack from its httpd's VIRTUAL_HOST once that
        // container is back on its network, so nothing to do here -- but say where it went.
        def url = zfinUtil.stackUrls(envF).primary
        timer.mark('start stack')
        info("thawed. ${url ?: ''}")
        info("(the archive at $src is kept -- remove it by hand once you trust the thaw)")
        timer.report("thaw '$slug' timing")
        if (restored) {
            println "    per volume:"
            restored.sort { -it.secs }.each { r ->
                println String.format("      %-16s %11s  %8.0f MB  %6.0f MB/s",
                        r.vn, zfinUtil.dur(r.secs), r.mb, r.secs > 0 ? r.mb / r.secs : 0)
            }
        }
    }
}
