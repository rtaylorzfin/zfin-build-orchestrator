// ZfinUtil -- shared helpers, canonical roots, and the volume capture/restore mechanics for the
// dev-stack command classes in lib/. The single front door `z`
// loads ZfinUtil AND every command class through ONE GroovyClassLoader (with lib/ on its
// classpath, so `ZfinUtil` resolves to a single Class everywhere), builds one instance, and
// calls `cmd.run(args, zfinUtil)` in-process. So command classes get the helpers + roots as
// a typed parameter -- no self-location, no env plumbing, one JVM:
//
//   class NewFeature {
//     def run(List args, ZfinUtil zfinUtil) {
//       def die = zfinUtil.&die; def runCommand = zfinUtil.&runCommand; def DOCKER = zfinUtil.DOCKER
//       ...
//     }
//   }
//
// The helpers live here rather than per-command so their semantics cannot drift:
// runCommand(List, [check:false]) honors check, and childEnv injects extra process env
// (zbuild sets it to default COMPOSE_FILE).
class ZfinUtil {
    // Two kinds of root, kept apart on purpose. The TOOL's own files sit beside `z`, wherever
    // this checkout of the orchestrator lives: lib/ and compose/ (the overlays and the sidecar's
    // build context). The ZFIN checkout it drives is a separate tree, found per run (REPO
    // below), so one install serves every ZFIN checkout and worktree on the host.
    final File HOME, LIB, COMPOSE
    ZfinUtil(File home) {
        HOME    = home.canonicalFile
        LIB     = new File(HOME, 'lib')
        COMPOSE = new File(HOME, 'compose')
        // Overlays name files under compose/ (the sidecar's build context) by this variable,
        // because compose resolves relative paths against the BASE file's directory.
        childEnv['ZFIN_COMPOSE_DIR'] = COMPOSE.absolutePath
    }

    /** docker-compose.overlay-worktree.yml when `dir` is a git worktree (its .git is a FILE),
     *  else nothing. Derived from the tree rather than recorded in its .env: whether git in a
     *  container needs the main repo mounted is a fact about the tree, not a choice. */
    List<String> worktreeOverlay(File dir) {
        new File(dir, '.git').isFile() ? ['docker-compose.overlay-worktree.yml'] : []
    }

    private File repoCache = null
    /** The ZFIN checkout this run targets: $ZFIN_REPO if set, otherwise the MAIN checkout of
     *  the git repo the working directory is in. The main checkout rather than the worktree you
     *  stand in, because a feature stack's base compose file and base .env are the ones in the
     *  checkout that owns its worktree (see stackSpec).
     *
     *  Resolved on first use, not in the constructor, so the commands that need no checkout
     *  (help, scaffold, shell-init) work from anywhere. */
    File getREPO() {
        if (repoCache) return repoCache
        def explicit = System.getenv('ZFIN_REPO')
        File r = null
        if (explicit) {
            r = new File(explicit.replaceFirst('^~', System.getProperty('user.home')))
        } else {
            // --path-format=absolute: in the main checkout, --git-common-dir is otherwise the
            // bare relative ".git".
            def common = captureOutput(['git', 'rev-parse', '--path-format=absolute', '--git-common-dir'])
            if (common) r = new File(common).parentFile
        }
        if (!r || !new File(r, 'docker/docker-compose.yml').isFile())
            die(explicit ? "ZFIN_REPO=$explicit is not a ZFIN checkout (no docker/docker-compose.yml there)"
                         : "not inside a ZFIN checkout -- cd into one (or a feature worktree), or set ZFIN_REPO")
        checkComposeVersion(r.canonicalFile)
        repoCache = r.canonicalFile
    }

    /** The version of a ZFIN checkout's compose file, as the interface this tooling depends on:
     *  the top-level `x-zfin-compose-version: N` in its docker/docker-compose.yml, or null when
     *  it declares none.
     *  Read as a line rather than parsed as YAML: it is one key, and the tool has no YAML
     *  dependency to spend on it. */
    Integer composeVersion(File repo) {
        for (line in new File(repo, 'docker/docker-compose.yml').readLines()) {
            def m = line =~ /^x-zfin-compose-version:\s*(\d+)\s*(#.*)?$/
            if (m.matches()) return m.group(1) as Integer
        }
        null
    }

    /** Refuse a checkout whose compose version this tooling does not know. The tooling relies
     *  on variables and services that live in the ZFIN repo, which changes on its own schedule;
     *  without this, a mismatch surfaces as a compose error or a stack that starts subtly
     *  wrong. ZFIN_SKIP_COMPOSE_VERSION_CHECK=1 proceeds anyway, with a warning. */
    void checkComposeVersion(File repo) {
        def v = composeVersion(repo)
        if (v in StackConfig.COMPOSE_VERSIONS_SUPPORTED) return
        def problem = v == null
            ? "${repo} declares no x-zfin-compose-version in docker/docker-compose.yml,\n" +
              "   so it predates the hooks this tooling relies on. Update that checkout to a ZFIN main that has them."
            : (v > StackConfig.COMPOSE_VERSIONS_SUPPORTED.max()
                ? "${repo} declares compose version ${v}; this tooling supports " +
                  "${StackConfig.COMPOSE_VERSIONS_SUPPORTED.join(', ')}.\n   Update the tooling:  git -C ${HOME} pull"
                : "${repo} declares compose version ${v}, which this tooling no longer supports " +
                  "(${StackConfig.COMPOSE_VERSIONS_SUPPORTED.join(', ')}).\n   Update that checkout, or use an older checkout of the tooling.")
        if (System.getenv('ZFIN_SKIP_COMPOSE_VERSION_CHECK') == '1') {
            System.err.println("!! ${problem}\n   ZFIN_SKIP_COMPOSE_VERSION_CHECK=1: continuing anyway.")
            return
        }
        die(problem + "\n   (ZFIN_SKIP_COMPOSE_VERSION_CHECK=1 proceeds anyway, at your own risk.)")
    }
    /** The ZFIN checkout's docker/ directory: the base compose file and base .env. */
    File getDOCKER() { new File(getREPO(), 'docker') }

    // Warm-volume contract + service roles + image names now live in StackConfig (policy).

    // Extra env injected into every spawned process (zbuild uses this to default COMPOSE_FILE;
    // resolveStack uses it to target an un-activated stack).
    Map<String, String> childEnv = [:]

    /** Where a stack-targeting var comes from: an ADOPTED stack (childEnv, see resolveStack)
     *  or the ambient environment. Read these instead of System.getenv so a
     *  command behaves the same whether the stack was activated or auto-detected. */
    String stackVar(String key) { childEnv[key] ?: System.getenv(key) }

    /** A stack's identity, derived from its own docker/.env plus the ORIGIN checkout's compose
     *  files. Returns null for a directory that is not a provisioned stack.
     *
     *  Deliberately NOT a per-worktree copy of the tooling and compose files. A copy would
     *  survive the main checkout switching branches, but it goes stale silently, and a stale
     *  copy surfaces as "no such service", or a usage error for a flag that exists, rather
     *  than as "your copy is old". One source of truth is worth more than that protection.
     *
     *  The per-feature .env is the record: project, host, image tags, and which overlays the
     *  stack was built with, so its composition is knowable from the stack itself. */
    Map stackSpec(File dir) {
        if (!dir) return null
        def envF = new File(dir, 'docker/.env')
        if (!envF.isFile()) return null
        def project = envField(envF, 'COMPOSE_PROJECT_NAME')
        if (!project) return null
        // A stack that DECLARES the key gets exactly what it declares -- INCLUDING NOTHING.
        // That case is the main checkout's own base stack: it runs the stock zfin-db/zfin-solr
        // images and loads from unloads, and must not silently inherit a feature's overlays.
        //
        // An ABSENT key gets the default for the kind of tree (see below), so a stack whose
        // .env never recorded the key still tears down correctly. envField() cannot tell absent from empty -- both
        // are '' -- so ask the file whether the key is there at all.
        def declared = envF.readLines().any { it.startsWith('ZFIN_COMPOSE_OVERLAYS=') }
        // An ABSENT key means a feature stack made before the key was recorded -- but only for
        // a feature worktree. A checkout's own stack (the base checkout, an instance) gets base
        // compose alone: giving it the feature overlay is how `z up` in the base checkout ended
        // up joining a proxy network, or dropping services an instance wants.
        def overlays = (declared
                ? envField(envF, 'ZFIN_COMPOSE_OVERLAYS').tokenize(':').findAll { it }
                : (isFeatureTree(dir) ? ['docker-compose.overlay-feature.yml'] : [])) + worktreeOverlay(dir)
        // Name the culprit: compose's own error for a missing -f does not say which file
        // records it, and every stack op would hit it.
        def missing = overlays.findAll { !new File(COMPOSE, it).isFile() }
        if (missing)
            System.err.println("!! ${project}: docker/.env (ZFIN_COMPOSE_OVERLAYS) names overlay(s) " +
                               "not in ${COMPOSE}: ${missing.join(', ')}")
        def files = ([new File(DOCKER, 'docker-compose.yml')] +
                     overlays.collect { new File(COMPOSE, it) })*.absolutePath
        [project : project,
         dir     : dir.absolutePath,
         envFile : envF.absolutePath,
         compose : files.join(':'),
         overlays: overlays,
         host    : envField(envF, 'DOCKER_VIRTUAL_HOST'),
         urls    : stackUrls(envF),
         tag     : envField(envF, 'ZFIN_SEED'),
         data    : overlays.any { it.contains('shared-db') } ? 'shared' : 'own']
    }

    /** Is `dir` a linked git worktree -- its .git a FILE pointing into the main repo? The main
     *  checkout's .git is a directory. This is what tells a feature from the main checkout,
     *  which sits beside the features under worktrees/ (as worktrees/main) but is not one. */
    static boolean isLinkedWorktree(File dir) { new File(dir, '.git').isFile() }

    /** Is `dir` a feature worktree (rather than a checkout's own stack)? A linked worktree
     *  directly under the worktrees dir. Never dies: this runs for `z help` and `z status` on
     *  a host with no dev tree yet. */
    boolean isFeatureTree(File dir) {
        if (!isLinkedWorktree(dir)) return false
        def root = setting('ZFIN_WORKTREES_DIR') ?: (setting('ZFIN_DEV_ROOT') ? "${setting('ZFIN_DEV_ROOT')}/worktrees" : null)
        if (!root) return false
        def wts = new File(root.replaceFirst('^~', System.getProperty('user.home'))).canonicalFile
        dir.canonicalFile.parentFile == wts
    }

    /** Point this invocation at the stack whose tree the cwd is in. Compose reads
     *  COMPOSE_PROJECT_NAME / COMPOSE_FILE / COMPOSE_ENV_FILES natively, so setting them is
     *  all "targeting a stack" means. */
    void resolveStack(File cwd) {
        def here = cwd?.canonicalFile
        if (!here) return
        def top = captureOutput(['git', '-C', here.absolutePath, 'rev-parse', '--show-toplevel'])
        if (!top) return
        def spec = stackSpec(new File(top))
        if (!spec) return
        if (System.getenv('COMPOSE_FILE')) return     // an explicit environment wins
        childEnv['COMPOSE_PROJECT_NAME'] = spec.project
        childEnv['COMPOSE_FILE'] = spec.compose
        childEnv['COMPOSE_ENV_FILES'] = spec.envFile
        childEnv['ZFIN_STACK_PROJECT'] = spec.project   // what `z status` reports
        childEnv['ZFIN_STACK_DIR'] = spec.dir
        childEnv['ZFIN_STACK_HOST'] = spec.host ?: ''
        childEnv['ZFIN_STACK_URL'] = spec.urls?.primary ?: ''
        childEnv['ZFIN_STACK_DIRECT'] = (spec.urls?.vhost && spec.urls?.direct) ? spec.urls.direct : ''
        if (spec.tag) childEnv['ZFIN_SEED'] = spec.tag
        System.err.println(">> targeting '${spec.project}' (${new File(spec.dir).name})")
    }

    void die(String m, int code = 1) { System.err.println("!! $m"); System.exit(code) }
    void info(String m) { println(">> $m") }

    private ProcessBuilder newProcess(List cmd) {
        def p = new ProcessBuilder(cmd*.toString())
        childEnv.each { k, v -> p.environment().put(k.toString(), v.toString()) }  // env map is String,String (values may be GStrings)
        p
    }

    /** Run a command, streaming stdio. Dies on nonzero unless [check:false]. Returns exit code. */
    int runCommand(List cmd, Map opts = [:]) {
        def code = newProcess(cmd).inheritIO().start().waitFor()
        if (code != 0 && opts.check != false) die("command failed ($code): ${cmd.join(' ')}", code)
        code
    }

    /** Where discarded child output goes. ProcessBuilder.Redirect.DISCARD would be the
     *  obvious spelling, but it is Java 9+ and this tooling runs on whatever JVM the HOST
     *  happens to have -- the Java 21 inside the compile image says nothing about it, and
     *  ZFIN's VMs ship Java 8, where DISCARD is a MissingPropertyException on the first
     *  `which`. redirectOutput(File) is Java 7 and does the same thing at the OS level: no
     *  pipe, so nothing to drain and no way for a chatty child to block on a full buffer.
     *  Unix-only, which this tooling already is (docker.sock, bash, /etc paths). */
    private static final File DEV_NULL = new File('/dev/null')

    /** Run with stdout+stderr discarded; return exit code (never dies). */
    int runQuietly(List cmd) {
        newProcess(cmd).redirectOutput(DEV_NULL).redirectError(DEV_NULL).start().waitFor()
    }

    /** Run; return trimmed stdout (stderr discarded). Never dies. */
    String captureOutput(List cmd) {
        def p = newProcess(cmd).redirectError(DEV_NULL).start()
        def out = p.inputStream.text; p.waitFor(); out.trim()
    }

    /** Like captureOutput, but MERGES stderr instead of discarding it.
     *
     *  Needed wherever the interesting output is on stderr -- `docker logs` being the case
     *  that forced this: a container's stderr comes back on the client's stderr, and postgres
     *  logs everything there, so captureOutput() returned an empty string for it. The
     *  clean-shutdown check in `z feature freeze` / `z shared freeze` looked for a marker in
     *  that output and could therefore never pass, failing open in one place and refusing to
     *  archive a perfectly clean database in the other. */
    String captureOutputMerged(List cmd) {
        def p = newProcess(cmd).redirectErrorStream(true).start()
        def out = p.inputStream.text; p.waitFor(); out.trim()
    }

    /** True if a docker image exists locally. */
    boolean imageExists(String ref) { runQuietly(['docker', 'image', 'inspect', ref]) == 0 }

    /** True if an external tool is resolvable on PATH. Optional integrations (tmux) are "use it
     *  if it's there, hint if it isn't", so they share one probe. */
    boolean onPath(String cmd) { runQuietly(['which', cmd]) == 0 }

    private Map<String, String> dotenvCache = null
    /** Parse docker/.env into a map (KEY=value lines). Parsed once per ZfinUtil instance
     *  (the base .env doesn't change mid-run). The single .env parser. */
    Map<String, String> dotenv() {
        if (dotenvCache != null) return dotenvCache
        def m = [:]
        def f = new File(DOCKER, '.env')
        if (f.isFile()) f.eachLine { line ->
            def mm = (line =~ /^([A-Za-z_][A-Za-z0-9_]*)=(.*)$/)
            if (mm.find()) m[mm.group(1)] = mm.group(2)
        }
        dotenvCache = m
    }

    // ---- host settings --------------------------------------------------------------------
    // How this host runs the tooling (StackConfig.HOST_SETTINGS), from two files, most specific
    // first:
    //   TREE  <dev root>/zfin-dev.env -- one dev tree's settings. z finds it by walking up from
    //         the working directory, the way git finds .git, and the directory holding it IS
    //         ZFIN_DEV_ROOT. Several trees on one host each keep their own.
    //   USER  ~/.config/zfin-build-orchestrator/env -- for running z outside any tree (a
    //         checkout kept elsewhere): it may name ZFIN_DEV_ROOT, and holds this user's defaults.
    // Precedence: the process environment, then the tree file, then the user file, then the
    // default. Never the ZFIN checkout's docker/.env -- that describes the checkout's stack.
    static final String TREE_CONFIG = 'zfin-dev.env'

    File userConfigFile() {
        def base = System.getenv('XDG_CONFIG_HOME') ?: "${System.getProperty('user.home')}/.config"
        new File(base, 'zfin-build-orchestrator/env')
    }

    private static Map<String, String> readEnvFile(File f) {
        def m = [:]
        if (f?.isFile()) f.eachLine { line ->
            def mm = (line =~ /^([A-Za-z_][A-Za-z0-9_]*)=(.*)$/)
            if (mm.find()) m[mm.group(1)] = mm.group(2)
        }
        m
    }
    private static File expandHome(String p) { new File(p.replaceFirst('^~', System.getProperty('user.home'))).absoluteFile }

    private Map<String, String> userConfigCache = null
    Map<String, String> userConfig() { userConfigCache != null ? userConfigCache : (userConfigCache = readEnvFile(userConfigFile())) }

    private boolean treeResolved = false
    private File treeRootCache = null
    private String treeVia = null     // 'env' | 'tree' | 'user': how the tree was found
    /** The dev tree: $ZFIN_DEV_ROOT, else the nearest directory at or above the working
     *  directory holding zfin-dev.env, else the user file's ZFIN_DEV_ROOT. Null when none. */
    File treeRoot() {
        if (treeResolved) return treeRootCache
        treeResolved = true
        if (System.getenv('ZFIN_DEV_ROOT')) { treeVia = 'env'; return (treeRootCache = expandHome(System.getenv('ZFIN_DEV_ROOT'))) }
        for (File d = new File('.').canonicalFile; d != null; d = d.parentFile)
            if (new File(d, TREE_CONFIG).isFile()) { treeVia = 'tree'; return (treeRootCache = d) }
        def fromUser = userConfig()['ZFIN_DEV_ROOT']
        if (fromUser) { treeVia = 'user'; treeRootCache = expandHome(fromUser) }
        treeRootCache
    }
    File treeConfigFile() { treeRoot() ? new File(treeRoot(), TREE_CONFIG) : null }

    private Map<String, String> treeConfigCache = null
    Map<String, String> treeConfig() { treeConfigCache != null ? treeConfigCache : (treeConfigCache = readEnvFile(treeConfigFile())) }

    /** A host setting: the environment, then the tree file, then the user file, then `dflt`.
     *  ZFIN_DEV_ROOT is the tree's own location (treeRoot), never a line in the tree file. */
    String setting(String key, String dflt = null) {
        if (key == 'ZFIN_DEV_ROOT') return treeRoot()?.path ?: dflt
        System.getenv(key) ?: (treeConfig()[key] ?: (userConfig()[key] ?: dflt))
    }

    /** Where a setting's value comes from, for `z config`: 'env', 'tree', 'user', or null. */
    String settingSource(String key) {
        if (key == 'ZFIN_DEV_ROOT') { treeRoot(); return treeVia }
        System.getenv(key) ? 'env' : (treeConfig()[key] ? 'tree' : (userConfig()[key] ? 'user' : null))
    }

    /** Write (or, with a null value, remove) one setting -- in the tree file, or with `user` in
     *  the user file -- keeping every other line, comments included, as it was. */
    void saveSetting(String key, String value, boolean user = false) {
        def f = user ? userConfigFile() : treeConfigFile()
        if (!f) die("no dev tree here to hold $key -- run z inside one, create one with z scaffold, " +
                    "or save it for this user: z config set --user $key=...")
        def lines = f.isFile() ? f.readLines()
                               : ["# zfin-build-orchestrator ${user ? 'settings for this user' : 'settings for this dev tree'} -- see `z config`".toString()]
        def kept = lines.findAll { !it.startsWith("${key}=") }
        if (value != null) kept << "${key}=${value}".toString()
        f.parentFile.mkdirs()
        f.text = kept.join('\n') + '\n'
        userConfigCache = null; treeConfigCache = null
    }

    /** Make `root` a dev tree: create it and its zfin-dev.env. When it is not at or above the
     *  working directory, also record it as the user's ZFIN_DEV_ROOT, or z would not find it
     *  from here. Returns true when it wrote the user file. */
    boolean markTree(File root) {
        root.mkdirs()
        def marker = new File(root, TREE_CONFIG)
        if (!marker.isFile())
            marker.text = "# zfin-build-orchestrator settings for this dev tree -- see `z config`\n"
        def cwd = new File('.').canonicalFile
        def above = false
        for (File d = cwd; d != null; d = d.parentFile) if (d == root.canonicalFile) { above = true; break }
        if (!above) saveSetting('ZFIN_DEV_ROOT', root.path, true)
        treeResolved = false; treeConfigCache = null
        !above
    }

    /** A value from the ZFIN checkout's docker/.env if set to a NON-EMPTY value, else the
     *  ambient environment, else `dflt` -- like compose's `${KEY:-dflt}`. That file describes
     *  the checkout's stack (ZFIN_RELEASE, DOCKER_ARCH); host settings use setting(). */
    String env(String key, String dflt = null) {
        def v = dotenv()[key]
        v ?: (System.getenv(key) ?: dflt)
    }

    /** Last value of `KEY=` in a dotenv-style file, or '' when the file or the key is absent.
     *  ONE implementation because the obvious inline version is a trap: the natural
     *  `findAll{...}[-1]` throws ArrayIndexOutOfBoundsException on an empty list rather than
     *  returning null, so `?: ''` never gets a chance. That bug shipped in both FeatureList
     *  and FeatureRemove and made `z feature rm` fail outright for any stack whose .env
     *  lacked the key being read (i.e. every feature not made with --existing-branch).
     *  Last-wins matches how the per-feature .env is built: new-feature APPENDS its overrides
     *  to a copy of the base env, so a duplicated key means "the override". */
    String envField(File envFile, String key) {
        if (!envFile?.isFile()) return ''
        def vals = envFile.readLines().findAll { it.startsWith(key + '=') }.collect { it.split('=', 2)[1] }
        vals ? vals[-1] : ''
    }

    /** Step timer for the long-running commands. `mark(label)` closes the step that just ran;
     *  `report()` prints them with a total. Freeze and thaw are multi-minute operations whose
     *  cost is not evenly spread -- knowing whether the time went to the database, the index or
     *  the shutdown is the difference between tuning the right thing and guessing.
     *
     *  A map of closures rather than a class: it is three lines of state, and this file is
     *  loaded through a GroovyClassLoader where an extra top-level class earns its keep. */
    Map stepTimer() {
        def t0 = System.currentTimeMillis()
        def last = [t0]
        def marks = []
        [
            mark  : { String label ->
                def now = System.currentTimeMillis()
                marks << [label: label, secs: (now - last[0]) / 1000.0]
                last[0] = now
            },
            report: { String title ->
                // Up to the last mark, not to now: whatever runs after it (a tmux attach that
                // lasts as long as you stay attached) is not part of the operation being timed.
                def total = (last[0] - t0) / 1000.0
                def w = Math.max(14, (marks.collect { (it.label as String).length() } + [0]).max())
                println ""
                println "  $title"
                marks.each { m ->
                    // Share of total, so the dominant step is obvious without doing the sums.
                    def pct = total > 0 ? (m.secs / total * 100.0) : 0
                    println String.format("    %-${w}s %7.1fs  %4.0f%%", m.label, m.secs, pct)
                }
                println String.format("    %-${w}s %7.1fs", 'TOTAL', total)
            }
        ]
    }

    /** Tar a named volume to `out` (gzip). Root + --entrypoint tar so it can read both
     *  postgres-owned and solr-owned contents and bypass the image's own entrypoint.
     *  Shared by `z feature freeze` and `z seed create`, so freeze archives and seeds are
     *  produced by the same code. */
    /** Is pigz (parallel gzip) present in the tar image? Cached: the probe costs a container
     *  start, and freeze asks once per volume. Null until first asked. */
    private Boolean pigzAvailable = null
    boolean hasPigz() {
        if (pigzAvailable == null)
            pigzAvailable = runQuietly(['docker', 'run', '--rm', '--entrypoint', 'sh',
                                        tarImage(), '-c', 'command -v pigz']) == 0
        pigzAvailable
    }

    void captureVolume(String vol, File out, boolean compress = true) {
        out.parentFile.mkdirs()
        def compressor = compress ? (hasPigz() ? 'pigz' : 'gzip') : null
        info("capturing $vol -> $out${compress ? " ($compressor -1)" : ' (uncompressed)'}")
        // Measured on this hardware, tarring a 5.7G solr volume:
        //     tar cf        25s   5.3G   212 MB/s
        //     gzip -1      111s   3.1G    51 MB/s
        //     gzip -6      198s   2.9G    29 MB/s   <- tar's czf default
        // gzip is single-threaded, so compression -- not disk -- is what dominates capture time.
        // When compressing at all, -1 is the level that makes sense: -6 costs another 87s to
        // save a further 0.2G. pigz parallelises it across cores; hasPigz() falls back to gzip
        // on an image without it. pigz is a drop-in parallel gzip and the output is an ordinary
        // gzip stream, so an archive written with it is readable by plain tar/gzip anywhere --
        // the choice is a speed detail, not a format decision.
        def tarArgs = compress ? ['-I', "$compressor -1".toString(), '-cf'] : ['-cf']
        runCommand(['docker', 'run', '--rm', '-u', '0', '--entrypoint', 'tar',
                    '-v', "${vol}:/data:ro",
                    '-v', "${out.parentFile.absolutePath}:/out",
                    tarImage()] + tarArgs + ["/out/${out.name}", '-C', '/data', '.'])
    }

    /** The archive file for a volume in `dir`, whichever form it was written in: uncompressed
     *  `<vn>.tar` or gzipped `<vn>.tgz` (freeze and seed create both write either, depending
     *  on --compress and whether pigz is available). Returns null when neither exists. */
    File archiveFileFor(File dir, String vn) {
        [new File(dir, "${vn}.tar"), new File(dir, "${vn}.tgz")].find { it.isFile() }
    }

    /** Create `<project>_<vn>` for each name and extract `<srcDir>/<vn>.tgz` into it, in
     *  parallel. Returns one result map per volume ([vn, vol, mb, secs, ok, err]); the caller
     *  reports and decides what a failure means. The counterpart of captureVolume, and the
     *  same code `z feature new` uses to restore a seed -- so a freeze archive and a seed
     *  restore identically.
     *
     *  Parallel because the cost is container startup plus writing many small files, which
     *  overlaps well (raw gzip is ~1s). Per-index result slots, so no contention; output is
     *  printed by the caller after the join, in order, rather than interleaved. */
    List<Map> restoreVolumes(String project, List<String> vns, File srcDir) {
        // Report each volume AS IT LANDS, not all of them afterwards. These run concurrently and
        // the big one (~16G pg_data) takes minutes, so a silent block followed by four lines
        // looked identical to a hang. Order is completion order, which is the useful order --
        // the small volumes finish first and you can see it is moving.
        // synchronized because several threads print: without it two lines interleave mid-word.
        def lock = new Object()
        def done = 0
        def announce = { Map r ->
            synchronized (lock) {
                done++
                info(String.format("  [%d/%d] %-14s %7.0f MB in %5.1fs%s",
                        done, vns.size(), r.vn, r.mb, r.secs, r.ok ? '' : '   !! FAILED'))
            }
        }
        def results = new Object[vns.size()]
        def threads = []
        def img = tarImage()
        vns.eachWithIndex { vn, idx ->
            threads << Thread.start {
                def vol = "${project}_${vn}"
                def cname = "zfin-restore-${project}-${vn}"   // deterministic: always cleanable
                def tgz = archiveFileFor(srcDir, vn) ?: new File(srcDir, "${vn}.tgz")
                def mb = tgz.isFile() ? tgz.length() / 1048576.0 : 0
                def t0 = System.currentTimeMillis()
                def run2 = { List c ->
                    def pr = new ProcessBuilder(c*.toString()).redirectErrorStream(true).start()
                    def o = pr.inputStream.text          // drain (avoid pipe deadlock) + capture
                    [code: pr.waitFor(), out: o]
                }
                try {
                    run2(['docker', 'rm', '-f', cname])   // clear a stale orphan from an interrupted run
                    def cr = run2(['docker', 'volume', 'create',
                                   '--label', "com.docker.compose.project=$project",
                                   '--label', "com.docker.compose.volume=$vn", vol])
                    // No --rm: a --rm container that never STARTS (interrupt) is left "Created"
                    // and pins the volume; name it and remove it explicitly in finally instead.
                    def ex = run2(['docker', 'run', '--name', cname, '-u', '0', '--entrypoint', 'tar',
                                   '-v', "${vol}:/data",
                                   '-v', "${srcDir.absolutePath}:/in:ro",
                                   // `xf`, not `xzf`: GNU tar sniffs the compression, so one
                                   // path handles both .tar and .tgz archives.
                                   img, 'xf', "/in/${tgz.name}", '-C', '/data'])
                    def ok = cr.code == 0 && ex.code == 0
                    results[idx] = [vn: vn, vol: vol, mb: mb, file: tgz.name,
                                    secs: (System.currentTimeMillis() - t0) / 1000.0,
                                    ok: ok, err: (ok ? '' : (cr.out + ex.out).trim())]
                    announce(results[idx])
                } catch (Throwable t) {
                    results[idx] = [vn: vn, vol: vol, mb: mb, file: tgz.name, secs: 0, ok: false, err: t.toString()]
                    announce(results[idx])
                } finally {
                    run2(['docker', 'rm', '-f', cname])   // always remove (normal + error paths)
                }
            }
        }
        threads*.join()
        results as List
    }

    boolean volumeExists(String vol) { runQuietly(['docker', 'volume', 'inspect', vol]) == 0 }

    /** The feature-stack inventory: ONE derivation, read by `z feature ls` and
     *  `z feature refresh`. Derived from
     *  ground truth (worktrees on disk + docker) every time rather than maintained
     *  incrementally, so it is self-healing: a stack stopped or removed behind z's back shows
     *  up correctly on the next call instead of going stale.
     *
     *  Keys: slug, project, branch, host, worktree, data (own|shared), state (up|down|...),
     *  url (see stackUrls). */
    List<Map> featureStacks() {
        def wtParent = new File(worktreesDir())
        // A feature IS a linked worktree with a provisioned docker/.env. Linked, because the
        // main checkout sits here too (worktrees/main) with a docker/.env of its own.
        def wts = ((wtParent.listFiles() ?: []) as List)
                .findAll { it.isDirectory() && isLinkedWorktree(it) && new File(it, 'docker/.env').isFile() }.sort { it.name }
        // "up" means SERVING, so it is keyed on httpd -- the service that answers the URL in
        // the same row. Keyed on any container with the project label, a `z run claude` sidecar
        // or a stray `z run compile` made a stack read as up while its URL returned 503 from the
        // proxy, which is worse than no column at all: the table and the link disagreed and the
        // table was the confident one.
        def servingProjects = captureOutput(['docker', 'ps', '--filter',
                "label=com.docker.compose.service=${StackConfig.WEB_SERVICE}",
                '--format', '{{.Label "com.docker.compose.project"}}'])
                .readLines().findAll { it } as Set
        // Any RUNNING container, httpd or not -- distinguishes a stack that is merely partly up
        // (data tier, or a sidecar) from one that is genuinely stopped.
        def running = captureOutput(['docker', 'ps', '--format', '{{.Label "com.docker.compose.project"}}'])
                .readLines().findAll { it } as Set
        // `frozen` is derived, not recorded: a freeze archive exists AND the project has no
        // containers at all. That keeps it self-healing -- thawing recreates containers and
        // the state flips back on its own, with no marker to go stale. A plain `z down` has
        // no containers either, but no archive, so the two stay distinguishable.
        def anyContainers = captureOutput(['docker', 'ps', '-a', '--format', '{{.Label "com.docker.compose.project"}}'])
                .readLines().findAll { it } as Set
        def archiveRoot = new File(archiveDir(false))   // only asks whether a stack is frozen
        wts.collect { wt ->
            def envF   = new File(wt, 'docker/.env')
            def proj   = envField(envF, 'COMPOSE_PROJECT_NAME') ?: wt.name
            def host   = envField(envF, 'DOCKER_VIRTUAL_HOST')
            def branch = captureOutput(['git', '-C', wt.absolutePath, 'rev-parse', '--abbrev-ref', 'HEAD']) ?: '?'
            def spec   = stackSpec(wt)
            [ slug    : wt.name,
              project : proj,
              branch  : branch,
              host    : host,
              worktree: wt.name,
              data    : spec?.data ?: 'own',
              state   : servingProjects.contains(proj) ? 'up'
                        : running.contains(proj) ? 'partial'
                        : (!anyContainers.contains(proj) &&
                           new File(new File(archiveRoot, wt.name),
                                    StackConfig.FREEZE_MANIFEST).isFile() ? 'frozen' : 'down'),
              url     : stackUrls(envF).primary ?: '',
              spec    : spec ]
        }
    }

    /** Which Compose project provides the shared db+solr that `--shared-db` stacks attach to.
     *
     *  Normally `zfin_shared` -- a dedicated stack restored from a seed by
     *  `z shared up`. But that costs ~30G of images plus a ~31G data copy, which does not fit
     *  on every host: a VM with 60G free and a loaded instance ALREADY RUNNING can point this
     *  at that instance instead and spend nothing.
     *
     *  Doing so means feature stacks read and write a REAL instance's database. That is a
     *  bigger claim than the usual shared-data warning, so `z feature new` says it plainly
     *  when the target is not the dedicated stack.
     */
    String sharedProject() { setting('ZFIN_SHARED_PROJECT', 'zfin_shared') }

    /** Is `project` a shared data stack `z shared` runs -- its db carries SHARED_DATA_LABEL --
     *  or one that does not exist yet, which `z shared up` may create? False for a real
     *  instance's project, and for a stack made by earlier tooling without the label. Looks at
     *  stopped containers too, and, with none at all, at whether its pg_data volume exists. */
    boolean managedSharedStack(String project) {
        def cid = captureOutput(['docker', 'ps', '-aq', '--filter', "label=com.docker.compose.project=$project",
                                 '--filter', 'label=com.docker.compose.service=db']).readLines().find { it }
        if (cid) return captureOutput(['docker', 'inspect', cid, '--format',
                "{{index .Config.Labels \"${StackConfig.SHARED_DATA_LABEL}\"}}"]) == 'true'
        !volumeExists("${project}_pg_data")
    }

    /** How to install a tool. Names BOTH platforms rather than detecting one: whoever reads
     *  this is often setting up a different machine from the one that printed it. Deliberately
     *  vague about the Linux package manager rather than guessing between apt/dnf/yum. */
    String installHint(String pkg) { "brew install $pkg (macOS) / your package manager, e.g. apt install $pkg (Linux)" }

    /** The feature slug for the worktree the cwd is in, or null.
     *
     *  Lets `z feature freeze|thaw|rm|refresh` be run with no argument from inside a feature
     *  worktree, the way the stack ops (run/up/down/...) already resolve their stack from the
     *  cwd. Typing the ticket while standing in its own directory is the kind of redundancy
     *  that makes a tool feel like paperwork.
     *
     *  Resolved from the worktree's own docker/.env, falling back to the directory's name
     *  when that is missing or unreadable -- which is exactly when you are most likely to be
     *  repairing it. Returns null in the MAIN
     *  checkout: that is not a feature, and inferring one there would be a guess. */
    String featureSlugFromCwd(File cwd = new File('.').canonicalFile) {
        def top = captureOutput(['git', '-C', cwd.absolutePath, 'rev-parse', '--show-toplevel'])
        if (!top) return null
        def dir = new File(top).canonicalFile
        if (dir == REPO.canonicalFile) return null          // the checkout itself is not a feature
        def spec = stackSpec(dir)
        if (spec?.project) return spec.project
        // Fallback for a worktree whose .env is missing or unreadable -- which is exactly when
        // you are most likely to be repairing it. A linked worktree under the worktrees dir IS
        // a feature, and its name is the slug.
        isLinkedWorktree(dir) && dir.parentFile?.canonicalFile == new File(worktreesDir()).canonicalFile ? dir.name : null
    }

    /** Archive the sidecar's session history for a stack, returning the tarball or null when
     *  there is nothing to archive.
     *
     *  Kept in `<archive>/sessions/`, a SIBLING of the per-stack freeze archives rather than
     *  inside one -- `z feature rm` deletes a stack's archive directory, and the session is
     *  precisely the thing you might still want afterwards: what the agent was asked, what it
     *  tried, why the branch looks the way it does. Timestamped rather than overwritten, so
     *  repeated exports build a history instead of replacing one.
     *
     *  Compressed: sessions are megabytes and long-lived, the opposite of the freeze archives
     *  where wall-clock beat size.
     *
     *  Contains no credential. The sidecar's token is a host file mounted at
     *  /run/secrets/claude-token and never enters claude_home. */
    File archiveClaudeSession(String project, String slug) {
        def vol = "${project}_${StackConfig.CLAUDE_VOL}"
        if (!volumeExists(vol)) return null
        def stamp = new Date().format('yyyyMMdd-HHmmss')
        def dir = new File(archiveDir(), 'sessions')
        def out = new File(dir, "${slug}-${stamp}.tgz")
        captureVolume(vol, out, true)
        // ...and the transcripts on their own, beside it. The full volume is mostly things
        // nobody will read: measured on a real archive, plugins/marketplaces was 667 of ~690
        // entries and 6.5M of 10M extracted, against 4 .jsonl files holding the actual
        // conversation. Keeping both means the cheap file is the one you reach for and the
        // volume is still there if a session ever has to be resumed rather than read.
        def tx = new File(dir, "${slug}-${stamp}.transcripts.tgz")
        captureVolumePath(vol, tx, 'projects')
        out
    }

    /** Capture ONE subdirectory of a volume. Same mechanism as captureVolume, narrowed: a
     *  missing path is not an error (a stack whose agent never ran has no projects/), so the
     *  caller gets a file only when there was something to put in it. */
    void captureVolumePath(String vol, File out, String rel) {
        out.parentFile.mkdirs()
        def compressor = hasPigz() ? 'pigz' : 'gzip'
        def code = runCommand(['docker', 'run', '--rm', '-u', '0', '--entrypoint', 'tar',
                    '-v', "${vol}:/data:ro", '-v', "${out.parentFile.absolutePath}:/out",
                    tarImage(), '-I', "$compressor -1".toString(), '-cf',
                    "/out/${out.name}", '-C', '/data', rel], [check: false])
        if (code != 0 || !out.isFile() || out.length() == 0) { out.delete(); return }
        info(String.format("transcripts -> %s (%.1f MB)", out, out.length() / 1048576.0))
    }

        /** The one path a host MUST declare: the parent holding everything this tooling owns.
     *
     *  Deliberately no fallback. An absolute default like /opt/zfin/dev is a guess about
     *  someone else's machine that works until it quietly does not -- and when it is wrong the
     *  failure is a directory appearing somewhere unexpected, not an error. A tree is declared
     *  by its zfin-dev.env file instead (see treeRoot); with none found, z asks.
     *
     *  The recommended layout (docs/dev-tree-layout.md) puts repo, worktrees,
     *  archives, caches and the mounted data directories under this one parent, so a
     *  developer machine has a single thing to back up, relocate or delete. Individual
     *  directories can still be pointed elsewhere for offload -- see the overrides below --
     *  and a symlink works too on Linux (less reliably under Docker Desktop, which resolves
     *  bind sources host-side). */
    String devRoot() {
        def root = treeRoot()
        if (root) return root.path
        def con = System.console()
        if (!con) die("no dev tree found: no ${TREE_CONFIG} at or above ${new File('.').canonicalPath}.\n" +
                      "   A dev tree is the directory holding worktrees/, archive/ and cache/. Create one:\n" +
                      "     z scaffold --root ${devRootSuggestion()}\n" +
                      "   See docs/dev-tree-layout.md for the recommended structure.")
        println "No dev tree found at or above here. A dev tree is the directory holding this tooling's"
        println "worktrees, archives and caches (docs/dev-tree-layout.md), marked by a ${TREE_CONFIG} file."
        def suggestion = devRootSuggestion()
        def answer = con.readLine("Create one at [${suggestion}]: ")?.trim() ?: suggestion
        def dir = expandHome(answer)
        def recorded = markTree(dir)
        info("created ${new File(dir, TREE_CONFIG)}" + (recorded ? " (and recorded it in ${userConfigFile()}, since it is not above here)" : ''))
        dir.path
    }

    /** What to offer for ZFIN_DEV_ROOT: the value a ZFIN checkout's docker/.env already carries,
     *  when the working directory is in one that has it, else the parent of that checkout, else
     *  ~/zfin-dev. Probes git directly rather than through REPO, which dies outside a checkout. */
    String devRootSuggestion() {
        def common = captureOutput(['git', 'rev-parse', '--path-format=absolute', '--git-common-dir'])
        def repo = common ? new File(common).parentFile : null
        def fromEnv = repo ? envField(new File(repo, 'docker/.env'), 'ZFIN_DEV_ROOT') : null
        fromEnv ?: (repo?.parentFile?.absolutePath ?: "${System.getProperty('user.home')}/zfin-dev")
    }

    /** Per-directory overrides, each defaulting to a place under ZFIN_DEV_ROOT. Override one
     *  when it has to live elsewhere -- archives on NFS being the obvious case. */
    String worktreesDir() {
        def d = setting('ZFIN_WORKTREES_DIR') ?: "${devRoot()}/worktrees"
        // Not a symlink: git records a worktree's REAL path in its pointers, while z records the
        // path it was given, and every container mounts the worktree at that path -- through a
        // symlink the two differ and git fails in the containers. Point the setting at the real
        // directory instead.
        if (java.nio.file.Files.isSymbolicLink(new File(d).toPath()))
            die("$d is a symlink. worktrees/ must be a real directory (git records real paths);\n" +
                "   set ZFIN_WORKTREES_DIR to its target instead:  z config set ZFIN_WORKTREES_DIR=${new File(d).canonicalPath}")
        d
    }

    /** `path`, a directory this tooling keeps data in, after checking that a symlink there leads
     *  somewhere. seeds/, archive/ and cache/ may be symlinks to other storage -- an external disk,
     *  an NFS share -- and simply following one needs nothing from us. A DANGLING one, with the
     *  disk unplugged or the share not mounted on this host, would otherwise surface as a failed
     *  mkdir or a Docker error naming neither; say what it is instead. */
    String checkedDir(String path, String what) {
        def p = new File(path).toPath()
        if (java.nio.file.Files.isSymbolicLink(p) && !java.nio.file.Files.exists(p)) {
            def target = p.parent.resolve(java.nio.file.Files.readSymbolicLink(p)).normalize()
            die("$what ($path) is a symlink to $target, which does not exist on this host.\n" +
                "   Is that disk or share mounted?")
        }
        path
    }
    /** The two absolute git paths the sidecar must bind, for a worktree OR a plain checkout:
     *  [common, dir]. For a worktree these differ (<repo>/.git and <repo>/.git/worktrees/<slug>);
     *  for a plain checkout both are <repo>/.git, so callers need no special case. */
    List<String> gitDirs(File tree) {
        def one = { String flag ->
            captureOutput(['git', '-C', tree.absolutePath, 'rev-parse',
                           '--path-format=absolute', flag])?.trim()
        }
        [one('--git-common-dir'), one('--git-dir')]
    }

    /** `check` false is for a caller that only peeks -- is there an archive for this stack? --
     *  and so should not fail when the archive's storage is away: a dangling link reads as empty. */
    String archiveDir(boolean check = true) {
        def d = setting('ZFIN_ARCHIVE_DIR') ?: "${devRoot()}/archive"
        check ? checkedDir(d, 'the archive directory') : d
    }
    String cacheDir()     { checkedDir(setting('ZFIN_CACHE_DIR') ?: "${devRoot()}/cache", 'the cache directory') }

    /** A host path from docker/.env, with the leading ~ compose would expand. */
    File envPath(String key) {
        def v = env(key)
        v ? new File(v.replaceFirst('^~', System.getProperty('user.home'))) : null
    }

    /** Seeds: reusable captures of a loaded stack, restored into a new stack by
     *  `z feature new --seed`. A SIBLING of the per-stack freeze archives and of sessions/,
     *  never inside one -- `z feature rm` deletes a stack's freeze archive, and a seed (hours
     *  of loading, shared by every stack on the host) must survive that. */
    /** Captured stacks that new stacks restore from: active inputs, kept apart from archive/
     *  (parked state). Its own override, since seeds and archives may each want to live on NFS. */
    File seedsDir() { new File(checkedDir(setting('ZFIN_SEEDS_DIR') ?: "${devRoot()}/seeds", 'the seeds directory')) }
    File seedDir(String tag) { new File(seedsDir(), tag) }

    /** Newest seed on this host, or null. Lets `--seed` be optional: the common case is "the
     *  one I just made". Ordered by the manifest's `created` stamp, not the tag -- tags are free
     *  text, and sorting by name makes a tag like `mactest` outrank every dated one. The
     *  directory's mtime covers a manifest without the field. */
    String newestSeed() {
        def dirs = (seedsDir().listFiles() ?: []).findAll { it.isDirectory() && new File(it, StackConfig.SEED_MANIFEST).isFile() }
        def when = { File d ->
            readSeedManifest(d)?.created ?: new Date(d.lastModified()).format('yyyy-MM-dd HH:mm:ss')
        }
        dirs ? dirs.max { when(it) }.name : null
    }

    /** SHA-256 of a file, streamed. Over the COMPRESSED tarball (~5G), never the expanded
     *  data (~21G), so a whole seed costs seconds rather than minutes. */
    String sha256(File f) {
        def md = java.security.MessageDigest.getInstance('SHA-256')
        f.withInputStream { ins ->
            byte[] buf = new byte[1 << 20]
            int n
            while ((n = ins.read(buf)) > 0) md.update(buf, 0, n)
        }
        md.digest().encodeHex().toString()
    }

    /** The platform CONTAINERS run on -- linux/arm64 on a Mac, linux/amd64 on the VMs. Docker's
     *  server, not the host: on a Mac the host is darwin but postgres runs linux/arm64, and it is
     *  the latter that wrote the data directory. */
    String dockerPlatform() {
        captureOutput(['docker', 'version', '--format', '{{.Server.Os}}/{{.Server.Arch}}'])?.trim() ?: ''
    }

    /** Why an archive captured elsewhere must not be restored here, or null when it is fine.
     *  Shared by seeds and freeze archives because the hazard is identical: both carry a
     *  PostgreSQL DATA DIRECTORY, which Postgres does not support moving between platforms. The
     *  danger is that it usually STARTS anyway -- same major version, both little-endian LP64,
     *  so the pg_control checks pass -- and then text index ordering follows whichever glibc
     *  wrote it. That surfaces as wrong query results from a database that looks healthy, not as
     *  an error, which is the worst way for this to fail. Archives written before `platform` was
     *  recorded have none, and are allowed: they predate the check, not the hazard.
     *  @param what  how to name the archive in the message
     *  @param was   the platform recorded in its manifest, or null
     *  @param fix   the one-line remedy for this kind of archive */
    String platformProblem(String what, String was, String fix) {
        if (!was) return null
        // What matters is the postgres that will OPEN it here: this host's db image.
        def now = imagePlatform(dbImage()) ?: dockerPlatform()
        if (!now || was == now) return null
        "$what holds a ${was} PostgreSQL data directory; this host's db image is ${now}.\n" +
        "   Its pg_data is a PostgreSQL data directory, which is not portable between platforms.\n" +
        "   It would probably start and then return wrong results for text comparisons, because\n" +
        "   index ordering follows the glibc that wrote it. The app tier (www_data,\n" +
        "   catalina_base) and solr_var ARE portable -- the data tier is not.\n" +
        "   ${fix}\n" +
        "   To override anyway:  ZFIN_ALLOW_PLATFORM_MISMATCH=1 <your command>"
    }

    /** True when the caller has explicitly accepted a cross-platform restore. */
    boolean allowPlatformMismatch() { System.getenv('ZFIN_ALLOW_PLATFORM_MISMATCH') as boolean }

    String seedPlatformProblem(String tag) {
        platformProblem("seed '$tag'", readSeedManifest(seedDir(tag))?.platform,
                        "Make a seed on this host instead:  z seed create")
    }

    /** What a seed was built from, recorded in its manifest as `built_from`. Set by
     *  `z seed build`, which knows the dump, the snapshot and the commit; a plain
     *  `z seed create` captures an existing stack and knows none of them. */
    Map seedProvenance = [:]

    /** Write <dir>/seed.json: what the seed is, what wrote it, and a checksum per tarball.
     *
     *  The checksums exist because a seed has none of an image's guarantees. Docker verifies
     *  a layer digest on every use; a tarball on NFS can be truncated by a full disk or damaged
     *  in a copy (seedProblems checks for both). `pg_major` is here for the same reason, from
     *  the other direction: PGDATA written by one postgres major cannot be opened by another,
     *  and catching that at restore beats discovering it when the server refuses to start. */
    Map writeSeedManifest(File dir, Map meta) {
        def vols = (meta.volumes ?: []).collect { String vn ->
            def f = archiveFileFor(dir, vn)
            [name: vn, file: f?.name, bytes: f?.length() ?: 0L, sha256: f ? sha256(f) : '']
        }
        def m = [tag: meta.tag, created: new Date().format('yyyy-MM-dd HH:mm:ss'),
                 source_project: meta.source_project, release: meta.release,
                 pg_major: meta.pg_major, platform: meta.platform ?: dockerPlatform(), volumes: vols]
        if (seedProvenance) m.built_from = seedProvenance
        new File(dir, StackConfig.SEED_MANIFEST).text = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(m))
        m
    }

    /** Read <dir>/seed.json, or null when it is absent or unparseable -- callers decide what a
     *  missing manifest means (ls prints "(no manifest)"; a restore refuses). */
    Map readSeedManifest(File dir) {
        def f = new File(dir, StackConfig.SEED_MANIFEST)
        if (!f.isFile()) return null
        try { return new groovy.json.JsonSlurper().parse(f) as Map } catch (ignored) { return null }
    }

    /** Record tarballs added to an existing seed (`z seed add-volumes`): replace or append their
     *  entries in seed.json and keep the rest -- created, built_from, platform and pg_major
     *  describe the data tier, which adding volumes does not touch. */
    Map addSeedManifestVolumes(File dir, List<String> vns, String fromProject) {
        def m = readSeedManifest(dir)
        def added = new Date().format('yyyy-MM-dd HH:mm:ss')
        def entries = ((m.volumes ?: []) as List).findAll { !(it.name in vns) }
        vns.each { vn ->
            def f = archiveFileFor(dir, vn)
            entries << [name: vn, file: f.name, bytes: f.length(), sha256: sha256(f),
                        source_project: fromProject, added: added]
        }
        m.volumes = entries
        // Written beside and renamed over, so an interrupted write cannot leave a manifest
        // that is half a JSON document.
        def tmp = new File(dir, StackConfig.SEED_MANIFEST + '.part')
        tmp.text = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(m))
        java.nio.file.Files.move(tmp.toPath(), new File(dir, StackConfig.SEED_MANIFEST).toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        m
    }

    /** What is wrong with a seed's tarballs, measured against its manifest: one line per
     *  tarball that is missing, or whose size or (with `full`) SHA-256 differs from what was
     *  recorded. Empty when all is well. `vns` limits the check to the volumes about to be
     *  restored; null checks every volume the manifest lists.
     *
     *  Every restore checks sizes, because that is free and catches the likeliest damage: a
     *  copy cut short by a full disk or an interrupted move to a share. The hash has to read
     *  every byte, which on a network share takes minutes, so it is left to `z seed verify`. */
    List<String> seedProblems(File dir, Map manifest, List<String> vns = null, boolean full = false) {
        def problems = []
        ((manifest?.volumes ?: []) as List).findAll { vns == null || it.name in vns }.each { v ->
            def f = v.file ? new File(dir, v.file as String) : null
            if (!f?.isFile()) { problems << "${v.name}: ${v.file ?: 'its tarball'} is missing".toString(); return }
            if (v.bytes != null && f.length() != (v.bytes as long)) {
                problems << "${v.name}: ${f.name} is ${f.length()} bytes; the manifest recorded ${v.bytes}".toString()
                return
            }
            if (full && v.sha256 && sha256(f) != v.sha256)
                problems << "${v.name}: ${f.name} does not match its recorded SHA-256".toString()
        }
        problems
    }

    /** Volumes with a tarball in a seed directory that its manifest does not list. They
     *  restore like any other, but nothing can check them. */
    List<String> untrackedSeedVolumes(File dir, Map manifest) {
        def listed = ((manifest?.volumes ?: []) as List)*.name
        (dir.listFiles() ?: []).findAll { it.isFile() && it.name ==~ /.+\.(tar|tgz)/ }
                .collect { it.name.replaceFirst(/\.(tar|tgz)$/, '') }
                .findAll { !(it in listed) }.unique().sort()
    }

    /** Which of a seed's app-tier volumes are missing or too small to be a deployed app.
     *  Empty list = the seed carries a usable app tier. Null manifest -> [] (say nothing
     *  rather than cry wolf about a seed we cannot read). */
    List<String> thinAppTier(String tag) {
        def m = readSeedManifest(seedDir(tag))
        if (!m) return []
        def byName = [:]
        (m.volumes ?: []).each { v -> byName[v.name] = (v.bytes ?: 0L) as long }
        StackConfig.APP_TIER_VOLUMES.findAll { vn ->
            !byName.containsKey(vn) || byName[vn] < StackConfig.APP_TIER_MIN_BYTES
        }
    }

    /** Stop with a useful message when <slug> is not a feature on this host. Called BEFORE any
     *  command offers a destructive flag: `z feature rm <typo>` used to reach its TTY check and
     *  answer "pass --force to confirm", which invites the destructive flag against a target
     *  that does not exist. Naming the known slugs costs one `ls` and answers the likely typo. */
    void requireFeature(String slug) {
        def known = ((new File(worktreesDir()).listFiles() ?: []) as List)
                .findAll { it.isDirectory() && isLinkedWorktree(it) && new File(it, 'docker/.env').isFile() }
                *.name.sort()
        if (slug in known) return
        die("no such feature: ${slug}\n" +
            (known ? "   known: ${known.join(', ')}" : "   none on this host yet -- z feature new <ticket>"))
    }

    /** The dump `gradle loaddb` will load from a db-unloads directory, chosen the way
     *  build.gradle's loadDatabase chooses it: the newest-mtime entry, then the last file in it.
     *  Returns [file: File] or [error: String], so a caller can refuse before a long build
     *  rather than fail inside gradle with "Cannot access last() element from an empty Array". */
    Map newestDbDump(File unloads) {
        if (!unloads?.isDirectory()) return [error: "no db unloads directory at ${unloads}"]
        def entries = (unloads.listFiles() ?: []) as List
        if (!entries) return [error: "${unloads} is empty"]
        def newest = entries.max { it.lastModified() }
        if (!newest.isDirectory())
            return [error: "loaddb picks the newest entry in ${unloads}, and that is the FILE '${newest.name}'.\n" +
                           "   It expects dated directories, each holding one dump."]
        def dumps = ((newest.listFiles() ?: []) as List).findAll { it.isFile() }.sort { it.name }
        if (!dumps) return [error: "loaddb picks the newest directory '${newest.name}', but it is EMPTY.\n" +
                                   "   Remove empty dirs under ${unloads} so the one holding the .bak is newest."]
        [file: dumps.last()]
    }

    /** The snapshot.* directories `gradle getLatestSolrIndex` restores from, newest first --
     *  <solr-unloads>/<instance>/snapshot.*, the layout `gradle getsolr` writes. Newest by mtime,
     *  which is how the task picks. */
    List<File> solrSnapshots(File unloads, String instance = 'zfindb') {
        def dir = new File(unloads, instance)
        ((dir.listFiles() ?: []) as List).findAll { it.isDirectory() && it.name.startsWith('snapshot.') }
                .sort { -it.lastModified() }
    }

    /** Write a stack's env file: `baseEnv` minus the keys in `strip`, then a marked block of
     *  `values`. A key stripped but not given a value goes back to its compose default -- which
     *  is how a stack drops the base env's published ports. */
    void writeStackEnv(File baseEnv, File out, Collection<String> strip, String header, Map values) {
        def owned = (strip + values.keySet()) as Set
        def kept = baseEnv.readLines().findAll { line ->
            def m = (line =~ /^([A-Za-z_][A-Za-z0-9_]*)=/)
            !(m.find() && m.group(1) in owned)
        }
        out.parentFile.mkdirs()
        out.text = kept.join('\n') + '\n\n# --- ' + header + ' ---\n' +
                   values.collect { k, v -> "$k=${v ?: ''}" }.join('\n') + '\n'
    }

    // ---- the host's development TLS certificate ----------------------------------------------
    // ONE self-signed certificate per host and feature domain, installed into every stack this
    // tooling makes, so trusting it once (z cert) covers every stack. Left to themselves, the
    // stack's compile container would make each stack its own (generate_base.sh in the ZFIN
    // repo), and a certificate trusted for one stack would not cover the next. That script only
    // creates files that are missing, so a stack that already has these keeps them.

    /** Where the host certificate lives: beside the config file, per user, per domain. */
    File devCertDir() { new File(userConfigFile().parentFile, "dev-cert/${featureDomain()}") }

    private String compileImageName() { StackConfig.compileImage(env('ZFIN_RELEASE'), env('DOCKER_ARCH', '')) }

    private static final String DEV_CERT_SCRIPT = '''
set -e
cd /out
openssl req -x509 -newkey rsa:2048 -nodes -days 825 \\
  -subj "/C=US/ST=Oregon/L=Eugene/O=University of Oregon/OU=ZFIN/CN=zfin.org" \\
  -addext "subjectAltName=DNS:zfin.org,DNS:*.${DOMAIN},DNS:${DOMAIN},DNS:localhost,IP:127.0.0.1" \\
  -addext "extendedKeyUsage=serverAuth" \\
  -keyout zfin.org.key -out zfin.org.crt 2>/dev/null
chmod 600 zfin.org.key
chmod 644 zfin.org.crt
chown "$OWNER" zfin.org.key zfin.org.crt
'''

    /** The host certificate's directory, creating the certificate on first use. Made inside the
     *  compile image (openssl), so the host needs no tooling of its own; 825 days, the longest
     *  validity browsers accept for a certificate you trust by hand. */
    File ensureDevCert() {
        def dir = devCertDir()
        if (new File(dir, 'zfin.org.crt').isFile() && new File(dir, 'zfin.org.key').isFile()) return dir
        dir.mkdirs()
        def owner = "${captureOutput(['id', '-u'])}:${captureOutput(['id', '-g'])}".toString()
        info("creating this host's development certificate (zfin.org, *.${featureDomain()}, localhost) in $dir")
        runCommand(['docker', 'run', '--rm', '-u', '0', '--entrypoint', 'bash',
                    '-e', "DOMAIN=${featureDomain()}".toString(), '-e', "OWNER=$owner".toString(),
                    '-v', "${dir.absolutePath}:/out".toString(), compileImageName(), '-c', DEV_CERT_SCRIPT])
        dir
    }

    private static final String DEV_CERT_INSTALL_SCRIPT = '''
set -e
C=/opt/zfin/tls/certs; K=/opt/zfin/tls/private; S=/opt/apache/apache-tomcat/conf
mkdir -p "$C" "$K"
cp /devcert/zfin.org.crt "$C/zfin.org.crt"
cp /devcert/zfin.org.key "$K/zfin.org.key"
rm -f "$C/zfin.org.p12" "$S/keystore"
openssl pkcs12 -export -name tomcat -in "$C/zfin.org.crt" -inkey "$K/zfin.org.key" \\
  -password pass:changeit -out "$C/zfin.org.p12"
keytool -importkeystore -noprompt -destkeystore "$S/keystore" -srckeystore "$C/zfin.org.p12" \\
  -srcstoretype pkcs12 -alias tomcat -srcstorepass changeit -deststorepass changeit >/dev/null 2>&1
chown -R 1000:1000 /opt/zfin/tls "$S/keystore"
chmod 600 "$K/zfin.org.key" "$C/zfin.org.p12"
chmod 644 "$C/zfin.org.crt" "$S/keystore"
'''

    /** Install the host certificate into `project`'s tls_certs (httpd) and keystore (tomcat)
     *  volumes, replacing what is there, as the same files generate_base.sh would write. A
     *  missing volume is created with compose's labels so compose adopts it; mounting it into
     *  the compile image first seeds it with that image's content, as compose would. Running
     *  containers read the files at start: restart httpd and tomcat to pick them up. */
    boolean installDevCert(String project) {
        def dir = ensureDevCert()
        ['tls_certs', 'keystore'].each { vn ->
            def vol = "${project}_${vn}".toString()
            if (!volumeExists(vol))
                runQuietly(['docker', 'volume', 'create', '--label', "com.docker.compose.project=$project",
                            '--label', "com.docker.compose.volume=$vn", vol])
        }
        runCommand(['docker', 'run', '--rm', '-u', '0', '--entrypoint', 'bash',
                    '-v', "${project}_tls_certs:/opt/zfin/tls".toString(),
                    '-v', "${project}_keystore:/opt/apache/apache-tomcat/conf".toString(),
                    '-v', "${dir.absolutePath}:/devcert:ro".toString(), compileImageName(), '-c', DEV_CERT_INSTALL_SCRIPT],
                   [check: false]) == 0
    }

    /** Make sure `project`'s build-cache volumes exist and belong to the compile container's
     *  user. Images built before they created /home/gradle/.npm and .m2 have nothing at those
     *  mount points, so Docker makes a fresh volume root-owned and the first npm install fails
     *  with EACCES. Creates each missing volume with compose's labels (so compose adopts it)
     *  and hands it to uid 1000; a volume restored from a seed is left as it is. */
    void prepareCacheVolumes(String project) {
        StackConfig.CACHE_VOLS.each { vn ->
            def vol = "${project}_${vn}".toString()
            if (volumeExists(vol)) {
                // Only an EMPTY volume is ours to fix; one with content has an owner already.
                def empty = runQuietly(['docker', 'run', '--rm', '-u', '0', '--entrypoint', 'sh', '-v', "${vol}:/v",
                                        tarImage(), '-c', '[ -z "$(ls -A /v)" ]']) == 0
                if (!empty) return
            } else {
                runQuietly(['docker', 'volume', 'create', '--label', "com.docker.compose.project=$project",
                            '--label', "com.docker.compose.volume=$vn", vol])
            }
            runQuietly(['docker', 'run', '--rm', '-u', '0', '--entrypoint', 'chown', '-v', "${vol}:/v",
                        tarImage(), '1000:1000', '/v'])
        }
    }

    /** Remove every volume labelled with `project`. `down -v` only removes volumes of services
     *  in the ACTIVE profile set, so a profile-gated service's volume (claude_home, behind
     *  profiles: [claude]) outlives it. Returns what was removed. */
    List<String> sweepProjectVolumes(String project) {
        def vols = captureOutput(['docker', 'volume', 'ls', '-q', '--filter',
                                  "label=com.docker.compose.project=$project"]).readLines().findAll { it }
        if (vols) {
            info("removing ${vols.size()} volume(s) `down -v` left behind: ${vols.join(', ')}")
            vols.each { runQuietly(['docker', 'volume', 'rm', it]) }
        }
        vols
    }

    /** Hand a tree back to the invoking user before deleting it. Things the compile container
     *  wrote into it -- node_modules, build/ -- belong to the container's `gradle` user, which on
     *  Linux is uid 1000 on the host too, so anyone else gets "Permission denied" halfway through
     *  `git worktree remove`. Docker Desktop maps ownership, so this finds nothing there. Tests
     *  the condition rather than the platform; `head -1` keeps the common case cheap. */
    void reclaimOwnership(File dir) {
        def uid = captureOutput(['id', '-u'])?.trim()
        def gid = captureOutput(['id', '-g'])?.trim()
        if (!uid || !gid) return
        def foreign = captureOutput(['sh', '-c',
                "find '${dir.absolutePath}' -maxdepth 3 ! -uid ${uid} -print 2>/dev/null | head -1"])?.trim()
        if (!foreign) return
        info("reclaiming container-owned files (e.g. ${foreign}) -- chown -> ${uid}:${gid}")
        runQuietly(['docker', 'run', '--rm', '-u', '0', '-v', "${dir.absolutePath}:/wt",
                    '--entrypoint', 'chown', tarImage(), '-R', "${uid}:${gid}", '/wt'])
    }

    // --- how a feature stack is reached -----------------------------------------------------
    // The repo runs no proxy. A stack publishes httpd on its own ports (8080+N / 8443+N), and
    // when docker/.env names an outside nginx-proxy's network it also joins that network and
    // advertises its hostname there. Resolution for all three: docker/.env -> environment ->
    // default, like every other host setting.

    /** The domain feature hostnames live under: <slug>.<domain>. */
    String featureDomain() { setting('ZFIN_FEATURE_DOMAIN', StackConfig.FEATURE_DOMAIN_DEFAULT) }
    String featureHost(String slug) { "${slug}.${featureDomain()}" }

    /** The address a feature stack publishes its ports on. Loopback unless the host says
     *  otherwise: a stack serves a real database, so reachable-from-the-network is a choice. */
    String featureBind() { setting('ZFIN_FEATURE_BIND', '127.0.0.1') }

    /** The external Docker network an outside nginx-proxy watches, or null to route nothing. */
    String proxyNetwork() { setting('ZFIN_PROXY_NETWORK', '') ?: (ProxyStack.running(this) ? ProxyStack.NETWORK : null) }

    /** The running nginx-proxy containers on this host, by name, each with the networks it is
     *  on. Recognised by image (`.../nginx-proxy`) or by the label acme-companion finds its
     *  nginx by, so a proxy built from some other image is missed -- which is why callers only
     *  warn. */
    Map<String, List<String>> runningProxies() {
        def rows = captureOutput(['docker', 'ps', '--format', '{{.Names}}\t{{.Image}}\t{{.Labels}}'])?.readLines() ?: []
        rows.collect { it.split('\t', 3) as List }.findAll { r ->
            r.size() >= 2 && (r[1] ==~ /(.*\/)?nginx-proxy(:.*)?/ ||
                    (r.size() > 2 && (r[2].contains('com.github.nginx-proxy.nginx=') ||
                                      r[2].contains('com.github.jrcs.letsencrypt_nginx_proxy_companion.nginx_proxy='))))
        }.collectEntries { r ->
            [r[0], (captureOutput(['docker', 'inspect', r[0], '--format',
                                   '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}']) ?: '').tokenize()]
        }
    }

    /** Why no proxy will route a stack that joins `net`, or null when a running nginx-proxy is
     *  on it. Joining a network the proxy is not on fails quietly: the name resolves and TLS
     *  works, and every request is a 502 from the proxy. */
    String proxyReachProblem(String net) {
        def proxies = runningProxies()
        if (!proxies)
            return "no running nginx-proxy found, so nothing routes stacks on $net.\n" +
                   "   (A proxy built from another image is not recognised; if yours is, ignore this.)"
        if (proxies.any { name, nets -> net in nets }) return null
        def first = proxies.keySet().first()
        "the proxy is not on $net, which this stack joins, so it answers 502 Bad Gateway:\n" +
        proxies.collect { name, nets -> "     $name is on ${nets.join(', ') ?: 'no network'}" }.join('\n') + "\n" +
        "   Connect it:  docker network connect $net $first\n" +
        "   then z restart httpd: nginx-proxy rereads its containers only when one starts or stops.\n" +
        "   To keep it across a recreate, add $net to that proxy's compose networks."
    }

    /** How a stack is reached, from its own .env:
     *  [direct: https://<bind>:<port>  (its published httpd port, when it has one),
     *   vhost : https://<host>          (when it advertises one to an outside proxy: a checkout's
     *                                    own stack, or one of ours that joined a proxy network),
     *   primary: vhost ?: direct]. A 0.0.0.0 bind is shown as localhost, the address a browser
     *  on this machine can use. */
    Map stackUrls(File envF) {
        def pub = envField(envF, 'DOCKER_HTTPD_HTTPS_PORT')
        def direct = null
        if (pub ==~ /.+:\d+/) {
            def addr = pub.substring(0, pub.lastIndexOf(':'))
            def port = pub.substring(pub.lastIndexOf(':') + 1)
            direct = "https://${addr in ['0.0.0.0', ''] ? 'localhost' : addr}:${port}".toString()
        }
        // Ours (the feature overlay) advertise nothing unless the proxy-network overlay follows.
        def overlays = envField(envF, 'ZFIN_COMPOSE_OVERLAYS').tokenize(':')
        def advertises = !overlays.any { it.contains('overlay-feature') } || overlays.any { it.contains('proxy-network') }
        def vh = advertises ? envField(envF, 'DOCKER_VIRTUAL_HOST') : null
        def vhost = vh ? "https://$vh".toString() : null
        [direct: direct, vhost: vhost, primary: vhost ?: direct]
    }

    /** Print a command's own file header (its leading `//` comment block) as --help text.
     *  Pass the command instance; reads its .groovy source (works for gcl-loaded classes). */
    void printHeader(cmd) {
        // By class name first: a class compiled because another one referenced it (Seed ->
        // SeedBuild) shares that class's code source, which would print the wrong header.
        def byName = new File(LIB, cmd.getClass().simpleName + '.groovy')
        def f = byName.isFile() ? byName : new File(cmd.getClass().protectionDomain.codeSource.location.toURI())
        println f.readLines()
                 .takeWhile { it.startsWith('//') || it.startsWith('#!') || it.trim().isEmpty() }
                 .findAll { it.startsWith('//') }
                 .collect { it.replaceFirst('^// ?', '') }
                 .join('\n')
    }

    /** -h/--help guard: if requested, print the command's header and return true (so the
     *  caller can `if (zfinUtil.helpRequested(args, this)) return`). */
    boolean helpRequested(List args, cmd) {
        if (args.any { it in ['-h', '--help'] }) { printHeader(cmd); return true }
        false
    }

    /** The canonical tar-capable container: the compile image (GNU tar, runs as root, always
     *  local, no Docker Hub pull). One source both the warm-restore and the capture use.
     *  Reads ZFIN_RELEASE from docker/.env (falling back to the environment). */
    /** Image used to run tar for volume capture/restore: GNU tar, root-runnable, already local.
     *  Overridable ($ZFIN_TAR_IMAGE) to pin it, or to try one with a faster compressor before
     *  committing the change to the base image. */
    String tarImage() { setting('ZFIN_TAR_IMAGE') ?: StackConfig.compileImage(env('ZFIN_RELEASE'), env('DOCKER_ARCH', '')) }

    /** This host's db image, named the way compose names it: DOCKER_DB_ARCH when declared --
     *  EMPTY is meaningful there (the bare amd64 tag), so presence is tested, not truthiness --
     *  else DOCKER_ARCH. */
    String dbImage() {
        def d = dotenv()
        def arch = d.containsKey('DOCKER_DB_ARCH') ? d['DOCKER_DB_ARCH']
                 : System.getenv().containsKey('DOCKER_DB_ARCH') ? System.getenv('DOCKER_DB_ARCH')
                 : env('DOCKER_ARCH', '')
        StackConfig.image('db', env('ZFIN_RELEASE'), arch)
    }

    /** The db image `project` actually runs (its db container's), or null without one. */
    String projectDbImage(String project) {
        captureOutput(['docker', 'ps', '-a', '--filter', "label=com.docker.compose.project=$project",
                       '--filter', 'label=com.docker.compose.service=db', '--format', '{{.Image}}'])
                .readLines().find { it } ?: null
    }

    /** An image's platform, `os/arch` (linux/amd64), or null when it is not present locally. */
    String imagePlatform(String ref) {
        captureOutput(['docker', 'image', 'inspect', ref, '--format', '{{.Os}}/{{.Architecture}}'])?.trim() ?: null
    }

    /** The platform of the postgres that wrote `project`'s pg_data: its db container's image.
     *  This, not the daemon's platform, is what a data directory's portability depends on --
     *  an arm64 Mac running the amd64 image under Rosetta writes an amd64 data directory. Falls
     *  back to this host's db image when the project has no db container. */
    String dataPlatform(String project) {
        def img = projectDbImage(project)
        (img ? imagePlatform(img) : null) ?: imagePlatform(dbImage()) ?: dockerPlatform()
    }

    // Connect the shared `zfin_shared` db/solr containers INTO the given feature project's
    // default network (`<project>_default`) with aliases db/solr, so a --shared-db feature's
    // app tier resolves `db`/`solr` to the shared containers. This is how a --shared-db
    // feature reaches shared data WITHOUT multi-homing its own tomcat -- catalina sets
    // `-Djava.rmi.server.hostname=$(container ip)`, and a two-network tomcat expands that to
    // two IPs, the second leaking in as a bare java arg (fatal "Could not find or load main
    // class 172.x"). Postgres/solr don't care about being on several networks, so we attach
    // THEM to the feature's network instead. Idempotent: skips a container already joined.
    // The network must already exist (compose creates it on up / up --no-start).
    void connectSharedData(String project) {
        def net = "${project}_default".toString()   // String, not GString: List.contains below
        def src = sharedProject()
        StackConfig.DATA_SERVICES.each { svc ->
            def cid = captureOutput(['docker', 'ps', '-q',
                '--filter', "label=com.docker.compose.project=$src",
                '--filter', "label=com.docker.compose.service=$svc"])
            // Loud, on stderr: a missing shared service means this stack's `db`/`solr` will
            // not resolve and the webapp fails at runtime with confusing connection errors.
            // As an info() line in the middle of provisioning output this went unnoticed.
            if (!cid) {
                System.err.println("!! shared $svc is NOT running -- '$net' will have no '$svc' alias, " +
                                   "so this stack cannot reach it. Fix with:  z shared up")
                return
            }
            def nets = captureOutput(['docker', 'inspect', cid, '--format',
                '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}']).split() as List
            if (nets.contains(net)) return
            info("connect shared $svc -> $net (alias $svc)")
            // check:false: a redundant connect (already joined) errors harmlessly.
            runCommand(['docker', 'network', 'connect', '--alias', svc, net, cid], [check: false])
        }
    }
}
