import groovy.json.JsonOutput

// StackStatus -- what `z status` knows about a stack, as DATA, and the text view of it.
//
// `z status` used to print as it went, reading the stack from the environment z sets up for
// the cwd (resolveStack -> childEnv). That answered "this stack" and nothing else. Splitting
// collect from print lets the same facts feed `z status --json` and `z web`, which shows
// every stack at once:
//
//   target(z)            the stack the cwd (or an activated environment) names
//   target(z, spec)      any stack, from ZfinUtil.stackSpec(<worktree>) -- no cwd needed
//   collect(z, t, opts)  one stack's facts as a Map (JSON-safe: strings, lists, maps)
//   print(z, st, verbose)  the familiar text, unchanged
//
// collect() makes a few docker calls per stack. A caller looking at many stacks passes
// opts.ps (snapshot()) and opts.proxies (runningProxies()) so those happen once in total.
class StackStatus {

    /** The stack this invocation targets: the cwd's (resolveStack) or an activated one. */
    static Map target(ZfinUtil z) {
        def project = z.stackVar('ZFIN_STACK_PROJECT')
        if (!project) return null
        [project       : project,
         composeProject: z.stackVar('COMPOSE_PROJECT_NAME') ?: project,
         dir           : z.stackVar('ZFIN_STACK_DIR') ?: '',
         envFile       : z.stackVar('COMPOSE_ENV_FILES') ?: '',
         compose       : z.stackVar('COMPOSE_FILE') ?: '',
         url           : z.stackVar('ZFIN_STACK_URL') ?: '',
         direct        : z.stackVar('ZFIN_STACK_DIRECT') ?: '',
         seed          : z.stackVar('ZFIN_SEED') ?: '']
    }

    /** Any stack, from its stackSpec -- the same values resolveStack would put in childEnv. */
    static Map target(ZfinUtil z, Map spec) {
        if (!spec) return null
        [project       : spec.project,
         composeProject: spec.project,
         dir           : spec.dir ?: '',
         envFile       : spec.envFile ?: '',
         compose       : spec.compose ?: '',
         url           : spec.urls?.primary ?: '',
         direct        : (spec.urls?.vhost && spec.urls?.direct) ? spec.urls.direct : '',
         seed          : spec.tag ?: '']
    }

    /** Every container on the host, once: project, service, name, status, image. For callers
     *  that collect() many stacks -- filtering this beats a `docker ps` per stack. */
    static List<Map> snapshot(ZfinUtil z) {
        (z.captureOutput(['docker', 'ps', '-a', '--format',
                '{{.Label "com.docker.compose.project"}}\t{{.Label "com.docker.compose.service"}}\t{{.Names}}\t{{.Status}}\t{{.Image}}']) ?: '')
                .readLines().collect { it.split('\t', 5) as List }.findAll { it.size() == 5 }
                .collect { [project: it[0], svc: it[1], name: it[2], status: it[3], image: it[4]] }
    }

    static Map collect(ZfinUtil z, Map t, Map opts = [:]) {
        def envF = t.envFile ? new File(t.envFile) : null
        def readEnv = { String key -> z.envField(envF, key) }
        def st = [stack: t.project, composeProject: t.composeProject, dir: t.dir, url: t.url, direct: t.direct, seed: t.seed,
                  compose: t.compose, envFile: t.envFile]

        st.desc = ZfinUtil.envUnquote(readEnv(StackConfig.DESC_KEY))
        st.offset = readEnv('ZFIN_PORT_OFFSET') ?: ''
        st.ports = [['https', 'DOCKER_HTTPD_HTTPS_PORT'], ['db', 'DOCKER_DB_PORT'],
                    ['debug', 'DOCKER_TOMCATDEBUG_PORT'], ['jenkins', 'DOCKER_JENKINS_HTTP_PORT']]
                .collectEntries { n, k -> [n, readEnv(k) ?: ''] }.findAll { it.value }

        // Branch: the checked-out branch of this stack's worktree (empty if dir isn't a git
        // worktree). The Jira key comes from the stack's name, which is the ticket for a
        // feature, else from the branch; a branch is often the key plus a suffix
        // (zfin-10510-v2), so only the key part is taken.
        st.branch = t.dir ? (z.captureOutput(['git', '-C', t.dir, 'rev-parse', '--abbrev-ref', 'HEAD']) ?: '') : ''
        def keyOf = { String s -> def m = s =~ /(?i)^([a-z]+-\d+)(?!\d)/; m ? m[0][1].toUpperCase() : null }
        st.ticket = keyOf(t.project) ?: (st.branch ? keyOf(st.branch) : null)
        st.jira = st.ticket ? "https://zfin.atlassian.net/browse/${st.ticket}".toString() : ''
        // PR "create" link: the GitHub owner/repo slug from origin (SSH or HTTPS form) and the
        // open-a-PR page for this branch. Empty for non-GitHub origins.
        def origin = t.dir ? z.captureOutput(['git', '-C', t.dir, 'remote', 'get-url', 'origin']) : ''
        def ghMatch = origin =~ /github\.com[:\/](.+?)(?:\.git)?\/?$/
        def ghSlug = ghMatch ? ghMatch[0][1] : ''
        st.prNew = (st.branch && ghSlug) ? "https://github.com/$ghSlug/pull/new/${st.branch}".toString() : ''

        // Own or shared data: it decides whether a write, a migration or a reindex here
        // touches only this stack or every --shared-db feature too.
        st.data = t.compose.contains('shared-db.yml') ? 'shared' : 'own'
        st.sharedProject = st.data == 'shared' ? z.sharedProject() : ''

        // The proxy network this stack's httpd joins (recorded in its .env by z feature new),
        // only when the overlay that does the joining is in its compose files.
        st.proxyNet = t.compose.contains('overlay-proxy-network') ? (readEnv('ZFIN_PROXY_NETWORK') ?: '') : ''
        if (st.proxyNet) {
            def proxies = opts.containsKey('proxies') ? opts.proxies : z.runningProxies()
            st.proxies = proxies.findAll { n, nets -> st.proxyNet in nets }.keySet() as List
        }

        // Containers: every one with the project label, `z run` sidecars included (they carry
        // the service label too, which is why `claude` shows under running).
        def all = opts.containsKey('ps') ? opts.ps : snapshot(z)
        def mine = all.findAll { it.project == t.project && it.svc }
        st.containers = mine.collect { [svc: it.svc, name: it.name, status: it.status, image: it.image] }
        def up     = mine.findAll { it.status.startsWith('Up') && !it.status.contains('unhealthy') }
        def sick   = mine.findAll { it.status.contains('unhealthy') }
        // A NON-ZERO exit is the one worth flagging. tomcat and httpd exiting 1 -- missing
        // server.xml, unreadable Apache include -- looked identical to `base` and `blast`
        // exiting 0, which they do by design every single `up`.
        def failed = mine.findAll { it.status =~ /Exited \((?!0\))/ }
        def idle   = mine.findAll { !it.status.startsWith('Up') } - failed
        st.running   = up*.svc.sort()
        st.unhealthy = sick*.svc.sort()
        st.failed    = failed.sort { it.svc }.collect { [svc: it.svc, status: it.status] }
        st.stopped   = idle*.svc.sort()
        st.state = st.running.contains(StackConfig.WEB_SERVICE) ? 'up' : (up || sick) ? 'partial' : 'down'

        // The Jenkins login, read straight from the running container. No file -- a password
        // from JENKINS_ADMIN_PASSWORD, or OIDC in charge -- means none. "default": the file is
        // what the init script generated, and stays put if someone changes it in Jenkins later.
        def jc = (up + sick).find { it.svc == 'jenkins' }
        st.jenkinsPassword = jc ? (z.captureOutput(['docker', 'exec', jc.name, 'cat', StackConfig.JENKINS_ADMIN_PASSWORD_FILE]) ?: '') : ''

        // A shared-data stack has no db/solr of its own -- the overlay suppresses them. One
        // showing up means something ran compose WITHOUT the overlays, and it joins this
        // network under the same name as the shared container, so `db` resolves to either.
        st.strays = st.data == 'shared' ? (up + sick).findAll { it.svc in StackConfig.DATA_SERVICES }*.svc.sort() : []

        // `z run <svc>` one-off containers (`<project>-<svc>-run-<hash>`), running only. The
        // sidecar is the one that matters: it is long-lived, holds the worktree read-write,
        // and is why a stack can look idle while an agent works in it.
        st.runners = all.findAll { it.project == t.composeProject && it.name.contains('-run-') && it.status.startsWith('Up') }
                        .collect { [name: it.name, status: it.status, image: it.image] }
        // A --shared-db stack's data tier lives in the separate shared project.
        st.sharedContainers = st.sharedProject ? all.findAll { it.project == st.sharedProject }
                        .collect { [name: it.name, status: it.status, image: it.image] } : []
        st
    }

    static String json(Map st) { JsonOutput.prettyPrint(JsonOutput.toJson(st)) }

    /** The text `z status [-v]` has always printed. Order is deliberate: URL first (the line
     *  you came for), then which stack this is, then whether it serves. The two -v tables
     *  stay live docker output, so they read exactly as `docker compose ps` does. */
    static void print(ZfinUtil z, Map st, boolean verbose) {
        def line = { String k, v -> println "  ${k.padRight(9)}: $v" }
        println "stack: ${st.stack}"
        if (st.desc)   line('desc', st.desc)
        if (st.url)    line('url', st.url)
        if (st.direct) line('direct', st.direct)
        if (st.dir)    line('dir', st.dir)
        if (st.branch) line('branch', st.branch)
        if (verbose && st.offset) line('ports', st.ports.collect { n, p -> "$n $p" }.join('   '))
        if (st.jira)   line('jira', st.jira)
        if (st.prNew)  line('pr', st.prNew)
        if (st.seed)   line('seed', st.seed)
        line('data', st.data == 'shared' ? "shared (${st.sharedProject} -- writes reach every --shared-db stack)"
                                         : "own (this stack's db+solr)")
        if (st.proxyNet)
            line('proxy', st.proxies ? "${st.proxies.join(', ')} on ${st.proxyNet}" : "!! nothing on ${st.proxyNet} -- z up says why")
        if (verbose) line('compose', st.compose ?: '<none>')
        if (verbose) line('env-file', st.envFile ?: '<none>')
        line('running', st.running ? st.running.join(' ') : '(none)')
        // UNHEALTHY and FAILED print only when they apply: free on a healthy stack, the whole
        // point on a sick one.
        if (st.unhealthy) line('UNHEALTHY', st.unhealthy.join(' '))
        if (st.failed)    line('FAILED', st.failed.collect { "${it.svc} (${it.status})" }.join(', '))
        if (st.jenkinsPassword) line('jenkins', "default login admin / ${st.jenkinsPassword}")
        if (st.strays) line('STRAY', "own ${st.strays.join('+')} on a shared-data stack -- ${st.strays.collect { "`$it`" }.join('/')} " +
                "resolve${st.strays.size() == 1 ? 's' : ''} to it OR the shared one at random. Started without the overlays; " +
                "remove: docker rm -f ${st.strays.collect { "${st.stack}-${it}-1" }.join(' ')}")
        // `stopped` is noise by default -- base and blast exit 0 on every up by design.
        if (verbose && st.stopped) line('stopped', st.stopped.join(' '))
        if (!verbose) { println ""; return }

        println "\ncontainers:"
        z.runCommand(['docker', 'compose', 'ps'], [check: false])
        if (st.runners) {
            println "\none-off containers (z run):"
            st.runners.each { println "  ${it.name}   ${it.status}   ${it.image}" }
        }
        if (st.sharedProject) {
            println "\nshared data (project ${st.sharedProject}, connected into ${st.composeProject}_default):"
            new ProcessBuilder(['docker', 'ps', '-a',
                '--filter', "label=com.docker.compose.project=${st.sharedProject}".toString(),
                '--format', 'table {{.Names}}\t{{.Status}}\t{{.Image}}']).inheritIO().start().waitFor()
        }
    }
}
