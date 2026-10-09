// Config -- `z config`: the settings this tooling runs with on this host.
//
//   z config [ls]                       every setting: its value and where it comes from
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
        def rest = args
        def describe = { File f -> f ? "$f${f.isFile() ? '' : '  (not created yet)'}" : '(no dev tree here)' }

        def sub = rest ? rest[0] : 'ls'
        switch (sub) {
            case 'ls': case 'list':
                println "tree file: ${describe(zfinUtil.treeConfigFile())}"
                def w = known.keySet()*.size().max()
                known.each { k, what ->
                    def src = zfinUtil.settingSource(k)
                    def shown = src ? "${zfinUtil.setting(k)}  [${src}]" : '-'
                    println String.format("  %-${w}s  %s", k, shown)
                    println String.format("  %-${w}s    %s", '', what)
                }
                break
            case 'get':
                if (rest.size() != 2) die("usage: z config get <KEY>", 2)
                requireKnown(rest[1])
                println zfinUtil.setting(rest[1]) ?: ''
                break
            case 'set':
                if (rest.size() != 2 || !rest[1].contains('=')) die("usage: z config set <KEY>=<value>", 2)
                def (k, v) = rest[1].split('=', 2)
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
}
