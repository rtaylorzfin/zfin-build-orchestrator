import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// ZWeb -- `z web [--port N] [--no-open]`: a local page showing every stack at once.
//
// One card per stack (the main checkout and every feature worktree): what `z status -v` says,
// the branch against origin (with a Push button when a plain push would do), the PR, and --
// where a fleet crewmate works the ticket -- its stage, who it waits on, and its last report.
// Below them, every other worktree of the repo (release branches, scratch checkouts) with its
// branch and push state. Per stack: z up / z down / z freeze / z thaw, run as background jobs
// whose output the page shows live. The page refreshes itself; Ctrl-C stops the server.
//
// SAFETY. It binds 127.0.0.1 only, but that is not enough on its own: any web page in your
// browser can send requests to localhost, and a stack's containers can usually reach the
// host's loopback ports too (Docker Desktop). So every /api call needs a token minted at
// start-up and printed only here, the Host header must be this server's own (which defeats
// DNS rebinding), and actions must be JSON POSTs (which a cross-site form cannot send).
// Actions: `git fetch origin`; a PLAIN `git push origin <branch>` -- offered only when the
// branch is ahead of origin and not behind it, never for main or release-* branches, and
// re-checked on the server at push time; and the stack ops, each allowed only in a state
// where it makes sense (no freeze of main, no down/freeze while a crewmate is working: the
// sidecar is one of the stack's containers). Nothing here force-pushes, removes a worktree,
// or discards data (`z down` keeps volumes; freeze archives before it removes).
class ZWeb {
    static final int DEFAULT_PORT = 7480
    static final long PR_TTL_MS = 120_000

    ZfinUtil z
    String token
    int port
    File webDir
    Map prCache = new ConcurrentHashMap()      // branch -> [at: millis, pr: Map|null]
    def pool = newPool(8)
    Map jobs = new ConcurrentHashMap()         // worktree -> latest job Map (see action())

    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return
        z = zfinUtil
        port = DEFAULT_PORT
        boolean open = true
        for (int i = 0; i < args.size(); i++) {
            switch (args[i]) {
                case '--port': port = (args[++i] ?: '0') as int; break
                case '--no-open': open = false; break
                default: z.die("z web: unknown '${args[i]}' (--port N, --no-open)", 2)
            }
        }
        webDir = new File(z.COMPOSE.parentFile, 'lib/web')
        def bytes = new byte[18]; new SecureRandom().nextBytes(bytes)
        token = bytes.encodeHex().toString()

        def server
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
        } catch (BindException e) {
            z.die("z web: port $port is taken (another z web?) -- use --port N")
        }
        server.createContext('/', { HttpExchange ex -> handle(ex) } as HttpHandler)
        server.executor = newPool(4)
        server.start()
        String url = "http://127.0.0.1:$port/#t=$token"
        println "z web: $url"
        println "       (the #t= part is this run's key; Ctrl-C stops the server)"
        if (open && System.getProperty('os.name').toLowerCase().contains('mac'))
            new ProcessBuilder(['open', url]).start()
        // Fetch once at start so the push indicator compares against a current origin.
        pool.submit { fetchOrigin() }
        Thread.currentThread().join()
    }

    /** Daemon threads that carry this class's loader: groovy-json finds its FastStringService
     *  through the thread's context loader, and a plain pool thread's cannot see z's lib/. */
    private static newPool(int n) {
        def loader = ZWeb.classLoader
        Executors.newFixedThreadPool(n, { Runnable r ->
            def t = new Thread(r); t.daemon = true; t.contextClassLoader = loader; t
        } as java.util.concurrent.ThreadFactory)
    }

    // ---- HTTP -----------------------------------------------------------------------------

    private handle(HttpExchange ex) {
        try {
            def path = ex.requestURI.path
            def host = ex.requestHeaders.getFirst('Host') ?: ''
            if (!(host in ["127.0.0.1:$port".toString(), "localhost:$port".toString()]))
                return send(ex, 421, [error: 'wrong Host header'])
            // The page and its one library (htm + Preact, vendored: no CDN, works offline).
            def assets = ['/': ['index.html', 'text/html; charset=utf-8'],
                          '/htm-preact.mjs': ['htm-preact-standalone-3.1.1.mjs', 'text/javascript; charset=utf-8']]
            if (ex.requestMethod == 'GET' && assets[path]) {
                def b = new File(webDir, assets[path][0]).bytes
                ex.responseHeaders.add('Content-Type', assets[path][1])
                ex.responseHeaders.add('Cache-Control', 'no-store')
                ex.responseHeaders.add('Content-Security-Policy', "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'")
                ex.sendResponseHeaders(200, b.length); ex.responseBody.withStream { it.write(b) }
                return
            }
            if (path == '/favicon.ico') { ex.sendResponseHeaders(204, -1); return }
            if (!path.startsWith('/api/')) return send(ex, 404, [error: 'not found'])
            if (ex.requestHeaders.getFirst('X-Z-Token') != token) return send(ex, 403, [error: 'bad or missing token -- reopen the URL z web printed'])
            if (ex.requestMethod == 'POST') {
                if (!(ex.requestHeaders.getFirst('Content-Type') ?: '').startsWith('application/json'))
                    return send(ex, 415, [error: 'JSON only'])
                def origin = ex.requestHeaders.getFirst('Origin')
                if (origin && !(origin in ["http://127.0.0.1:$port".toString(), "http://localhost:$port".toString()]))
                    return send(ex, 403, [error: 'cross-origin'])
            }
            def body = ex.requestMethod == 'POST' ? (new JsonSlurper().parse(ex.requestBody) ?: [:]) : [:]
            switch ("${ex.requestMethod} $path".toString()) {
                case 'GET /api/stacks': return send(ex, 200, stacks())
                case 'POST /api/fetch': return send(ex, 200, fetchOrigin())
                case 'POST /api/push':  return send(ex, 200, push(body.path as String))
                case 'POST /api/action': return send(ex, 200, action(body.stack as String, body.op as String))
                default: return send(ex, 404, [error: 'not found'])
            }
        } catch (Throwable t) {
            System.err.println("z web: ${ex.requestMethod} ${ex.requestURI.path}: $t")
            try { send(ex, 500, [error: t.toString()]) } catch (ignored) {}
        } finally { ex.close() }
    }

    private void send(HttpExchange ex, int code, Object o) {
        def b = JsonOutput.toJson(o).getBytes('UTF-8')
        ex.responseHeaders.add('Content-Type', 'application/json')
        ex.responseHeaders.add('Cache-Control', 'no-store')
        ex.sendResponseHeaders(code, b.length); ex.responseBody.withStream { it.write(b) }
    }

    // ---- data -----------------------------------------------------------------------------

    /** The main checkout plus every feature worktree, each as StackStatus.collect() + git + fleet. */
    Map stacks() {
        def t0 = System.currentTimeMillis()
        def ps = StackStatus.snapshot(z)
        def proxies = z.runningProxies()
        def features = z.featureStacks()
        def wtRoot = new File(z.worktreesDir())
        def dirs = [new File(wtRoot, 'main')] + features.collect { new File(wtRoot, it.worktree) }
        def featureState = features.collectEntries { [it.worktree, it.state] }
        def futures = dirs.findAll { it.isDirectory() }.collect { File d ->
            pool.submit({
                def spec = z.stackSpec(d)
                if (!spec) return null
                def st = StackStatus.collect(z, StackStatus.target(z, spec), [ps: ps, proxies: proxies])
                st.worktree = d.name
                st.main = d.name == 'main'
                // Frozen/freezing is derived from the freeze archive, which collect() does not read.
                if (featureState[d.name] in ['frozen', 'freezing']) st.state = featureState[d.name]
                st.git = gitInfo(d, st.branch)
                st.pr = (st.main || !st.branch) ? null : prInfo(d, st.branch)
                st.fleet = fleetInfo(d)
                st.ops = allowedOps(st)
                st
            } as java.util.concurrent.Callable)
        }
        def list = futures.collect { it.get(60, TimeUnit.SECONDS) }.findAll { it }
        list.each { it.job = jobView(jobs[it.worktree]) }
        def stackDirs = list*.dir as Set
        def others = worktrees().findAll { !(it.path in stackDirs) }.collect { w ->
            pool.submit({
                def d = new File(w.path)
                w + [name: d.name, git: d.isDirectory() ? gitInfo(d, w.branch) : null]
            } as java.util.concurrent.Callable)
        }.collect { it.get(60, TimeUnit.SECONDS) }
        [stacks: list, worktrees: others, fetchedAt: lastFetch(), tookMs: System.currentTimeMillis() - t0,
         now: new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX")]
    }

    private String git(File d, List a) { z.captureOutput(['git', '-C', d.absolutePath] + a) ?: '' }

    /** HEAD, uncommitted changes, and where the branch stands against origin/<branch>. */
    Map gitInfo(File d, String branch) {
        def head = git(d, ['log', '-1', '--format=%h%x09%s%x09%cr'])?.split('\t', 3) as List
        // Untracked sidecar/playwright scratch is not work to lose (same rule as fleet check).
        def dirty = git(d, ['status', '--porcelain']).readLines()
                .findAll { it && !it.startsWith('?? .sidecar/') && !it.startsWith('?? .playwright-mcp/') }
        def g = [sha: head?.getAt(0) ?: '', subject: head?.getAt(1) ?: '', when: head?.getAt(2) ?: '',
                 dirty: dirty.size(), dirtyFiles: dirty.take(15)]
        if (!branch || branch == 'HEAD') return g + [push: 'detached']
        def remote = "refs/remotes/origin/$branch".toString()
        if (!git(d, ['rev-parse', '--verify', '-q', remote])) {
            g.push = 'unpushed'
            g.ahead = (git(d, ['rev-list', '--count', branch, '--not', '--remotes']) ?: '0') as int
        } else {
            def lr = git(d, ['rev-list', '--left-right', '--count', "$remote...$branch".toString()]).split(/\s+/)
            g.behind = lr[0] as int; g.ahead = lr[1] as int
            g.push = g.ahead && g.behind ? 'diverged' : g.ahead ? 'ahead' : g.behind ? 'behind' : 'same'
        }
        g.canPush = pushAllowed(branch) && (g.push == 'ahead' || (g.push == 'unpushed' && g.ahead > 0))
        g
    }

    /** Every worktree of the repo (`git worktree list`): path, branch, and git's own flags. */
    List<Map> worktrees() {
        def out = git(repoDir(), ['worktree', 'list', '--porcelain'])
        out.split(/\n\n/).collect { blk ->
            def w = [:]
            blk.readLines().each { l ->
                def (k, v) = (l.split(' ', 2) as List) + ['']
                switch (k) {
                    case 'worktree': w.path = v; break
                    case 'branch':   w.branch = v - 'refs/heads/'; break
                    case 'detached': w.branch = 'HEAD'; break
                    case 'bare':     w.bare = true; break
                    case 'locked':   w.locked = v ?: 'locked'; break
                    case 'prunable': w.prunable = v ?: 'prunable'; break
                }
            }
            w
        }.findAll { it.path && !it.bare }
    }

    static boolean pushAllowed(String branch) {
        branch && branch != 'HEAD' && !(branch in ['main', 'master']) && !branch.startsWith('release')
    }

    /** The PR for this branch on the canonical repo (the `canon` remote, else origin), cached. */
    Map prInfo(File d, String branch) {
        def hit = prCache[branch]
        if (hit && System.currentTimeMillis() - hit.at < PR_TTL_MS) return hit.pr
        def url = git(d, ['remote', 'get-url', 'canon']) ?: git(d, ['remote', 'get-url', 'origin'])
        def m = url =~ /github\.com[:\/](.+?)(?:\.git)?\/?$/
        Map pr = null
        if (m) {
            def out = z.captureOutput(['gh', 'pr', 'list', '-R', m[0][1], '--head', branch, '--state', 'all', '--limit', '1',
                                       '--json', 'number,state,isDraft,url,title,headRefOid,body,statusCheckRollup'])
            def arr = out ? new JsonSlurper().parseText(out) : []
            if (arr) {
                def p = arr[0]
                def checks = (p.statusCheckRollup ?: []).collect { it.conclusion ?: it.state ?: it.status }
                pr = [number: p.number, state: p.isDraft && p.state == 'OPEN' ? 'DRAFT' : p.state, url: p.url, title: p.title,
                      headSha: (p.headRefOid ?: '').take(10), emptyBody: !(p.body ?: '').trim(),
                      checks: !checks ? 'none' : checks.any { it in ['FAILURE', 'ERROR', 'CANCELLED', 'TIMED_OUT'] } ? 'fail'
                              : checks.every { it in ['SUCCESS', 'NEUTRAL', 'SKIPPED'] } ? 'ok' : 'pending']
            }
        }
        prCache[branch] = [at: System.currentTimeMillis(), pr: pr]
        pr
    }

    /** A fleet crewmate's view of the ticket: state.md, herdr's agent status, the last report. */
    Map fleetInfo(File d) {
        def sc = new File(d, '.sidecar')
        def stateF = new File(sc, 'state.md')
        if (!stateF.isFile()) return null
        def state = [:]
        stateF.readLines().each { l -> def m = l =~ /^([a-z][a-z -]*?):\s*(.*)$/; if (m) state[m[0][1]] = m[0][2] }
        def crew = (state.crewmate ?: '').tokenize(' ')[0] ?: ''
        def agent = 'none'
        if (crew) {
            def out = z.captureOutput(['herdr', 'agent', 'get', crew])
            try { agent = new JsonSlurper().parseText(out).result.agent.agent_status } catch (ignored) {}
        }
        def nums = { String box -> ((new File(sc, box).listFiles() ?: []) as List).collect { (it.name =~ /^(\d+)\.md$/) }
                                     .findAll { it }.collect { it[0][1] as int }.sort() }
        def inbox = nums('inbox'), outbox = nums('outbox')
        def last = outbox ? new File(sc, String.format('outbox/%03d.md', outbox[-1])) : null
        def section = { String text, String head ->
            def m = text =~ /(?ms)^## ${head}\s*\n(.*?)(?=^## |\z)/
            m ? m[0][1].trim().readLines().take(14).join('\n') : ''
        }
        def text = last?.isFile() ? last.text : ''
        [stage: state.stage ?: '', waitingOn: state['waiting-on'] ?: '', openDecisions: state['open decisions'] ?: '',
         crewmate: crew, agent: agent, last: state.last ?: '',
         inbox: inbox ? inbox[-1] : 0, outbox: outbox ? outbox[-1] : 0,
         lastReport: last?.name ?: '', summary: section(text, 'Summary'), needsHost: section(text, 'Needs from host'),
         deliverables: ((new File(sc, 'deliverables').listFiles() ?: []) as List).findAll { it.isFile() }*.name.sort()]
    }

    // ---- actions --------------------------------------------------------------------------

    private File repoDir() { new File(z.worktreesDir(), 'main') }

    private String lastFetch() {
        def common = git(repoDir(), ['rev-parse', '--path-format=absolute', '--git-common-dir'])
        def f = common ? new File(common, 'FETCH_HEAD') : null
        f?.isFile() ? new Date(f.lastModified()).format("yyyy-MM-dd'T'HH:mm:ssXXX") : ''
    }

    /** `git fetch origin` once for the shared repo -- every worktree sees the result. */
    synchronized Map fetchOrigin() {
        def r = runGit(repoDir(), ['fetch', 'origin'])
        println "z web: git fetch origin -> exit ${r.exit}"
        r + [fetchedAt: lastFetch()]
    }

    /** A plain `git push origin <branch>` for one of the repo's worktrees (named by path, and
     *  only a path `git worktree list` gives), after re-checking on the server that a plain
     *  push is what it needs. */
    synchronized Map push(String path) {
        def w = path ? worktrees().find { it.path == path } : null
        if (!w) return [ok: false, output: "not a worktree of this repo: $path"]
        def d = new File(w.path)
        def branch = git(d, ['rev-parse', '--abbrev-ref', 'HEAD'])
        def g = gitInfo(d, branch)
        if (!g.canPush) return [ok: false, output: "not pushing $branch: origin is ${g.push}" +
                                                   (pushAllowed(branch) ? '' : ' (main/release branches are never pushed from here)')]
        println "z web: git push origin $branch  (${d.name}, ${g.ahead} commit(s))"
        def r = runGit(d, ['push', 'origin', branch])
        println r.output.readLines().collect { "       $it" }.join('\n')
        prCache.remove(branch)
        r + [ok: r.exit == 0, git: gitInfo(d, branch)]
    }

    // ---- stack ops as background jobs ---------------------------------------------------

    static final Map OPS = [
        up    : [label: 'z up',     cmd: { String wt -> ['up'] }],
        down  : [label: 'z down',   cmd: { String wt -> ['down'] }],
        freeze: [label: 'z freeze', cmd: { String wt -> ['feature', 'freeze', wt] }],
        thaw  : [label: 'z thaw',   cmd: { String wt -> ['feature', 'thaw', wt] }],
    ]

    /** Which ops make sense for a stack right now; the page shows these and action() re-checks. */
    static List<String> allowedOps(Map st) {
        def ops = []
        def crewBusy = st.fleet?.agent == 'working'
        switch (st.state) {
            case 'up': case 'partial':
                ops << 'up'                                   // starts whatever is stopped
                if (!crewBusy) ops << 'down'
                if (!crewBusy && !st.main) ops << 'freeze'
                break
            case 'down':
                ops << 'up'
                if (!st.main) ops << 'freeze'
                break
            case 'frozen': case 'freezing':
                ops << 'thaw'
                if (st.state == 'freezing') ops << 'freeze'  // finishes a freeze that stopped part-way
                break
        }
        ops
    }

    /** Start `z <op>` for one stack in the background, in that stack's worktree, so the stack
     *  z acts on is the one the cwd names -- exactly as when you type it there. */
    synchronized Map action(String worktree, String op) {
        if (!(op in OPS.keySet())) return [ok: false, output: "unknown action $op"]
        if (!worktree || !(worktree ==~ /[A-Za-z0-9._-]+/)) return [ok: false, output: 'no such stack']
        def d = new File(z.worktreesDir(), worktree)
        def spec = d.isDirectory() ? z.stackSpec(d) : null
        if (!spec) return [ok: false, output: "no stack $worktree"]
        if (jobs[worktree]?.running) return [ok: false, output: "${jobs[worktree].label} is still running on $worktree"]
        // Re-derive the state the page saw; it may be seconds old.
        def st = StackStatus.collect(z, StackStatus.target(z, spec), [proxies: [:]])
        st.main = worktree == 'main'
        def fs = z.featureStacks().find { it.worktree == worktree }
        if (fs?.state in ['frozen', 'freezing']) st.state = fs.state
        st.fleet = fleetInfo(d)
        if (!(op in allowedOps(st)))
            return [ok: false, output: "${OPS[op].label} is not offered for $worktree while it is ${st.state}" +
                                       (st.fleet?.agent == 'working' ? ' and its crewmate is working' : '')]
        def zScript = new File(z.COMPOSE.parentFile, 'z').absolutePath
        def argv = [zScript] + OPS[op].cmd(worktree)
        def job = [id: UUID.randomUUID().toString().take(8), worktree: worktree, op: op, label: OPS[op].label,
                   cmd: "cd ${d.name} && z ${argv.drop(1).join(' ')}".toString(), started: System.currentTimeMillis(),
                   running: true, exit: null, lines: Collections.synchronizedList([])]
        jobs[worktree] = job
        println "z web: ${job.cmd}"
        pool.submit {
            try {
                def pb = new ProcessBuilder(argv*.toString()).directory(d).redirectErrorStream(true)
                // An activated stack in the shell that started z web must not redirect this one.
                ['COMPOSE_FILE', 'COMPOSE_PROJECT_NAME', 'COMPOSE_ENV_FILES', 'ZFIN_STACK_PROJECT', 'ZFIN_STACK_DIR',
                 'ZFIN_STACK_HOST', 'ZFIN_STACK_URL', 'ZFIN_STACK_DIRECT', 'ZFIN_SEED'].each { pb.environment().remove(it) }
                def p = pb.start()
                p.outputStream.close()                       // no TTY, no stdin: nothing can prompt
                p.inputStream.eachLine('UTF-8') { l ->
                    job.lines << l
                    if (job.lines.size() > 400) job.lines.remove(0)
                }
                job.exit = p.waitFor()
            } catch (Throwable t) {
                job.lines << t.toString(); job.exit = -1
            } finally {
                job.running = false; job.ended = System.currentTimeMillis()
                println "z web: ${job.cmd} -> exit ${job.exit}"
            }
        }
        [ok: true, job: jobView(job)]
    }

    static Map jobView(Map j) {
        if (!j) return null
        [id: j.id, op: j.op, label: j.label, cmd: j.cmd, running: j.running, exit: j.exit,
         started: j.started, ended: j.ended, tail: (j.lines as List).takeRight(60).join('\n')]
    }

    /** git with stderr merged (push/fetch report there), no prompts, and a time limit. */
    private Map runGit(File d, List a) {
        def pb = new ProcessBuilder((['git', '-C', d.absolutePath] + a)*.toString()).redirectErrorStream(true)
        pb.environment().put('GIT_TERMINAL_PROMPT', '0')
        def p = pb.start()
        def out = new StringBuilder()
        def reader = Thread.start { out << p.inputStream.text }
        if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); return [exit: -1, output: 'timed out after 120s'] }
        reader.join(5000)
        [exit: p.exitValue(), output: out.toString().trim()]
    }
}
