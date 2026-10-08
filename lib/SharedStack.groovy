// SharedStack -- manage the dedicated shared data stack (Compose project: the
// ZFIN_SHARED_PROJECT setting, default zfin_shared -- give each dev tree its own in its
// zfin-dev.env, since project names are global to the Docker host):
// ONE db + solr, restored from a seed, run here -- and feature stacks created with
// `z feature new --shared-db` reach them (instead of seeding their own copies) by connecting
// these shared containers into the feature's own network -- see ZfinUtil.connectSharedData.
//   z shared up   [--seed T]        boot the shared db+solr
//   z shared down [--rm-data]      stop it (keep the shared copy; --rm-data discards it)
//   z shared status                show the shared stack + which features share it
//   z shared freeze [--to DIR] [--compress]   park the shared db+solr: archive + down -v
//   z shared thaw   [--from DIR]   restore them and start the shared stack
//
// freeze/thaw here is the same machinery as
// `z feature freeze`, one level up. It is a HOST-level operation -- every attached feature
// stops working the moment the shared data tier goes -- so it refuses while sharers are
// running unless you say --stop-sharers, which stops their app tiers first, in order.
//
// SHARED DATA == SHARED WRITES: read-mostly features only. See docs/dev-stacks.md.
class SharedStack {
    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return
        def die = zfinUtil.&die; def info = zfinUtil.&info
        def runCommand = zfinUtil.&runCommand; def captureOutput = zfinUtil.&captureOutput
        def DOCKER = zfinUtil.DOCKER
        def project = zfinUtil.sharedProject()

        def sub  = args ? args[0] : 'status'
        def rest = args.drop(1)
        def tagArg = null; def rmData = false
        def archDir = null; def stopSharers = false; def force = false; def compressShared = null
        for (int i = 0; i < rest.size(); i++) {
            switch (rest[i]) {
                case '--seed': case '--tag': tagArg = rest[++i]; break
                case '--rm-data': case '--volumes': rmData = true; break
                case '--to': case '--from': archDir = rest[++i]; break
                case '--stop-sharers':      stopSharers = true; break
                case '--compress': case '-z': compressShared = true; break
                case '--no-compress': compressShared = false; break
                // --force is accepted as an alias for muscle memory, but --stop-sharers is the
                // honest name: the flag AUTHORISES stopping them, it does not silence a check.
                case '--force': case '-f':  stopSharers = true; force = true; break
                default: die("z shared: unknown arg '${rest[i]}'", 2)
            }
        }

        // Seed selection mirrors new-feature: --seed > $ZFIN_SEED > newest seed on this host.
        def tag = tagArg ?: zfinUtil.setting('ZFIN_SEED') ?: zfinUtil.newestSeed()
        // A seed from another platform restores a PostgreSQL data directory this host cannot
        // safely use. Refused rather than warned: the failure is silent wrong answers, so a
        // warning in a long provisioning log is not enough.
        def platProblem = tag ? zfinUtil.seedPlatformProblem(tag) : null
        if (platProblem && !zfinUtil.allowPlatformMismatch()) zfinUtil.die(platProblem)

        // The shared stack = base + the shared provider overlay, run as `project` off the base
        // docker/.env. No data overlay: like a feature stack, its volumes are restored from a
        // seed before anything starts, so the stock db/solr images find their data in place.
        def files = [new File(DOCKER, 'docker-compose.yml'), new File(zfinUtil.COMPOSE, 'docker-compose.overlay-shared.yml')]
        def compose = ['docker', 'compose', '-p', project, '--env-file', new File(DOCKER, '.env').absolutePath] +
                      files.collectMany { ['-f', it.absolutePath] }
        if (tag) {
            zfinUtil.childEnv['ZFIN_SEED'] = tag
        }
        // Solr at feature sizing, not the base file's production default (16g limit, 12g heap),
        // which a laptop's Docker VM cannot give it. One solr serving a few read-mostly features
        // needs no more than one feature's own. A value the base docker/.env sets wins.
        def baseEnv = new File(DOCKER, '.env')
        if (!zfinUtil.envField(baseEnv, 'DOCKER_SOLR_MEM_LIMIT')) zfinUtil.childEnv['DOCKER_SOLR_MEM_LIMIT'] = StackConfig.FEATURE_SOLR_MEM
        if (!zfinUtil.envField(baseEnv, 'DOCKER_SOLR_HEAP'))      zfinUtil.childEnv['DOCKER_SOLR_HEAP']      = StackConfig.FEATURE_SOLR_HEAP

        // ZFIN_SHARED_PROJECT can also point features at a real instance's data; that project
        // is not this command's to start, stop, freeze or thaw. Nor is a shared stack made by
        // earlier tooling, which lacks the label -- in particular one another dev tree runs.
        if (sub in ['up', 'down', 'freeze', 'thaw'] && !zfinUtil.managedSharedStack(project))
            die("'$project' is not a shared stack z manages: its db has no ${StackConfig.SHARED_DATA_LABEL} label.\n" +
                "   It may be a real instance, or one another dev tree or older tooling runs. Give this tree\n" +
                "   its own:  z config set ZFIN_SHARED_PROJECT=<name>   (in this tree's zfin-dev.env)")

        // A feature shares this data by having the shared db connected into its own
        // `<project>_default` network, so the sharers are exactly those networks. One
        // derivation, used by up (to warn), freeze (to refuse) and status (to report).
        def sharers = {
            def cid = captureOutput(['docker', 'ps', '-q',
                '--filter', "label=com.docker.compose.project=$project",
                '--filter', 'label=com.docker.compose.service=db'])
            if (!cid) return []
            captureOutput(['docker', 'inspect', cid, '--format',
                '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}']).split()
                .findAll { it.endsWith('_default') }.collect { it.replaceFirst(/_default$/, '') }
        }
        // ...and which of those actually have containers running, which is the difference
        // between "would break" and "is breaking right now".
        def runningProjects = {
            captureOutput(['docker', 'ps', '--format', '{{.Label "com.docker.compose.project"}}'])
                .readLines().findAll { it } as Set
        }

        def archiveRoot = new File(archDir ?: zfinUtil.archiveDir(sub in ['freeze', 'thaw']))
        def dest = new File(archiveRoot, project)
        def manifestFile = new File(dest, StackConfig.FREEZE_MANIFEST)
        def sharedVols = StackConfig.DATA_VOLS

        switch (sub) {
            case 'up':
                // Seeding happens HERE, not via an image: `up` on empty volumes restores the
                // seed first, exactly as `z feature new` does. A stack that already has data
                // keeps it -- restoring over a live shared tier would discard everyone's work.
                def seeded = zfinUtil.runQuietly(['docker', 'volume', 'inspect', "${project}_pg_data".toString()]) == 0
                if (!seeded) {
                    if (!tag) die("no seed found -- capture one (z seed create) or pass --seed <tag>")
                    def seed = zfinUtil.seedDir(tag)
                    StackConfig.DATA_VOLS.each { vn ->
                        if (!zfinUtil.archiveFileFor(seed, vn)) die("seed '$tag' has no $vn tarball ($seed)")
                    }
                    info("shared data tier is empty -- restoring seed '$tag' (this is the one-time copy)")
                    def res = zfinUtil.restoreVolumes(project, StackConfig.DATA_VOLS, seed)
                    def bad = res.findAll { !it.ok }
                    if (bad) die("seed restore failed: ${bad.collect { it.vn }.join(', ')}\n" + bad.collect { it.err }.join('\n'))
                    res.each { r -> info(String.format("restored %s (%.0f MB) in %.1fs", r.vol, r.mb, r.secs)) }
                }
                // If sharers are already attached, `up` may RECREATE the data tier (it does
                // whenever the service definition changed), and that kills every connection
                // their webapps hold: c3p0 surfaces it as
                //     FATAL: terminating connection due to administrator command
                // and the stack serves 500s until its tomcat is restarted. Observed for real.
                // Warn with the fix rather than let it look like the feature stack broke.
                def attached = sharers()
                if (attached) {
                    System.err.println("!! ${attached.size()} feature(s) are attached to this shared data tier: ${attached.join(', ')}")
                    System.err.println("   If this recreates db/solr, their connection pools die and they serve 500s.")
                    System.err.println("   Recover with:  z restart tomcat   (in each attached stack)")
                }
                info("shared data stack '$project' up (tag $tag) -> ONE db+solr copy that --shared-db features attach to")
                runCommand(compose + ['up', '-d'] + StackConfig.DATA_SERVICES)
                // A RECREATED container keeps none of the network connections the old one had, so
                // every sharer would silently lose `db`/`solr`. Reattach the ones attached before;
                // connectSharedData skips a network the container is already on.
                attached.each { proj ->
                    if (zfinUtil.runQuietly(['docker', 'network', 'inspect', "${proj}_default"]) == 0)
                        zfinUtil.connectSharedData(proj)
                }
                info("attach features with: z feature new <ticket> --shared-db")
                break
            case 'down':
                runCommand(compose + ['down'] + (rmData ? ['-v'] : []), [check: false])
                info(rmData ? "shared stack down; the shared copy was discarded (-v)"
                            : "shared stack down; shared copy kept (z shared down --rm-data to discard)")
                break
            case 'status':
                runCommand(compose + ['ps'], [check: false])
                // A feature shares this data by having the shared db connected into its own
                // `<project>_default` network, so the sharers are exactly those networks.
                def dbcid = captureOutput(['docker', 'ps', '-q',
                    '--filter', "label=com.docker.compose.project=$project",
                    '--filter', 'label=com.docker.compose.service=db'])
                if (!dbcid) { info("shared stack not running (z shared up)"); break }
                def features = captureOutput(['docker', 'inspect', dbcid, '--format',
                    '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}']).split()
                    .findAll { it.endsWith('_default') }.collect { it.replaceFirst(/_default$/, '') }
                info("features sharing this db: ${features ? features.join(', ') : '(none connected)'}")
                break
            case 'freeze':
                if (manifestFile.isFile() && !force)
                    die("the shared stack already has an archive at $dest\n" +
                        "   z shared thaw   to restore it, or --force to overwrite")

                // THE GUARD. Freezing the shared data tier takes every attached feature down
                // with it, so this refuses rather than surprising you -- and --stop-sharers
                // authorises the stopping instead of suppressing the warning.
                def live = runningProjects()
                def attachedNow = sharers()
                def busy = attachedNow.findAll { live.contains(it) }
                if (busy) {
                    System.err.println("!! ${busy.size()} feature stack(s) are RUNNING on this shared data tier:")
                    busy.each { System.err.println("     $it") }
                    if (!stopSharers)
                        die("freezing would break them mid-flight.\n" +
                            "   Stop them yourself, or authorise it:  z shared freeze --stop-sharers")
                    // Ordered, exactly as a feature freeze orders its own stack: every app tier
                    // down before any data tier is touched.
                    def appTier = StackConfig.APP_SERVICES + ['tomcatdebug', 'jenkins', StackConfig.BUILD_SERVICE, 'claude']
                    busy.each { proj ->
                        info("stopping $proj's app tier before touching shared data")
                        runCommand(['docker', 'compose', '-p', proj, 'stop'] + appTier, [check: false])
                    }
                } else if (attachedNow) {
                    info("attached but not running: ${attachedNow.join(', ')} (nothing to stop)")
                }

                if (!dest.exists() && !dest.mkdirs()) die("cannot create $dest")
                info("stopping shared db+solr (up to 120s for postgres to checkpoint)")
                runCommand(compose + ['stop', '-t', '120'] + StackConfig.DATA_SERVICES, [check: false])

                def dbc = captureOutput(['docker', 'ps', '-aq',
                        '--filter', "label=com.docker.compose.project=$project",
                        '--filter', 'label=com.docker.compose.service=db'])
                if (dbc) {
                    def clean = false
                    for (int i = 0; i < 15 && !clean; i++) {
                        if (zfinUtil.captureOutputMerged(['docker', 'logs', '--tail', '60', dbc]).contains('database system is shut down')) clean = true
                        else Thread.sleep(1000)
                    }
                    if (clean) info("shared db shut down cleanly (checkpoint complete)")
                    else if (!force) die("never saw 'database system is shut down' after a 120s stop -- " +
                                         "refusing to archive an unclean database (--force to override)")
                }

                def presentShared = sharedVols.findAll { zfinUtil.volumeExists("${project}_${it}") }
                def t0 = System.currentTimeMillis()
                if (compressShared == null) compressShared = zfinUtil.hasPigz()   // see FeatureFreeze
                def sext = compressShared ? 'tgz' : 'tar'
                presentShared.each { vn -> zfinUtil.captureVolume("${project}_${vn}", new File(dest, "${vn}.${sext}"), compressShared) }
                def secs = (System.currentTimeMillis() - t0) / 1000.0
                def bytes = presentShared.sum(0L) { new File(dest, "${it}.${sext}").length() } as long
                manifestFile.text = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson([
                    slug: project, project: project, data: 'archived',
                    volumes: presentShared, bytes: bytes, capturedIn: secs, compressed: compressShared,
                    seed: tag,
                    sharersAtFreeze: attachedNow, frozenAt: new Date().format("yyyy-MM-dd HH:mm:ss"),
                    toolVersion: 1]))

                info("down -v the shared stack (the archive is the copy now)")
                runCommand(compose + ['down', '-v'], [check: false])
                info(String.format("shared stack frozen: %.1f GB in %.0fs -> %s", bytes / 1073741824.0, secs, dest))
                if (attachedNow) info("its sharers must be thawed/restarted after `z shared thaw`: ${attachedNow.join(', ')}")
                break

            case 'thaw':
                if (!manifestFile.isFile()) die("no shared archive at $dest (--from DIR if elsewhere)")
                def sm = new groovy.json.JsonSlurper().parse(manifestFile)
                def svols = (sm.volumes ?: []) as List
                def already = svols.findAll { zfinUtil.volumeExists("${project}_${it}") }
                if (already && !force)
                    die("these shared volumes already exist: ${already.join(', ')}\n" +
                        "   The shared stack is not frozen. `z shared down --rm-data` first to replace it.")
                info("thaw shared stack  frozen ${sm.frozenAt}  volumes=${svols.join(', ')}")
                def res = zfinUtil.restoreVolumes(project, svols, dest)
                def bad = res.findAll { !it.ok }
                if (bad) die("restore failed: ${bad.collect { it.vn }.join(', ')}\n" + bad.collect { it.err }.join('\n'))
                runCommand(compose + ['up', '-d'] + StackConfig.DATA_SERVICES)
                info("shared data back up. Each sharer needs `z up` in its worktree to reconnect" +
                     (sm.sharersAtFreeze ? " (was: ${(sm.sharersAtFreeze as List).join(', ')})" : ''))
                break

            default: die("z shared: unknown subcommand '$sub' (up|down|status|freeze|thaw)", 2)
        }
    }
}
