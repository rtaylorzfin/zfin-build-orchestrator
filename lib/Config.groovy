// Config -- `z config`: the settings this tooling runs with on this host.
//
//   z config [ls] [-l]                  every setting: the value z uses, and where it comes from
//                                       (-l adds what each one is)
//   z config get <KEY>                  one setting's effective value
//   z config set <KEY>=<value>          save it in this dev tree's zfin-dev.env
//   z config unset <KEY>                remove it from that file (back to the default)
//   z config path                       the tree file
//
// Settings come from, in order: the process environment, the dev tree's zfin-dev.env (found by
// walking up from the working directory; the directory holding it is ZFIN_DEV_ROOT), the
// default. To run z outside any tree, export ZFIN_DEV_ROOT. Neither is a ZFIN checkout's
// docker/.env. The keys, and what each is, are StackConfig.HOST_SETTINGS.
class Config {
    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return
        def die = zfinUtil.&die; def info = zfinUtil.&info
        def known = StackConfig.HOST_SETTINGS
        def requireKnown = { String k ->
            if (!(k in known.keySet()))
                die("unknown setting '$k'. Known: ${known.keySet().join(', ')}" +
                    // The usual miss: a stack setting, which belongs in the checkout's docker/.env.
                    (k ==~ /(ZFIN_RELEASE|COMPOSE_.*|DOCKER_.*)/
                        ? "\n   $k is a stack setting, kept in the checkout's docker/.env:  z env set $k=<value>" : ''), 2)
        }
        // Keys are matched whatever their case (z config get zfin_seeds_dir); values are kept as typed.
        def canon = { String k -> known.keySet().find { it.equalsIgnoreCase(k) } ?: k }
        def rest = args
        def describe = { File f -> f ? "$f${f.isFile() ? '' : '  (not created yet)'}" : '(no dev tree here)' }

        def sub = rest ? rest[0] : 'ls'
        switch (sub) {
            case 'ls': case 'list':
                def lng = rest.drop(1).any { it in ['-l', '--long'] }
                def bad = rest.drop(1).find { !(it in ['-l', '--long']) }
                if (bad) die("z config ls: unknown arg '$bad' (-l|--long)", 2)
                println "tree file: ${describe(zfinUtil.treeConfigFile())}"
                println ""
                def w = known.keySet()*.size().max()
                known.each { k, what ->
                    def (v, note) = effective(k, zfinUtil)
                    def src = zfinUtil.settingSource(k) ?: 'default'
                    println String.format("  %-${w}s  %-7s  %s", k, src, (v ?: '-') + (note ? "  ($note)" : ''))
                    if (lng) println String.format("  %-${w}s  %-7s  %s", '', '', what)
                }
                println ""
                println "tree: set in the tree file (z config set)   env: this shell's environment, which wins"
                if (!lng) println "what each setting is:  z config ls -l"
                break
            case 'get':
                if (rest.size() != 2) die("usage: z config get <KEY>", 2)
                def gk = canon(rest[1])
                requireKnown(gk)
                def (gv, gnote) = effective(gk, zfinUtil)
                println gv ?: ''
                // The value alone on stdout, so $(z config get ...) works; at a terminal, say
                // when it is not set anywhere and z works it out.
                if (!zfinUtil.settingSource(gk) && System.console() != null)
                    System.err.println("   (not set -- the default${gnote ? ', ' + gnote : ''})")
                break
            case 'set':
                if (rest.size() != 2 || !rest[1].contains('=')) die("usage: z config set <KEY>=<value>", 2)
                def (k, v) = rest[1].split('=', 2)
                k = canon(k)
                requireKnown(k)
                if (k == 'ZFIN_DEV_ROOT')
                    die("a tree's ZFIN_DEV_ROOT is where its ${ZfinUtil.TREE_CONFIG} is, not a line in it.\n" +
                        "   Make a directory a tree with  z scaffold --root <dir>; to run z outside it,\n" +
                        "   export ZFIN_DEV_ROOT=<dir>", 2)
                zfinUtil.saveSetting(k, v)
                info("$k=$v  -> ${zfinUtil.treeConfigFile()}")
                if (System.getenv(k)) info("note: \$$k is set in this shell's environment, which overrides the file")
                break
            case 'unset':
                if (rest.size() != 2) die("usage: z config unset <KEY>", 2)
                rest = [rest[0], canon(rest[1])]
                requireKnown(rest[1])
                if (!zfinUtil.treeConfigFile()?.isFile()) die("nothing to unset: ${describe(zfinUtil.treeConfigFile())}")
                zfinUtil.saveSetting(rest[1], null)
                info("removed ${rest[1]} from ${zfinUtil.treeConfigFile()}")
                break
            case 'path':
                println "tree file: ${describe(zfinUtil.treeConfigFile())}"
                break
            default:
                die("z config: unknown '$sub' (ls|get|set|unset|path)", 2)
        }
    }

    /** The value z actually runs with for `k`, and a note on where a default comes from:
     *  [value, note]. Set values come back as they are; defaults are resolved the way the
     *  code that uses them resolves them -- without the checks that die (a broken symlink is
     *  reported where it is used, not here). A null value means "none" (no seed yet, say). */
    private static List effective(String k, ZfinUtil zfinUtil) {
        if (zfinUtil.settingSource(k)) return [zfinUtil.setting(k), null]
        def root = zfinUtil.treeRoot()?.path
        def under = { String d -> root ? ["$root/$d".toString(), null] : [null, "\$ZFIN_DEV_ROOT/$d, once there is a tree"] }
        switch (k) {
            case 'ZFIN_DEV_ROOT':      return [null, 'no zfin-dev.env at or above here']
            case 'ZFIN_WORKTREES_DIR': return under('worktrees')
            case 'ZFIN_SEEDS_DIR':     return under('seeds')
            case 'ZFIN_ARCHIVE_DIR':   return under('archive')
            case 'ZFIN_CACHE_DIR':     return under('cache')
            case 'ZFIN_SEED':
                def dir = root ? new File(root, 'seeds') : null
                def newest = dir?.isDirectory() ? zfinUtil.newestSeed() : null
                return newest ? [newest, 'the newest seed'] : [null, 'no seeds yet']
            case 'ZFIN_FEATURE_BIND':   return [zfinUtil.featureBind(), null]
            case 'ZFIN_FEATURE_DOMAIN': return [zfinUtil.featureDomain(), null]
            case 'ZFIN_PROXY_NETWORK':
                return ProxyStack.running(zfinUtil) ? [ProxyStack.NETWORK, 'z proxy, running'] : [null, 'z proxy is not running']
            case 'ZFIN_PROXY_HTTP_PORT':  return ['80', null]
            case 'ZFIN_PROXY_HTTPS_PORT': return ['443', null]
            case 'ZFIN_SHARED_PROJECT':   return [zfinUtil.sharedProject(), null]
            case 'ZFIN_CLAUDE_TOKEN_FILE':
                return [StackConfig.CLAUDE_TOKEN_FILE_DEFAULT, null]
            case 'ZFIN_TAR_IMAGE':
                return zfinUtil.env('ZFIN_RELEASE') ? [zfinUtil.tarImage(), 'the compile image'] : [null, 'the compile image; ZFIN_RELEASE is not set']
            default: return [null, null]
        }
    }
}
