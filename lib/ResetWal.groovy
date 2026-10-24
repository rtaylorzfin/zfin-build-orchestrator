// ResetWal -- `z resetwal [<container>|<ticket>] [-y]`: shed the WAL a postgres keeps for
// reuse. Stops the database, runs pg_resetwal on its data directory, starts it again.
//
//   z resetwal                 the db of the stack that owns the working directory
//   z resetwal <ticket>        that feature's db
//   z resetwal <container>     any postgres container, by name or id -- one z did not make,
//                              or the shared data stack's (e.g. zfin_shared_resources-db-1)
//   -y, --yes                  skip the confirmation prompt
//
// WHY: postgres keeps written WAL up to max_wal_size for reuse -- 10GB in the base compose
// file -- and it drains only as new WAL is written. Measured at 10G of one feature's 25G
// pg_data. A cleanly shut-down cluster needs none of it: every change is already in the data
// files. Feature stacks now cap it at 1GB (docker-compose.overlay-feature.yml), which stops it
// growing but does not shed what is there; this does. `z freeze` does the same on its way to
// the archive.
//
// SAFE BY CONSTRUCTION, NOT BY CHECKING: pg_resetwal runs WITHOUT -f, and then refuses a
// cluster that was not shut down cleanly -- the one case where resetting would throw writes
// away. The stop gets 120s so postgres can finish its shutdown checkpoint (docker's default
// 10s can end in SIGKILL, which pg_resetwal would then, rightly, refuse).
//
// WHAT IT COSTS: the database is down for a minute, and whatever was connected loses its
// connection (tomcat usually reconnects; `z restart tomcat` if not). It also ends the WAL
// history -- fine for a dev database, wrong for one with replicas or WAL archiving -- which is
// why it always asks first.
class ResetWal {
    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return
        def die = zfinUtil.&die; def info = zfinUtil.&info
        def runCommand = zfinUtil.&runCommand; def captureOutput = zfinUtil.&captureOutput
        def runQuietly = zfinUtil.&runQuietly

        def target = null; def yes = false
        args.each { a ->
            if (a in ['-y', '--yes']) yes = true
            else if (a.startsWith('-')) die("z resetwal: unknown arg '$a'", 2)
            else if (target) die("z resetwal: one container or ticket, not several", 2)
            else target = a
        }

        // ---- which database ---------------------------------------------------------------
        // A container by that name or id wins: that is the way in for a stack z did not make.
        // Otherwise a feature ticket; with nothing named, the stack that owns the working
        // directory (a feature worktree or a checkout -- the same test `z help` uses).
        def isContainer = { String ref -> ref && runQuietly(['docker', 'container', 'inspect', ref]) == 0 }
        def cid = null; def stackDir = null; def project = null; def spec = null
        if (target && isContainer(target)) {
            cid = target
        } else {
            if (target) {
                def slug = target.toLowerCase()
                zfinUtil.requireFeature(slug)
                stackDir = new File(zfinUtil.worktreesDir(), slug)
            } else {
                def top = captureOutput(['git', 'rev-parse', '--show-toplevel'])
                stackDir = top ? new File(top) : null
            }
            spec = stackDir ? zfinUtil.stackSpec(stackDir) : null
            if (!spec?.project)
                die("usage: z resetwal [<container>|<ticket>] [-y]\n" +
                    "   (or run it inside a checkout or feature worktree to mean that stack's db)", 2)
            project = spec.project
            if (spec.data == 'shared') {
                def sharedDb = captureOutput(['docker', 'ps', '-a', '--format', '{{.Names}}',
                        '--filter', "label=com.docker.compose.project=${zfinUtil.sharedProject()}",
                        '--filter', 'label=com.docker.compose.service=db']).readLines().find { it }
                die("'$project' uses the shared data stack -- it has no database of its own.\n" +
                    "   The shared one is a container:  z resetwal ${sharedDb ?: '<shared db container>'}")
            }
            cid = captureOutput(['docker', 'ps', '-aq', '--filter', "label=com.docker.compose.project=$project",
                                 '--filter', 'label=com.docker.compose.service=db']).readLines().find { it }
            if (!cid) die("'$project' has no db container -- frozen, or never started? (z feature ls)")
        }

        def inspect = { String fmt -> captureOutput(['docker', 'container', 'inspect', cid, '--format', fmt]) }
        def name    = inspect('{{.Name}}').replaceFirst('^/', '')
        // The image ID, not its tag: tags move (ZFIN's :main does), and pg_resetwal must be the
        // same postgres major that wrote the data directory.
        def img     = inspect('{{.Image}}')
        def imgRef  = inspect('{{.Config.Image}}')
        def running = inspect('{{.State.Running}}') == 'true'
        def cproj   = inspect('{{index .Config.Labels "com.docker.compose.project"}}')
        // A postgres container says where its data is; anything without PGDATA is not one.
        def pgdata  = inspect('{{range .Config.Env}}{{println .}}{{end}}').readLines()
                .find { it.startsWith('PGDATA=') }?.substring(7)
        if (!pgdata) die("$name does not look like a postgres container (no PGDATA in its environment).")
        // --volumes-from reaches the data however it is mounted -- a named volume, a bind mount,
        // the image's anonymous volume -- and -e PGDATA covers a container that moved it.
        def mounts = ['--volumes-from', cid, '-e', "PGDATA=$pgdata".toString()]

        // Restart through compose when z knows the stack: `up -d db` recreates the container when
        // its settings changed, which is what applies the overlay's WAL cap to a db created
        // before it existed (and is a plain start otherwise). A bare
        // container just gets `docker start`.
        def compose = null
        if (spec && cproj == project) {
            def envF = new File(stackDir, 'docker/.env')
            compose = ['docker', 'compose', '-p', project, '--env-file', envF.absolutePath] +
                      spec.compose.tokenize(':').collectMany { ['-f', it] }
        }

        // Who loses their connection: running containers on the networks it is attached to
        // (the default bridge aside -- every plain `docker run` lands there). For the shared
        // db that is other developers' stacks, so name them.
        def nets = inspect('{{range $k, $v := .NetworkSettings.Networks}}{{println $k}}{{end}}').readLines()
                .findAll { it && !(it in ['bridge', 'host', 'none']) }
        def users = nets.collectMany { n ->
            captureOutput(['docker', 'network', 'inspect', n, '--format', '{{range .Containers}}{{println .Name}}{{end}}'])
                    .readLines().findAll { it && it != name }
        }.unique().sort()
        def otherStacks = users.collect {
            captureOutput(['docker', 'container', 'inspect', it, '--format', '{{index .Config.Labels "com.docker.compose.project"}}'])
        }.findAll { it && it != cproj }.unique().sort()

        long before = zfinUtil.pgWalBytesVia(mounts, img)
        def gb = { long b -> b < 0 ? '?' : b < (1L << 30) ? String.format('%.0f MB', b / 1048576.0)
                                                          : String.format('%.1f GB', b / 1073741824.0) }
        def step = { String what, String why -> println String.format("  - %-36s %s", what, why).replaceAll(/\s+$/, '') }

        // ---- the warning --------------------------------------------------------------------
        if (!yes) {
            def con = System.console()
            if (!con) die("z resetwal: no TTY to confirm on -- pass -y", 2)
            println "About to RESET THE WAL of postgres container $name" + (cproj ? "  (stack '$cproj')" : '') + ":"
            println "  pg_wal is ${gb(before)} now; pg_resetwal leaves one 16 MB segment."
            if (running) {
                step("docker stop -t 120 $name", "the database goes DOWN for about a minute")
                if (users) {
                    println "      connected and running: ${users.join(', ')}"
                    println "      they lose their connections (tomcat usually reconnects; else z restart tomcat)"
                }
                if (otherStacks)
                    println "      !! this db serves OTHER stacks too: ${otherStacks.join(', ')}"
            } else {
                step("(already stopped)", "$name stays stopped")
            }
            step("pg_resetwal $pgdata", "refuses by itself unless the shutdown was clean")
            if (running)
                compose ? step("docker compose up -d db", "recreated if its compose settings changed (e.g. the WAL cap)")
                        : step("docker start $name", "")
            println "  The WAL history ends here: right for a dev database, wrong for one with replicas,"
            println "  WAL archiving or point-in-time recovery."
            def a = con.readLine("Proceed? [y/N]: ")?.trim()?.toLowerCase()
            if (!(a?.startsWith('y'))) die("aborted.", 0)
        }

        // ---- stop, reset, start -------------------------------------------------------------
        if (running) {
            info("stopping $name (up to 120s for postgres to checkpoint)")
            runCommand(['docker', 'stop', '-t', '120', cid])
        }
        def restart = {
            if (!running) return
            if (compose) runCommand(compose + ['up', '-d', 'db'], [check: false])
            else runCommand(['docker', 'start', cid], [check: false])
        }
        info("pg_resetwal ($imgRef)")
        if (!zfinUtil.resetWal(mounts, img)) {
            System.err.println("!! pg_resetwal declined (its reason is above) -- nothing was changed.")
            if (running) {
                System.err.println("   Starting the database again as it was; postgres recovers on its own if it needs to.")
                restart()
            }
            System.exit(1)
        }
        long after = zfinUtil.pgWalBytesVia(mounts, img)
        restart()

        // Wait for it to answer, so "done" means usable. pg_isready, not the zfin probe: this
        // may be anybody's postgres, with any database name. A recreated container has a new id.
        if (running) {
            def now = compose
                ? captureOutput(['docker', 'ps', '-q', '--filter', "label=com.docker.compose.project=$project",
                                 '--filter', 'label=com.docker.compose.service=db']).readLines().find { it }
                : cid
            def ready = false
            for (int i = 0; i < 90 && now && !ready; i++) {
                if (runQuietly(['docker', 'exec', now, 'pg_isready', '-q']) == 0) ready = true else Thread.sleep(1000)
            }
            if (!ready) System.err.println("!! $name has not answered pg_isready after 90s -- check `docker logs ${now ?: name}`")
        }
        info("WAL reset: ${gb(before)} -> ${gb(after)}" + (running ? "; $name is back up" : '') +
             (users && running ? ". If tomcat lost its pool: z restart tomcat" : ''))
    }
}
