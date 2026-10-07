// Config -- `z config`: the settings this tooling runs with on this host.
//
//   z config [ls]                       every setting: its value and where it comes from
//   z config get <KEY>                  one setting's effective value
//   z config set [--user] <KEY>=<value> save it in this dev tree's zfin-dev.env, or --user in
//                                       this user's file
//   z config unset [--user] <KEY>       remove it from that file (back to the next source)
//   z config path                       the two files
//
// Settings come from, in order: the process environment, the dev tree's zfin-dev.env (found by
// walking up from the working directory; the directory holding it is ZFIN_DEV_ROOT), the
// user file (~/.config/zfin-build-orchestrator/env), the default. The user file is for running
// z outside any tree: it may name ZFIN_DEV_ROOT, and holds this user's defaults. None of them
// is a ZFIN checkout's docker/.env. The keys, and what each is, are StackConfig.HOST_SETTINGS.
class Config {
    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return
        def die = zfinUtil.&die; def info = zfinUtil.&info
        def known = StackConfig.HOST_SETTINGS
        def requireKnown = { String k ->
            if (!(k in known.keySet()))
                die("unknown setting '$k'. Known: ${known.keySet().join(', ')}", 2)
        }
        def user = args.contains('--user')
        def rest = args - ['--user']
        def fileFor = { boolean u -> u ? zfinUtil.userConfigFile() : zfinUtil.treeConfigFile() }
        def describe = { File f -> f ? "$f${f.isFile() ? '' : '  (not created yet)'}" : '(no dev tree here)' }

        def sub = rest ? rest[0] : 'ls'
        switch (sub) {
            case 'ls': case 'list':
                println "tree file: ${describe(zfinUtil.treeConfigFile())}"
                println "user file: ${describe(zfinUtil.userConfigFile())}"
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
                if (rest.size() != 2 || !rest[1].contains('=')) die("usage: z config set [--user] <KEY>=<value>", 2)
                def (k, v) = rest[1].split('=', 2)
                requireKnown(k)
                if (k == 'ZFIN_DEV_ROOT' && !user)
                    die("a tree's ZFIN_DEV_ROOT is where its ${ZfinUtil.TREE_CONFIG} is, not a line in it.\n" +
                        "   Make a directory a tree with  z scaffold --root <dir>, or point this user at one\n" +
                        "   (for running z outside it):  z config set --user ZFIN_DEV_ROOT=<dir>", 2)
                zfinUtil.saveSetting(k, v, user)
                info("$k=$v  -> ${fileFor(user)}")
                if (System.getenv(k)) info("note: \$$k is set in this shell's environment, which overrides the file")
                else if (user && zfinUtil.settingSource(k) == 'tree')
                    info("note: this dev tree's ${ZfinUtil.TREE_CONFIG} sets $k too, and wins here")
                break
            case 'unset':
                if (rest.size() != 2) die("usage: z config unset [--user] <KEY>", 2)
                requireKnown(rest[1])
                if (!fileFor(user)?.isFile()) die("nothing to unset: ${describe(fileFor(user))}")
                zfinUtil.saveSetting(rest[1], null, user)
                info("removed ${rest[1]} from ${fileFor(user)}")
                break
            case 'path':
                println "tree file: ${describe(zfinUtil.treeConfigFile())}"
                println "user file: ${describe(zfinUtil.userConfigFile())}"
                break
            default:
                die("z config: unknown '$sub' (ls|get|set|unset|path)", 2)
        }
    }
}
