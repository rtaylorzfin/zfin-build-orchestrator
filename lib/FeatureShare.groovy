// FeatureShare -- switch an EXISTING feature stack between its own db+solr and the shared ones,
// in place: the worktree, branch, deployed webapp, caches and Jenkins stay as they are.
//
//   z feature share   [<ticket>] [-y] [--seed T]
//       Drop this stack's own db+solr and attach it to the shared stack's (ZFIN_SHARED_PROJECT,
//       default zfin_shared) -- what `z feature new --shared-db` would have made. Starts the
//       shared stack first if it is down (--seed T seeds it on its first run; default: this
//       stack's own seed). DESTRUCTIVE: this stack's own db and solr volumes are deleted, along
//       with any writes or migrations made in them, so it asks first unless -y.
//
//   z feature unshare [<ticket>] [--seed T]
//       The reverse: detach from the shared db+solr and give the stack its own again, restored
//       from a seed (--seed T > the stack's ZFIN_SEED > $ZFIN_SEED > the newest). If the stack
//       still has own data volumes -- a share whose bring-up failed keeps them -- those are
//       reused instead. Nothing is deleted, so it does not ask.
//
// Both infer the ticket from the worktree you stand in. Only the stack's db clients (tomcat,
// tomcatdebug, jenkins) are stopped and started again: their connection pools point at the
// old db. Everything else -- httpd, compile, the claude sidecar -- keeps running, and resolves
// `db`/`solr` to the new containers on its next connection.
//
// Both compare liquibase's databasechangelog in the two dbs and report changesets one has and
// the other lacks: a migration this branch ran on its own db does not come with it to the
// shared one (re-running it migrates the shared db for EVERY sharer), and a db restored from
// a seed lacks what the branch ran on the shared one.
//
// SHARED DATA == SHARED WRITES: read-mostly features only. See docs/dev-stacks.md.
class FeatureShare {
    // The services that hold connections to db/solr and must be restarted to drop them. httpd
    // only proxies to tomcat; compile and claude open connections per command.
    static final List<String> DB_CLIENTS = ['tomcat', 'tomcatdebug', 'jenkins']
    static final String SHARED_OVERLAY = 'docker-compose.overlay-shared-db.yml'

    ZfinUtil z

    def run(List args, ZfinUtil zfinUtil) {
        z = zfinUtil
        if (z.helpRequested(args, this)) return
        def mode = args ? args[0] : null
        def rest = args.drop(1)
        def yes = false; def tagArg = null; def name = null
        for (int i = 0; i < rest.size(); i++) {
            def a = rest[i]
            if (a in ['-y', '--yes']) yes = true
            else if (a in ['--seed', '--tag']) {
                if (i + 1 >= rest.size()) z.die("z feature $mode: $a needs a seed tag", 2)
                tagArg = rest[++i]
            }
            else if (a.startsWith('-')) z.die("z feature $mode: unknown arg '$a'", 2)
            else if (name) z.die("z feature $mode: one feature at a time (got '$name' and '$a')", 2)
            else name = a
        }
        if (!name) name = z.featureSlugFromCwd()
        if (!name) z.die("usage: z feature $mode [<ticket>]   (or run it inside a feature worktree)", 2)
        def slug = name.toLowerCase()
        z.requireFeature(slug)
        def wt = new File(z.worktreesDir(), slug)
        def spec = z.stackSpec(wt)
        if (!spec) z.die("'$slug' has no readable docker/.env ($wt) -- nothing to switch")
        // A frozen stack's volumes are in its archive. Sharing would discard nothing yet leave
        // the archive restoring own data on thaw; unsharing would restore on top of it. Either
        // way, thaw first and decide then.
        def blocked = z.freezeBlocksUp(spec.project)
        if (blocked) z.die(blocked)
        if (spec.project == z.sharedProject())
            z.die("'${spec.project}' IS the shared project (ZFIN_SHARED_PROJECT) -- it cannot share with itself")

        switch (mode) {
            case 'share':   share(spec, wt, tagArg, yes); break
            case 'unshare': unshare(spec, wt, tagArg); break
            default: z.die("FeatureShare: mode must be share|unshare (got '$mode')", 2)
        }
    }

    // ---------------------------------------------------------------------------------------
    private void share(Map spec, File wt, String tagArg, boolean yes) {
        def project = spec.project as String
        if (spec.data == 'shared')
            z.die("'$project' already uses the shared db+solr (${z.sharedProject()}).  z feature unshare  to go back.")
        def ownVols = StackConfig.DATA_VOLS.collect { "${project}_${it}".toString() }.findAll { z.volumeExists(it) }

        // The shared stack must be up before anything here is touched: it is what this stack is
        // about to depend on, and the changelog comparison below needs it. Starting it is not
        // destructive, so it happens before the question; an abort leaves it running, harmlessly.
        SharedStack.ensureRunning(z, tagArg ?: spec.tag as String, 'z feature share',
                                  "Nothing was changed in '$project'.")

        def ownDb = containerOf(project, 'db')
        def drift = changelogDrift(ownDb, containerOf(z.sharedProject(), 'db'))
        if (drift == null) {
            println ">> could not compare liquibase changelogs" +
                    (ownDb ? " (no databasechangelog readable in one of the dbs)" : " ('$project' db is not running)") +
                    " -- check by hand if this branch has migrations"
        } else {
            if (drift.onlyA)
                System.err.println("!! ${drift.onlyA.size()} liquibase changeset(s) ran on this stack's own db but NOT on the shared one:\n" +
                                   sample(drift.onlyA) +
                                   "   They are lost with the own db. This branch's code will then run against a schema\n" +
                                   "   without them, and re-running them (gradle liquibasePostBuild) migrates the SHARED\n" +
                                   "   db for every sharer.")
            if (drift.onlyB)
                z.info("the shared db has ${drift.onlyB.size()} changeset(s) this stack's db does not " +
                       "(another branch's, or a newer seed)")
            if (!drift.onlyA && !drift.onlyB) z.info("liquibase changelogs match -- same schema history in both dbs")
        }

        def running = runningServices(project)
        def clients = DB_CLIENTS.findAll { it in running }
        if (!yes) {
            def con = System.console()
            if (!con) z.die("z feature share: no TTY -- pass -y to confirm", 2)
            println "About to switch '$project' to the shared db+solr (${z.sharedProject()}):"
            if (clients) println "  - stop ${clients.join(' ')} (their connection pools point at the old db), start them again after"
            println "  - remove this stack's own db and solr containers"
            if (ownVols) println "  - DELETE its own data volumes: ${ownVols.join(', ')}   (any writes/migrations in them are lost)"
            println "  - from then on, writes here reach EVERY stack sharing ${z.sharedProject()}"
            println "  - its db is no longer published on a host port (the shared db publishes none)"
            def a = con.readLine("Proceed? [y/N]: ")?.trim()?.toLowerCase()
            if (!(a?.startsWith('y'))) z.die("aborted -- nothing was changed in '$project'.", 0)
        }

        def compose = composeCmd(spec)
        if (clients) {
            z.info("stopping ${clients.join(' ')}")
            z.runCommand(compose + ['stop'] + clients)
        }
        // Removed, not just stopped: a stopped container keeps its `db`/`solr` aliases on this
        // stack's network, and two `db`s on one network is DNS round-robin between two databases.
        z.info("removing this stack's own db + solr containers")
        z.runCommand(compose + ['rm', '--stop', '--force'] + StackConfig.DATA_SERVICES)

        // The stack's composition, recorded where every later z command reads it (stackSpec):
        // after this, `z up` connects the shared containers itself, as for --shared-db stacks.
        def overlays = [SHARED_OVERLAY] + (spec.overlays as List).findAll { !(it in z.worktreeOverlay(wt)) }
        setEnv(new File(spec.envFile as String), [ZFIN_COMPOSE_OVERLAYS: overlays.join(':')])
        def fresh = z.stackSpec(wt)

        // The network outlives the containers removed above while anything else is on it; when
        // nothing is, the next `z up` creates it and connects the shared containers then.
        def net = "${project}_default".toString()
        boolean ok = true
        if (z.runQuietly(['docker', 'network', 'inspect', net]) == 0) {
            z.connectSharedData(project)
            ok = StackConfig.DATA_SERVICES.every { svc -> networksOf(containerOf(z.sharedProject(), svc)).contains(net) }
            if (ok && clients) {
                z.info("starting ${clients.join(' ')} on the shared data")
                ok = z.runCommand(composeCmd(fresh) + ['start'] + clients, [check: false]) == 0
            }
        }

        // Only once the stack is on the shared data: a failed bring-up keeps the own copy, so
        // `z feature unshare` can put it straight back without a ~minutes-long seed restore.
        if (!ok) {
            System.err.println("!! '$project' is switched to the shared data, but did not come up cleanly on it.\n" +
                               "   Its own volumes are KEPT (${ownVols.join(', ') ?: 'none'}): fix and `z up`, or go back\n" +
                               "   with  z feature unshare $project   (reuses them, no restore).")
            System.exit(1)
        }
        ownVols.each { vol ->
            if (z.runQuietly(['docker', 'volume', 'rm', vol]) == 0) z.info("deleted $vol")
            else System.err.println("!! could not delete $vol (still in use?) -- remove it by hand: docker volume rm $vol")
        }
        z.info("'$project' now uses the shared db+solr (${z.sharedProject()})" +
               (clients ? '' : " -- nothing was running; `z up` connects them on start"))
        z.info("back to its own data with:  z feature unshare $project")
    }

    // ---------------------------------------------------------------------------------------
    private void unshare(Map spec, File wt, String tagArg) {
        def project = spec.project as String
        if (spec.data != 'shared')
            z.die("'$project' already has its own db+solr.  z feature share  to switch it to the shared ones.")

        // Volumes first: a missing or foreign seed must fail before anything is stopped.
        def missing = StackConfig.DATA_VOLS.findAll { !z.volumeExists("${project}_${it}") }
        def tag = null
        if (missing) {
            tag = tagArg ?: spec.tag ?: z.setting('ZFIN_SEED') ?: z.newestSeed()
            if (!tag) z.die("no seed to restore this stack's own db+solr from -- make one (z seed new) or pass --seed <tag>")
            def seed = z.seedDir(tag as String)
            if (!seed.isDirectory()) z.die("no seed '$tag' on this host ($seed)")
            def lacks = missing.findAll { !z.archiveFileFor(seed, it) }
            if (lacks) z.die("seed '$tag' has no ${lacks.join(', ')} tarball -- pick one that does: --seed <tag>")
            def plat = z.seedPlatformProblem(tag as String)
            if (plat && !z.allowPlatformMismatch()) z.die(plat)
        } else {
            z.info("'$project' still has its own data volumes -- reusing them, no restore")
        }

        def running = runningServices(project)
        def clients = DB_CLIENTS.findAll { it in running }
        def compose = composeCmd(spec)
        if (clients) {
            z.info("stopping ${clients.join(' ')}")
            z.runCommand(compose + ['stop'] + clients)
        }
        // Off this stack's network BEFORE its own db joins it: two containers aliased `db` on one
        // network is DNS round-robin between two databases. Stopped ones too -- a stopped shared
        // container keeps its endpoint and would rejoin with it on `z shared up`.
        def net = "${project}_default".toString()
        StackConfig.DATA_SERVICES.each { svc ->
            def cid = containerOf(z.sharedProject(), svc, true)
            if (cid && networksOf(cid).contains(net)) {
                z.info("disconnect shared $svc from $net")
                z.runCommand(['docker', 'network', 'disconnect', '-f', net, cid], [check: false])
            }
        }

        if (missing) {
            z.info("restoring ${missing.join(' + ')} from seed '$tag'")
            def res = z.restoreVolumes(project, missing, z.seedDir(tag as String))
            def bad = res.findAll { !it.ok }
            if (bad) {
                // The shared overlay is still recorded, so `z up` reconnects the shared data:
                // the stack is back where it started, less whatever partial volume this left.
                bad.each { r -> if (z.volumeExists(r.vol as String)) z.runQuietly(['docker', 'volume', 'rm', r.vol]) }
                if (clients) z.info("reconnect to the shared data with:  z up")
                z.die("seed restore failed: ${bad.collect { it.vn }.join(', ')}\n" + bad.collect { it.err }.join('\n'))
            }
        }

        def overlays = (spec.overlays as List).findAll { it != SHARED_OVERLAY && !(it in z.worktreeOverlay(wt)) }
        def envUpdates = [ZFIN_COMPOSE_OVERLAYS: overlays.join(':')]
        // The seed this stack's data now comes from, so a later unshare/freeze/seed knows it.
        if (tag && tag != spec.tag) envUpdates.ZFIN_SEED = tag
        setEnv(new File(spec.envFile as String), envUpdates)
        def fresh = z.stackSpec(wt)

        // Up only if it was up: a stopped stack stays stopped and gets its own data on `z up`.
        // One `up`, so compose orders it -- the clients wait for db's healthcheck (depends_on).
        if (running) {
            def svcs = StackConfig.DATA_SERVICES + clients
            z.info("starting ${svcs.join(' ')} on its own data")
            z.runCommand(composeCmd(fresh) + ['up', '-d'] + svcs)
            def ownDb = containerOf(project, 'db')
            if (ownDb) {
                def drift = changelogDrift(ownDb, containerOf(z.sharedProject(), 'db'))
                if (drift?.onlyB)
                    System.err.println("!! the shared db has ${drift.onlyB.size()} liquibase changeset(s) this stack's restored db does not:\n" +
                                       sample(drift.onlyB) +
                                       "   If this branch ran them on the shared db, run them again here: gradle liquibasePostBuild")
            }
        }
        z.info("'$project' has its own db+solr again" + (tag ? " (from seed '$tag')" : '') +
               (running ? '' : " -- nothing was running; `z up` starts it"))
    }

    // ---------------------------------------------------------------------------------------
    /** `docker compose` for this stack, exactly as stackSpec composes it. */
    private List composeCmd(Map spec) {
        ['docker', 'compose', '-p', spec.project, '--env-file', spec.envFile] +
                (spec.compose as String).tokenize(':').collectMany { ['-f', it] }
    }

    /** The services of `project` with a running container. */
    private Set<String> runningServices(String project) {
        z.captureOutput(['docker', 'ps', '--filter', "label=com.docker.compose.project=$project",
                         '--format', '{{.Label "com.docker.compose.service"}}']).readLines().findAll { it } as Set
    }

    /** The container of `project`'s `svc`: running only, or (all) stopped too. Null if none. */
    private String containerOf(String project, String svc, boolean all = false) {
        z.captureOutput(['docker', 'ps'] + (all ? ['-a'] : []) + ['-q',
                         '--filter', "label=com.docker.compose.project=$project",
                         '--filter', "label=com.docker.compose.service=$svc"]).readLines().find { it } ?: null
    }

    private List<String> networksOf(String cid) {
        if (!cid) return []
        z.captureOutput(['docker', 'inspect', cid, '--format',
                         '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}']).split() as List
    }

    /** Replace (in place, keeping the file's order) or append each key in this .env. */
    private void setEnv(File envF, Map<String, String> kv) {
        def seen = [] as Set
        def lines = envF.readLines().collect { line ->
            def k = kv.keySet().find { line.startsWith(it + '=') }
            if (!k) return line
            seen << k
            "${k}=${kv[k]}".toString()
        }
        kv.each { k, v -> if (!(k in seen)) lines << "${k}=${v}".toString() }
        def tmp = new File(envF.parentFile, envF.name + '.part')
        tmp.text = lines.join('\n') + '\n'
        java.nio.file.Files.move(tmp.toPath(), envF.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    /** Wait (up to ~90s) for a just-started postgres to answer. */
    private boolean waitReady(String cid) {
        for (int i = 0; i < 90; i++) {
            if (z.runQuietly(StackConfig.dbHealthCheck(cid)) == 0) return true
            Thread.sleep(1000)
        }
        System.err.println("!! db $cid has not answered pg_isready after 90s")
        false
    }

    /** Liquibase changesets in db `a` but not `b` (onlyA), and in `b` but not `a` (onlyB) --
     *  keyed as liquibase keys them, id + author + file. Null when either is unreadable. */
    private Map changelogDrift(String a, String b) {
        def read = { String cid ->
            // A db started moments ago -- the shared one, when `share` just brought it up --
            // refuses connections for a second or two after its container is "running".
            if (!cid || !waitReady(cid)) return null
            def out = z.captureOutput(['docker', 'exec', cid, 'psql', '-U', 'postgres', '-d', 'zfindb', '-Atc',
                    "select id || '|' || author || '|' || filename from databasechangelog"])
            out ? (out.readLines().findAll { it } as Set) : null
        }
        def ca = read(a), cb = read(b)
        if (ca == null || cb == null) return null
        [onlyA: (ca - cb).sort(), onlyB: (cb - ca).sort()]
    }

    /** A few changesets as `file: id`, for a warning. */
    private static String sample(List<String> keys) {
        def shown = keys.take(5).collect { k ->
            def p = k.split('\\|', 3)
            "     ${p.size() == 3 ? new File(p[2]).name + ': ' + p[0] : k}"
        }
        shown.join('\n') + '\n' + (keys.size() > 5 ? "     ...and ${keys.size() - 5} more\n" : '')
    }
}
