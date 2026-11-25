// StackOps -- the stack lifecycle commands, acting on the stack that owns the working
// directory (ZfinUtil.resolveStack: git for the checkout root, then its docker/.env):
// run / exec / up / stop / down / pull / log / restart / status. z routes the whole family here
// (args[0] is the op), so z itself stays pure routing. All but `status` need a stack -- either
// activated (COMPOSE_FILE in the environment) or auto-detected from cwd by
// ZfinUtil.resolveStack, which z calls for this family; status reports gracefully with neither.
//
// Read stack vars through zfinUtil.stackVar() (not System.getenv) and spawn compose through
// zfinUtil.runCommand() (which injects childEnv), so activated and auto-detected stacks behave
// identically.
class StackOps {
    def run(List args, ZfinUtil zfinUtil) {
        def op   = args ? args[0] : 'status'
        def rest = args.drop(1)
        def die  = zfinUtil.&die

        def compose = { List a -> System.exit(zfinUtil.runCommand(['docker', 'compose'] + a, [check: false])) }
        def requireStack = {
            if (!zfinUtil.stackVar('COMPOSE_FILE'))
                die("z $op: no stack found. Run this from inside a checkout or feature worktree\n" +
                    "   whose docker/.env names a COMPOSE_PROJECT_NAME -- that file is what makes a\n" +
                    "   directory a stack.")
        }

        // The proxy network this stack's httpd joins, or null when it joins none. Read from the
        // stack's own .env, where z feature new recorded it, and only when the overlay that
        // does the joining is in the stack's compose files.
        def stackProxyNet = { ->
            if (!(zfinUtil.stackVar('COMPOSE_FILE') ?: '').contains('overlay-proxy-network')) return null
            def envf = zfinUtil.stackVar('COMPOSE_ENV_FILES')
            zfinUtil.envField(envf ? new File(envf) : null, 'ZFIN_PROXY_NETWORK') ?: null
        }

        // run/exec: split docker flags (-u root, before OR after the service) from the service
        // name and the bash args. First bare word = service (default compile); first bash flag
        // (e.g. -c) ends parsing and it + the rest go to bash.
        // Validate service names before handing them to compose. Compose answers a typo with a
        // bare `no such service: x` -- no `!!` prefix, so it does not read as ours, and no list,
        // though the compose file we just resolved names every valid one. Flags and anything
        // after a `--` are left alone; only bare words are checked.
        def checkServices = { List a ->
            def files = (zfinUtil.stackVar('COMPOSE_FILE') ?: '').tokenize(':').findAll { it }
            def known = files.collectMany { f ->
                def txt = new File(f).isFile() ? new File(f).text : ''
                def out = []; def inServices = false
                txt.readLines().each { line ->
                    if (line ==~ /^[a-z].*:\s*$/) inServices = line.startsWith('services:')
                    else if (inServices) { def m = line =~ /^  ([a-z][a-z0-9_-]*):\s*$/; if (m) out << m[0][1] }
                }
                out
            } as Set
            if (!known) return                        // could not read the files; say nothing
            def bad = a.findAll { !it.startsWith('-') } .findAll { !(it in known) }
            if (bad) zfinUtil.die("no such service: ${bad.join(', ')}\n" +
                                  "   services in this stack: ${known.sort().join(' ')}")
        }

        def parseSvc = { List a ->
            def dockerArgs = []; def service = null; int i = 0
            while (i < a.size()) {
                def t = a[i]
                if (t in ['-u', '--user']) {
                    if (i + 1 >= a.size()) die("$t requires an argument")
                    dockerArgs += [t, a[i + 1]]; i += 2
                } else if (t.startsWith('-')) break
                else if (service == null) { service = t; i++ }
                else break
            }
            [service ?: StackConfig.BUILD_SERVICE, dockerArgs, a.drop(i)]
        }
        // The claude sidecar needs its host-side token file to exist before compose tries to
        // bind-mount it -- Docker silently creates a DIRECTORY for a missing bind source, which
        // then fails confusingly inside the container. Create it empty (0600) and let the
        // container's profile explain how to fill it, rather than failing here: entering the
        // sidecar to read the instructions is a perfectly reasonable thing to do.
        def prepareClaudeToken = {
            def f = new File(zfinUtil.setting('ZFIN_CLAUDE_TOKEN_FILE', StackConfig.CLAUDE_TOKEN_FILE_DEFAULT).replaceFirst('^~', System.getProperty('user.home')))
            zfinUtil.childEnv['ZFIN_CLAUDE_TOKEN_FILE'] = f.absolutePath
            if (f.isFile()) {
                if (f.length() == 0)
                    zfinUtil.info("note: ${f} is empty -- run `claude setup-token` on the HOST and save it there")
                return
            }
            if (f.isDirectory())
                die("$f is a directory, not a token file. Docker creates one when the bind source " +
                    "is missing; remove it and re-run.")
            f.parentFile.mkdirs()
            f.text = ''
            // Best effort: a credential file should not be world-readable even while empty.
            try { f.setReadable(false, false); f.setReadable(true, true); f.setWritable(false, false) } catch (ignored) { }
            zfinUtil.info("created an empty token file at $f -- run `claude setup-token` on the HOST and save it there")
        }

        // `up` and `run` create whatever volumes are missing -- on a frozen stack, empty ones in
        // place of the archived data (see ZfinUtil.freezeBlocksUp). exec needs a running
        // container, so it cannot get that far.
        def refuseIfFrozen = {
            def why = zfinUtil.freezeBlocksUp(zfinUtil.stackVar('COMPOSE_PROJECT_NAME'))
            if (why) die(why)
        }

        def runExec = { boolean isExec, List a ->
            requireStack()
            if (!isExec) refuseIfFrozen()
            def (svc, da, ba) = parseSvc(a)
            if (svc == StackConfig.CLAUDE_SERVICE) prepareClaudeToken()
            if (isExec) compose(['exec'] + (System.console() ? [] : ['-T']) + da + [svc, 'bash', '-l'] + ba)
            // --no-deps: `docker compose run` starts a service's dependencies by default, so a
            // one-off command in a service that declares any becomes a stack boot -- `z run
            // jenkins -c 'cat <file>'` waited on a 19G Postgres going healthy. Dependencies are
            // sequenced by the commands that need them (`z build` ups db before load-db), not by
            // a graph that exists for `up` and `down` ordering. Without this, no service anyone
            // might `z run` can safely declare depends_on, which is why `compile` has none.
            // `z run claude` with nothing else to do starts CLAUDE, not a shell you then have
            // to type `claude` into. Still a login shell underneath, because the profile sets
            // SOURCEROOT/TARGETROOT and the agent needs them. Any argument -- `-c "..."`, or a
            // command to run -- means you wanted the shell, so scripted uses are unaffected.
            else if (svc == StackConfig.CLAUDE_SERVICE && !ba)
                        compose(['run', '--rm', '--no-deps'] + da + [svc, 'bash', '-l', '-c', 'claude'])
            else        compose(['run', '--rm', '--no-deps'] + da + [svc, 'bash', '-l'] + ba)
        }

        switch (op) {
            case 'run':     runExec(false, rest); break
            case 'exec':    runExec(true, rest); break
            case 'up':
                requireStack()
                refuseIfFrozen()
                // --arch amd64|arm64: start these services from the other architecture's image,
                // e.g. an amd64-only jenkins under emulation on an arm64 host. ZFIN tags amd64
                // BARE and arm64 `-arm64`, so a platform name is taken rather than the suffix --
                // `--arch ''` for amd64 would be both unguessable and easy to lose in a shell.
                // It overrides DOCKER_ARCH (and DOCKER_DB_ARCH, which db reads first) for this one
                // compose run; the shell environment outranks the .env in compose interpolation.
                int ai = rest.findIndexOf { it == '--arch' || it.startsWith('--arch=') }
                String archPlatform = null
                if (ai >= 0) {
                    def a = rest[ai]
                    archPlatform = a.contains('=') ? a.split('=', 2)[1] : (ai + 1 < rest.size() ? rest[ai + 1] : null)
                    rest = rest.take(ai) + rest.drop(ai + (a.contains('=') ? 1 : 2))
                    def platform = archPlatform?.startsWith('linux/') ? archPlatform : "linux/$archPlatform"
                    def suffix = archPlatform ? StackConfig.archSuffix(platform) : null
                    if (suffix == null) die("z up --arch: expected amd64 or arm64, got '${archPlatform ?: ''}'", 2)
                    // Services only: the whole stack on a foreign arch is a slower stack, never a
                    // fix, and a bare `z up --arch` is far likelier to be a slip than a plan.
                    if (!rest.any { !it.startsWith('-') })
                        die("z up --arch needs the service(s) to switch, e.g. z up jenkins --arch amd64", 2)
                    zfinUtil.childEnv['DOCKER_ARCH'] = suffix
                    zfinUtil.childEnv['DOCKER_DB_ARCH'] = suffix
                    // --no-deps: the override applies to every service in this compose run, so
                    // without it `up jenkins` would also recreate db on the foreign arch.
                    rest = ['--no-deps'] + rest
                }
                checkServices(rest)
                // A --shared-db stack keeps its app tier single-homed and reaches shared data
                // by connecting the shared db/solr into this feature's network -- so up must
                // create the network, connect them, THEN start (see ZfinUtil.connectSharedData).
                // Idempotent: on the normal stop/start cycle the network + connect persist, so
                // this just re-confirms them; it matters after a full `docker compose down`.
                def proj = zfinUtil.stackVar('COMPOSE_PROJECT_NAME')
                int upCode
                if (zfinUtil.stackVar('COMPOSE_FILE')?.contains('shared-db.yml') && proj) {
                    zfinUtil.runCommand(['docker', 'compose', 'up', '--no-start'] + rest)
                    zfinUtil.connectSharedData(proj)
                    upCode = zfinUtil.runCommand(['docker', 'compose', 'start'] + rest, [check: false])
                } else {
                    upCode = zfinUtil.runCommand(['docker', 'compose', 'up', '-d'] + rest, [check: false])
                }
                // Not recorded anywhere: the next `z up` that touches these services (a bare
                // `z up` included) sees an image that differs from the .env's and recreates them
                // on the native arch. Said once here so that is not a surprise.
                if (archPlatform && upCode == 0)
                    zfinUtil.info("note: --arch is for this run only -- a later `z up` without it recreates " +
                                  "${rest.findAll { !it.startsWith('-') }.join(' ')} on the stack's own arch " +
                                  "(z restart keeps it)")
                def gap = stackProxyNet() ? zfinUtil.proxyReachProblem(stackProxyNet()) : null
                if (gap) System.err.println("!! $gap")
                System.exit(upCode)
                break
            // stop vs down, matching compose's own meaning of the words: `stop` halts the
            // containers and keeps everything (this is what `z down` used to do, misleadingly);
            // `down` removes containers + network, and only discards this stack's ~26G db/solr
            // copy if you pass -v yourself. `z feature rm` is the guided full teardown.
            case 'stop':    requireStack(); checkServices(rest); compose(['stop'] + rest); break
            case 'down':    requireStack(); compose(['down'] + rest); break
            case 'pull':    requireStack(); checkServices(rest); compose(['pull'] + rest); break
            case 'log':     requireStack(); compose(['logs', '-f'] + rest); break
            case 'restart': requireStack(); checkServices(rest); compose(['restart'] + rest); break

            case 'status':
                // Default is the SHORT answer: where it is, what it is, and whether it serves.
                // Everything else -- ports, compose files, the container table -- is reference
                // material you look up occasionally, and printing it every time buried the six
                // lines anyone actually reads. `-v` for the rest; `--json` for all of it as data
                // (what `z web` reads). The facts and the text both live in StackStatus.
                def verbose = rest.any { it in ['-v', '--verbose'] }
                def asJson  = rest.contains('--json')
                def target  = StackStatus.target(zfinUtil)
                if (!target) {
                    println(asJson ? '{"stack": null}' : "stack: none here -- cd into a checkout or feature worktree")
                    return
                }
                def st = StackStatus.collect(zfinUtil, target)
                if (asJson) println StackStatus.json(st)
                else StackStatus.print(zfinUtil, st, verbose)
                break

            default: die("StackOps: unknown op '$op'", 2)
        }
    }
}
