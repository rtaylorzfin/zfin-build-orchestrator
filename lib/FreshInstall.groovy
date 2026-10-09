#!/usr/bin/env groovy
// z fresh-install -- guided day-zero setup on a BARE workstation (nothing in Docker yet).
//
// Interactive. Run it DIRECTLY from a checkout:
//   z fresh-install [--dry-run]     (from inside the ZFIN checkout to set up)
//
// Steps:
//   1. verify the machine is ZFIN-fresh (no ZFIN volumes / images / containers)
//   2. ask: build stock images locally, or pull from ghcr.io
//   3. check the init inputs exist (db dump, solr dump; optional bowtie/blast/loadup)
//   4. ask for an optional first ticket (e.g. ZFIN-789)
//   5. drive the existing tools to stand up a loaded base stack, and optionally a feature:
//        z build all -> [z seed create + z feature new]
//
// --dry-run runs the checks and prints the plan (using defaults, no prompts) without
// executing the heavy build/load steps.
//
// REACHING THE STACKS. The repo runs no proxy. Every feature stack publishes httpd on its
// own port (https://127.0.0.1:8443+N), which needs nothing set up. To serve them by name
// instead, point the ZFIN_PROXY_NETWORK host setting (z config) at the network an nginx-proxy on this host
// watches; the stacks then join it and advertise <slug>.<ZFIN_FEATURE_DOMAIN>, and names
// resolving to that proxy are the host's business (a wildcard DNS record, dnsmasq, or
// /etc/hosts).

class FreshInstall {
    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return
        def die = zfinUtil.&die; def info = zfinUtil.&info; def captureOutput = zfinUtil.&captureOutput;
        def runCommand = zfinUtil.&runCommand
        def DOCKER = zfinUtil.DOCKER
        def envFile = new File(DOCKER, '.env')
        if (!envFile.exists()) die(zfinUtil.noBaseEnv())

        def dryRun = (args as List).contains('--dry-run')
        ; (args as List).findAll { it.startsWith('-') && it != '--dry-run' }.each { die("unknown flag: $it", 2) }

// read docker/.env into a map (paths come from .env only -- no ambient fallback here)
        def dotenv = zfinUtil.dotenv()

// --- 1. fresh check (scoped to ZFIN signals; ignores unrelated Docker) -----------
        info("checking the machine is ZFIN-fresh...")
        def volumes = captureOutput(['docker', 'volume', 'ls', '--format', '{{.Name}}']).readLines().findAll { it ==~ /.*_(pg_data|solr_var)$/ }
        def images = captureOutput(['docker', 'images', '--format', '{{.Repository}}:{{.Tag}}']).readLines().findAll { it.startsWith('ghcr.io/zfin/') }.unique()
        def zfinCts = captureOutput(['docker', 'ps', '-a', '--format', '{{.Names}}\t{{.Image}}']).readLines()
                .findAll { it.contains('\t') && it.split('\t')[1].contains('zfin') }
                .collect { it.split('\t')[0] }
        def found = []
        if (volumes) found << "volumes: ${volumes.join(', ')}"
        if (images) found << "images: ${images.take(6).join(', ')}${images.size() > 6 ? ' …' : ''}"
        if (zfinCts) found << "containers: ${zfinCts.join(', ')}"
        if (found) {
            System.err.println("!! this machine is NOT ZFIN-fresh -- found:")
            found.each { System.err.println("   - $it") }
            die("z fresh-install is for a clean machine. Tear those down first (or use z feature / z build directly).")
        }
        info("  fresh ✓ (no ZFIN volumes / images / containers)")

// --- 3. init inputs (paths come from docker/.env) --------------------------------
        info("checking init inputs...")
        def home = System.getProperty('user.home')
        def expandTilde = { String s -> s?.startsWith('~') ? home + s.substring(1) : s }   // Java File doesn't expand ~
        def checkPath = { String var, boolean required ->
            def p = expandTilde(dotenv[var])
            def ok = p && new File(p).exists()
            println("   ${ok ? '✓' : (required ? '✗' : '–')} ${var.padRight(38)} ${p ?: '(unset)'}${ok ? '' : (required ? '   MISSING (required)' : '   (optional, skipped)')}")
            [required: required, ok: ok, var: var]
        }
        def inputs = [
                checkPath('DOCKER_DB_UNLOADS_PATH', true),                    // db dump
                checkPath('DOCKER_SOLR_UNLOADS_PATH', true),                  // solr dump
                checkPath('DOCKER_BOWTIE_PATH', false),
                checkPath('DOCKER_ABBLAST_PATH', false),
                checkPath('DOCKER_BLASTSERVER_BLAST_DATABASE_PATH', false),
                checkPath('DOCKER_LOADUP_PATH', false),
        ]
        def missingRequired = inputs.findAll { it.required && !it.ok }
        if (missingRequired) die("missing required init input(s): ${missingRequired*.var.join(', ')} -- set them in docker/.env and re-run.")

// loaddb picks the newest-mtime entry under the db unloads and loads its last file, so an
// empty newer directory shadows the real dump and fails deep inside gradle. Catch it here,
// before the long build. ZfinUtil.newestDbDump makes the same choice loaddb does.
        def dbUnloads = expandTilde(dotenv['DOCKER_DB_UNLOADS_PATH'])
        if (dbUnloads && new File(dbUnloads).isDirectory()) {
            def pick = zfinUtil.newestDbDump(new File(dbUnloads))
            if (pick.error) die("db dump: ${pick.error}")
            println("   ✓ db dump: loaddb will use ${pick.file.parentFile.name}/${pick.file.name}")
        }

// getLatestSolrIndex (build.gradle) restores the newest snapshot.* under
// <solr-unloads>/<instance>/ -- `zfindb` by default, the layout `gradle getsolr` writes -- and
// needs Solr 9 snapshot.* dirs there (ZFIN-10171); legacy full-SOLR_HOME dumps cannot be
// restored at all. ZfinUtil.solrSnapshots reads that same path, so this check and the task
// agree about where the index lives. Checked here because `z build load-solr` runs after the
// image build and the full db load -- the most expensive possible place to find out.
        def solrUnloads = expandTilde(dotenv['DOCKER_SOLR_UNLOADS_PATH'])
        if (solrUnloads) {
            def root = new File(solrUnloads)
            def snaps = zfinUtil.solrSnapshots(root)
            if (!snaps) {
                // Name a snapshot sitting one directory off, if that is what's there -- "no
                // snapshots" reads like "you have no backup" when the truth is "wrong place".
                def misplaced = [new File(root, 'v9'), new File(root, 'zfindb/v9'), root].collectMany { d ->
                    ((d.listFiles() ?: []) as List).findAll { it.isDirectory() && it.name.startsWith('snapshot.') }
                }
                def want = new File(root, 'zfindb')
                die("solr dump: no snapshot.* dir under ${want} (Solr 9 format).\n" +
                        (misplaced ? "   Found ${misplaced[0]} instead. getLatestSolrIndex reads ${want}/ -- move it:\n" +
                                     "     mkdir -p ${want} && mv ${misplaced.max { it.lastModified() }} ${want}/\n"
                                   : "   getLatestSolrIndex needs ${want}/snapshot.<ts>/ -- not a legacy\n" +
                                     "   full-SOLR_HOME dump. Fetch one (gradle getsolr), then re-run.\n"))
            }
            println("   ✓ solr dump: getLatestSolrIndex will use zfindb/${snaps[0].name}")
        }

// --- 2 & 4. choices (prompt interactively; dry-run uses defaults) ----------------
        def project = dotenv['COMPOSE_PROJECT_NAME'] ?: StackConfig.BASE_PROJECT
        def host = dotenv['DOCKER_VIRTUAL_HOST'] ?: 'zfin.org'
        def tag = 'dev'
        def buildImages = false
        def firstTicket = null

        if (dryRun) {
            info("--dry-run: using defaults (pull images; no first ticket)")
        } else {
            def con = System.console()
            if (!con) die("no TTY -- run z fresh-install from a terminal (or --dry-run to preview)")
            def imgChoice = con.readLine("Images -- (b)uild locally or (p)ull from ghcr.io? [p]: ")?.trim()?.toLowerCase()
            buildImages = imgChoice?.startsWith('b')
            firstTicket = con.readLine("First ticket to start a feature stack (blank = base stack only): ")?.trim() ?: null
            def go = con.readLine("Proceed to set up '$project' (${buildImages ? 'build' : 'pull'} images, full load)${firstTicket ? " + feature $firstTicket" : ''}? [y/N]: ")?.trim()?.toLowerCase()
            if (!go?.startsWith('y')) die("aborted.", 0)
        }

// --- 5. the plan (everything routes through the single front door `z`) -----------
        def zExe = new File(zfinUtil.HOME, 'z').absolutePath
        def baseCompose = new File(DOCKER, 'docker-compose.yml').absolutePath

        def plan = []
        plan << [zExe, 'build', 'all'] + (buildImages ? ['--build'] : [])
        if (firstTicket) {
            // A feature stack needs a seed to restore from -- captured from the base stack `build all` just deployed, so
            // the feature comes up already serving. -y: this run is unattended from here on.
            plan << [zExe, 'seed', 'create', '--from', project, '--tag', tag]
            plan << [zExe, 'feature', 'new', firstTicket, '-y', '--seed', tag, '--up']
        }

        println "\nPlan:"
        plan.each { println "  \$ ${it.join(' ')}" }

        if (dryRun) {
            info("dry-run: not executing."); return
        }

// A reused box may carry stale cross-arch layers that make `docker compose build` emit
// mixed-arch images (e.g. an amd64 `compile` on an arm64 host -> emulation). Prune so the
// build is clean + native -- but ONLY when we're actually building: if the user chose to
// pull, there's nothing to build and no reason to wipe the whole machine's build cache.
        if (buildImages) {
            info("pruning docker build cache (clean, native-arch image builds)")
            new ProcessBuilder('docker', 'builder', 'prune', '-af').inheritIO().start().waitFor()
        }

        println ""
// The base stack's compose env for `z build` (so it targets '$project', not the compose-dir
// default). z passes it through to zbuild; the other steps take their target via args.
        def baseEnv = ['COMPOSE_PROJECT_NAME': project, 'COMPOSE_FILE': baseCompose, 'COMPOSE_ENV_FILES': envFile.absolutePath]
        plan.eachWithIndex { cmd, i ->
            info("step ${i + 1}/${plan.size()}: ${cmd.join(' ')}")
            def pb = new ProcessBuilder(cmd*.toString()).inheritIO()
            if (cmd.size() > 1 && cmd[1] == 'build') baseEnv.each { k, v -> pb.environment().put(k, v) }
            def code = pb.start().waitFor()
            if (code != 0) die("step ${i + 1} failed ($code)", code)
        }
        info("fresh install complete. Put z on PATH, with tab completion, once in ~/.bashrc:")
        info("  eval \"\$(${new File(zfinUtil.HOME, 'z')} shell-init)\"     (this shell; append its output to ~/.bashrc for every shell)")
        if (firstTicket) info("...your feature: cd ${new File(zfinUtil.worktreesDir(), firstTicket.toLowerCase())} (z commands resolve it from there)")
    }
}
