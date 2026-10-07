// ProxyStack -- `z proxy`: the reverse proxy that serves every feature stack by name.
//
//   z proxy up                     start it (compose/proxy.yml), then check the names resolve
//   z proxy down                   stop it; stacks stay reachable on their published ports
//   z proxy status                 is it up, which stacks it routes, do names resolve
//   z proxy attach <ticket>|--all  route a stack made before the proxy ran (recreates httpd)
//
// With it up, https://<slug>.<ZFIN_FEATURE_DOMAIN> reaches a stack: names resolve to the proxy
// (dnsmasq; `up` checks and says how), the proxy terminates TLS with the host development
// certificate (z cert), and forwards over https to the stack's httpd on a shared network.
// Listens on ZFIN_FEATURE_BIND, ports ZFIN_PROXY_HTTP_PORT/ZFIN_PROXY_HTTPS_PORT (80/443).
//
// Only for a host without a proxy of its own. Where one runs, set ZFIN_PROXY_NETWORK to its
// network and leave this down: see compose/proxy.yml for why the two must not coexist.
class ProxyStack {
    static final String PROJECT = 'zfin_proxy'
    static final String NETWORK = 'zfin_proxy'

    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return
        def die = zfinUtil.&die; def info = zfinUtil.&info
        def sub = args ? args[0] : 'status'
        switch (sub) {
            case 'up':     up(zfinUtil); break
            case 'down':   zfinUtil.runCommand(compose(zfinUtil) + ['down'], [check: false]); break
            case 'status': status(zfinUtil); break
            case 'attach': attach(args.drop(1), zfinUtil); break
            default: die("z proxy: unknown '$sub' (up|down|status|attach)", 2)
        }
    }

    private List<String> compose(ZfinUtil zfinUtil) {
        def bind = zfinUtil.featureBind()
        zfinUtil.childEnv['ZFIN_PROXY_BIND'] = bind
        zfinUtil.childEnv['ZFIN_PROXY_HTTP_PORT'] = zfinUtil.setting('ZFIN_PROXY_HTTP_PORT', '80')
        zfinUtil.childEnv['ZFIN_PROXY_HTTPS_PORT'] = zfinUtil.setting('ZFIN_PROXY_HTTPS_PORT', '443')
        zfinUtil.childEnv['ZFIN_PROXY_CERT_DIR'] = zfinUtil.devCertDir().absolutePath
        zfinUtil.childEnv['ZFIN_FEATURE_DOMAIN'] = zfinUtil.featureDomain()
        ['docker', 'compose', '-p', PROJECT, '-f', new File(zfinUtil.COMPOSE, 'proxy.yml').absolutePath]
    }

    static boolean running(ZfinUtil zfinUtil) {
        zfinUtil.captureOutput(['docker', 'ps', '-q', '--filter', "label=com.docker.compose.project=$PROJECT"])
    }

    private void up(ZfinUtil zfinUtil) {
        def other = zfinUtil.setting('ZFIN_PROXY_NETWORK')
        if (other && other != NETWORK)
            zfinUtil.die("this host already names a proxy network (ZFIN_PROXY_NETWORK=$other), and stacks join that one.\n" +
                         "   Two proxies on one Docker socket would both claim every stack. To use this one instead:\n" +
                         "     z config unset ZFIN_PROXY_NETWORK   (or --user), then z proxy up")
        zfinUtil.ensureDevCert()
        zfinUtil.runCommand(compose(zfinUtil) + ['up', '-d'])
        def https = zfinUtil.setting('ZFIN_PROXY_HTTPS_PORT', '443')
        zfinUtil.info("proxy up: https://<slug>.${zfinUtil.featureDomain()}${https == '443' ? '' : ":$https"}/")
        zfinUtil.info("new feature stacks join it; one made earlier joins with:  z proxy attach <ticket>  (or --all)")
        dnsCheck(zfinUtil)
    }

    private void status(ZfinUtil zfinUtil) {
        def other = zfinUtil.setting('ZFIN_PROXY_NETWORK')
        if (other && other != NETWORK) println "stacks join the host's own proxy network: $other (ZFIN_PROXY_NETWORK)"
        println "z proxy : ${running(zfinUtil) ? 'up' : 'down'}"
        if (zfinUtil.runQuietly(['docker', 'network', 'inspect', NETWORK]) == 0) {
            def members = zfinUtil.captureOutput(['docker', 'network', 'inspect', NETWORK, '--format',
                    '{{range .Containers}}{{.Name}} {{end}}']).split().findAll { it && !it.startsWith("${PROJECT}-") }
            println "routes  : ${members ? members.join(', ') : '(no stacks attached)'}"
        }
        dnsCheck(zfinUtil)
    }

    /** Does a name under the feature domain resolve to where the proxy listens? */
    private void dnsCheck(ZfinUtil zfinUtil) {
        def domain = zfinUtil.featureDomain()
        def bind = zfinUtil.featureBind()
        def want = bind in ['0.0.0.0', ''] ? '127.0.0.1' : bind
        def probe = "dns-check.${domain}".toString()
        def got = null
        try { got = InetAddress.getByName(probe).hostAddress } catch (UnknownHostException ignored) { }
        if (got == want) { println "names   : *.${domain} -> ${want}"; return }
        System.err.println("!! ${probe} resolves to ${got ?: 'nothing'}, not ${want}: stack names will not reach the proxy.")
        System.err.println("""   Send *.${domain} to ${want} with dnsmasq, once per host. On macOS:
     brew install dnsmasq
     echo 'address=/${domain}/${want}' >> \$(brew --prefix)/etc/dnsmasq.conf
     sudo brew services restart dnsmasq
     sudo mkdir -p /etc/resolver && echo 'nameserver 127.0.0.1' | sudo tee /etc/resolver/${domain}
   On Linux, the same `address=` line in your dnsmasq (or NetworkManager's) configuration.""")
    }

    private void attach(List args, ZfinUtil zfinUtil) {
        def net = zfinUtil.proxyNetwork()
        if (!net) zfinUtil.die("no proxy to attach to: start one (z proxy up), or set ZFIN_PROXY_NETWORK to the host's own")
        def stacks = zfinUtil.featureStacks()
        if (!args.contains('--all')) {
            def wanted = (args ?: [zfinUtil.featureSlugFromCwd()]).findAll { it }*.toLowerCase()
            if (!wanted) zfinUtil.die("usage: z proxy attach <ticket>|--all  (or run it inside a feature worktree)", 2)
            wanted.each { zfinUtil.requireFeature(it) }
            stacks = stacks.findAll { it.slug in wanted }
        }
        stacks.each { st ->
            def slug = st.slug
            def wt = new File(zfinUtil.worktreesDir(), st.worktree)
            def envF = new File(wt, 'docker/.env')
            def spec = zfinUtil.stackSpec(wt)
            if (!spec) { println "  ${slug}: no readable docker/.env -- skipped"; return }
            if (spec.overlays.any { it.contains('proxy-network') }) { println "  ${slug}: already routed"; return }
            def overlays = (spec.overlays - zfinUtil.worktreeOverlay(wt)) + ['docker-compose.overlay-proxy-network.yml']
            def lines = envF.readLines().findAll { !it.startsWith('ZFIN_COMPOSE_OVERLAYS=') && !it.startsWith('ZFIN_PROXY_NETWORK=') }
            lines << "ZFIN_COMPOSE_OVERLAYS=${overlays.join(':')}".toString()
            lines << "ZFIN_PROXY_NETWORK=${net}".toString()
            envF.text = lines.join('\n') + '\n'
            def httpdUp = zfinUtil.captureOutput(['docker', 'ps', '-q', '--filter', "label=com.docker.compose.project=${spec.project}",
                                                  '--filter', 'label=com.docker.compose.service=httpd'])
            if (httpdUp) {
                def fresh = zfinUtil.stackSpec(wt)
                def cmd = ['docker', 'compose', '-p', fresh.project, '--env-file', envF.absolutePath] +
                          fresh.compose.tokenize(':').collectMany { ['-f', it] } + ['up', '-d', '--no-deps', 'httpd']
                zfinUtil.runCommand(cmd, [check: false])
            }
            println "  ${slug}: routed as https://${zfinUtil.envField(envF, 'DOCKER_VIRTUAL_HOST')}" +
                    (httpdUp ? '' : '  (httpd is down; takes effect on its next start)')
        }
    }
}
