#!/usr/bin/env groovy
// zbuild -- hands-free, phased build/deploy orchestrator for a stack.
//
// Non-interactive: the engine behind GoCD/CI *and* behind z-fresh-install. It runs
// the same sequence the GoCD "Trunk" pipeline does, but as versioned, testable Groovy
// instead of ~30 tasks of shell embedded in pipeline JSON. Keep it PHASED (not one
// monolith) so a CI pipeline can still map stage->phase and keep parallelism/gating.
//
// It targets a stack purely through the compose environment
// (COMPOSE_PROJECT_NAME / COMPOSE_FILE / COMPOSE_ENV_FILES) -- set by z's cwd
// auto-detection, by GoCD environment_variables, or (for COMPOSE_FILE only) defaulted
// here to the base docker-compose.yml so it also works from a bare checkout.
//
// Usage:
//   z build <phase> [<phase>...] [--build] [--test]
//   z build all [--build] [--test]        # every phase, in order
//
// Phases (mirror the GoCD Trunk pipeline; each maps to one GoCD stage):
//   configure       (--build ? build : pull) images; init volumes (cert/props via
//                   the compile login shell); ant do
//   load-db         up db; gradle loaddb && make && liquibasePreBuild && liquibasePostBuild
//   load-solr       up solr; wait for the core; gradle getLatestSolrIndex
//   deploy-jenkins  ant deploy-jobs && deploy-plugins; (re)start jenkins
//   deploy          build WAR; ant deploy-catalina-base && deploy-without-tests-and-tomcat-restart;
//                   (re)start httpd/mailpit/tomcat; (--test ? gradle test non+smoke)
//
// Flags:  --build  build stock images in `configure` (default: pull from ghcr.io)
//         --pull-missing  in `configure`, pull only images this host does not have. Tags are
//                  host-wide, so a plain pull replaces a locally built image for EVERY stack
//                  here, not just the one being configured.
//         --dump PATH      in `load-db`, load this dump (a path INSIDE the container, under
//                  /opt/zfin/unloads/db) instead of the one loaddb picks -- `gradle loaddb -DB=`.
//         --snapshot PATH  in `load-solr`, restore this snapshot.* dir (inside the container,
//                  under /opt/zfin/unloads/solr) -- `gradle getLatestSolrIndex -DsolrDump=`.
//         --test   run the smoke/non-smoke test tasks in `deploy`

class Zbuild {
    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return
        def die = zfinUtil.&die; def info = zfinUtil.&info
        def DOCKER = zfinUtil.DOCKER

        // Last-resort COMPOSE_FILE for children: GoCD env_vars or z's cwd-based auto-detection
        // normally provide the full COMPOSE_PROJECT_NAME/FILE/ENV_FILES, so this only fires for a
        // checkout whose docker/.env names no project. childEnv is injected into
        // every process zfinUtil.runCommand spawns (compose + zc).
        if (!zfinUtil.stackVar('COMPOSE_FILE')) zfinUtil.childEnv['COMPOSE_FILE'] = new File(DOCKER, 'docker-compose.yml').absolutePath

        // sh, not a bare runCommand(): inside these closures an unqualified call resolves against
        // Zbuild (which has no such method), so every phase died with MissingMethodException on
        // its first docker call.
        def sh = zfinUtil.&runCommand
        def compose = { Object... a -> sh(['docker', 'compose'] + (a as List)) }
        // lifecycle: up/stop/down/build/pull
        def zc = { String script -> sh(['docker', 'compose', 'run', '--rm', StackConfig.BUILD_SERVICE, 'bash', '-l', '-c', script]) }
        // run in the compile container (login shell)

        // Run a sequence ONE COMMAND PER CONTAINER, announcing each. A phase used to hand the
        // whole chain to one shell -- `gradle make && ant deploy-catalina-base && ant
        // deploy-without-tests-and-tomcat-restart` -- so a failure anywhere reported the entire string and
        // left you to work out which link broke, from ant output that does not say. The extra
        // container per step costs a few seconds against phases that run for minutes, and it
        // buys a failure that names itself. Stops at the first failure, like `&&` did.
        def zcSteps = { String phase, List<String> cmds ->
            cmds.eachWithIndex { c, i ->
                zfinUtil.info("${phase} [${i + 1}/${cmds.size()}]: ${c}")
                def t0 = System.currentTimeMillis()
                // check:false so the failure is reported HERE, naming the step. runCommand's own
                // failure path calls die(), which exits the JVM -- catching it is not an option.
                def code = sh(['docker', 'compose', 'run', '--rm', StackConfig.BUILD_SERVICE,
                               'bash', '-l', '-c', c], [check: false])
                if (code != 0) zfinUtil.die(
                    "${phase} failed at step ${i + 1} of ${cmds.size()}: ${c}\n" +
                    "   Earlier steps in this phase succeeded. Re-run just this one with:\n" +
                    "     z run -c \"${c}\"", code)
                zfinUtil.info(String.format("${phase} [${i + 1}/${cmds.size()}] ok (%.1fs)",
                                            (System.currentTimeMillis() - t0) / 1000.0))
            }
        }

        def buildImages = false
        def pullMissing = false
        def dump = null, snapshot = null
        def runTests = false
        def requested = []
        def argv = args as List
        // Flags that take a value, pulled out first so the loop below stays one-word-per-case.
        ['--dump', '--snapshot'].each { f ->
            def i = argv.indexOf(f)
            if (i >= 0) {
                if (i + 1 >= argv.size()) die("$f needs a path", 2)
                if (f == '--dump') dump = argv[i + 1] else snapshot = argv[i + 1]
                argv = argv.take(i) + argv.drop(i + 2)
            }
        }
        argv.each { a ->
            switch (a) {
                case '--build': buildImages = true; break
                case '--pull-missing': pullMissing = true; break
                case '--test': runTests = true; break
                case '-h': case '--help':
                    println new File(getClass().protectionDomain.codeSource.location.toURI()).readLines()
                            .findAll { it.startsWith('//') }.collect { it.replaceFirst('// ?', '') }.join('\n')
                    System.exit(0)
                default:
                    if (a.startsWith('-')) die("unknown flag: $a", 2)
                    requested << a
            }
        }

        def STOCK = ['base', 'compile', 'db', 'solr', 'httpd', 'tomcat', 'jenkins', 'fail2ban']

        def PHASES = [:]
        PHASES['configure'] = {
            if (buildImages) {
                // Build `base` FIRST, on its own, so it's tagged locally before compile/jenkins
                // resolve `FROM ghcr.io/zfin/zfin-base:...`. In a single combined `compose build`,
                // buildx resolves that FROM from the REGISTRY (amd64) -- concurrently with base
                // building -- instead of the local native base, yielding amd64 compile/jenkins on
                // an arm64 host. Base-first makes their FROM pick up the local (native) base.
                info('configure: build base image (native, for the FROM chain)')
                compose('build', 'base')
                info('configure: build the remaining stock images')
                compose(['build'] + (STOCK - 'base') as Object[])
            } else {
                info(pullMissing ? 'configure: pull stock images this host does not have (--pull-missing)'
                                 : 'configure: pull stock images')
                compose(['pull'] + (pullMissing ? ['--policy', 'missing'] : []) + STOCK as Object[])
            }
            info('configure: init volumes (cert + zfin.properties via the compile login shell)')
            zc('true')          // bash -l sources .profile -> generates cert/keystore + zfin.properties
            info('configure: ant do')
            zc('ant do')
        }
        PHASES['load-db'] = {
            info('load-db: up db + loaddb/make/liquibase')
            compose('up', '-d', 'db')
            zcSteps('load-db', ["gradle loaddb${dump ? " -DB='$dump'" : ''}".toString(), 'gradle make',
                                'gradle liquibasePreBuild', 'gradle liquibasePostBuild'])
        }
        PHASES['load-solr'] = {
            info('load-solr: up solr, wait for core, getLatestSolrIndex')
            compose('up', '-d', 'solr')
            // Poll, THEN re-check: the loop's last command is `sleep`, so without the final
            // ping the whole thing exits 0 even if the core never came up -- and gradle
            // getLatestSolrIndex would then run against a not-ready core (a confusing downstream
            // failure instead of a clean "solr timed out"). Triple-SINGLE quotes so Groovy leaves
            // $i / ${CORE} for bash. (zc dies on the nonzero exit.)
            zc('''
      for i in $(seq 1 60); do
        curl -sf -o /dev/null "http://solr:8983/solr/${CORE:-site_index}/admin/ping" && break
        echo "waiting for solr core ($i/60)"; sleep 5
      done
      if ! curl -sf -o /dev/null "http://solr:8983/solr/${CORE:-site_index}/admin/ping"; then
        echo "solr core did not respond after ~300s" >&2; exit 1
      fi
    '''.stripIndent())
            zc("gradle getLatestSolrIndex${snapshot ? " -DsolrDump='$snapshot'" : ''}".toString())
        }
        PHASES['deploy-jenkins'] = {
            info('deploy-jenkins: deploy jobs + plugins, (re)start jenkins')
            zc('ant deploy-jobs')
            zc('ant deploy-plugins')
            compose('up', '-d', 'jenkins')
        }
        PHASES['deploy'] = {
            info('deploy: build WAR, deploy catalina-base + app, (re)start app tier')
            compose('stop', 'httpd', 'tomcat')
            // deploy-without-tests-and-tomcat-restart skips the restart, despite reading as if it
            // does one: it is deploy-without-tests minus `restart`. The stop and up either side
            // of this line restart the app tier from the host, so a restart from inside compile
            // would only be redundant.
            zcSteps('deploy', ['gradle make', 'ant deploy-catalina-base',
                               'ant deploy-without-tests-and-tomcat-restart'])
            compose('up', '-d', 'httpd', 'mailpit', 'tomcat')
            if (runTests) {
                info('deploy: tests')
                zc('gradle test -PnonSmokeTests')
                zc('gradle test -PsmokeTests')
            }
        }
        def ORDER = ['configure', 'load-db', 'load-solr', 'deploy-jenkins', 'deploy']

        if (!requested) die("no phase given. Phases: ${ORDER.join(', ')} (or 'all'). See --help.", 2)
        def toRun = requested == ['all'] ? ORDER : requested
        toRun.each { if (!PHASES[it]) die("unknown phase '$it'. Phases: ${ORDER.join(', ')} (or 'all').", 2) }

        def proj = zfinUtil.stackVar('COMPOSE_PROJECT_NAME') ?: '(compose default)'
        info("stack=${proj}  phases=${toRun.join(' -> ')}${buildImages ? '  [build]' : ''}${runTests ? '  [test]' : ''}")
        toRun.each { PHASES[it]() }
        info("done: ${toRun.join(', ')}")
    }
}
