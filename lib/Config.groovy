// Config -- `z config`: this host's settings for the tooling.
//
//   z config [ls]              every setting: its value and where it comes from
//   z config get <KEY>         one setting's effective value
//   z config set <KEY>=<value> write it to the config file
//   z config unset <KEY>       remove it from the config file (back to the default)
//   z config path              where the config file is
//
// Host settings describe how this machine runs the tooling -- where worktrees and archives
// live, which address and proxy network stacks use. They are kept in the tool's own config
// file, per user (see ZfinUtil.configFile), and never in a ZFIN checkout's docker/.env. The
// process environment overrides the file, so a one-off `ZFIN_SEED=x z feature new ...` works.
// The keys, and what each is, are StackConfig.HOST_SETTINGS.
class Config {
    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return
        def die = zfinUtil.&die; def info = zfinUtil.&info
        def known = StackConfig.HOST_SETTINGS
        def requireKnown = { String k ->
            if (!(k in known.keySet()))
                die("unknown setting '$k'. Known: ${known.keySet().join(', ')}", 2)
        }

        def sub = args ? args[0] : 'ls'
        switch (sub) {
            case 'ls': case 'list':
                println "config file: ${zfinUtil.configFile()}${zfinUtil.configFile().isFile() ? '' : '  (not created yet)'}"
                def w = known.keySet()*.size().max()
                known.each { k, what ->
                    def src = zfinUtil.settingSource(k)
                    def shown = src ? "${zfinUtil.setting(k)}  [${src}]" : '-'
                    println String.format("  %-${w}s  %s", k, shown)
                    println String.format("  %-${w}s    %s", '', what)
                }
                break
            case 'get':
                if (args.size() != 2) die("usage: z config get <KEY>", 2)
                requireKnown(args[1])
                println zfinUtil.setting(args[1]) ?: ''
                break
            case 'set':
                if (args.size() != 2 || !args[1].contains('=')) die("usage: z config set <KEY>=<value>", 2)
                def (k, v) = args[1].split('=', 2)
                requireKnown(k)
                zfinUtil.saveSetting(k, v)
                info("$k=$v  -> ${zfinUtil.configFile()}")
                if (System.getenv(k)) info("note: \$$k is set in this shell's environment, which overrides the file")
                break
            case 'unset':
                if (args.size() != 2) die("usage: z config unset <KEY>", 2)
                requireKnown(args[1])
                zfinUtil.saveSetting(args[1], null)
                info("removed ${args[1]} from ${zfinUtil.configFile()}")
                break
            case 'path':
                println zfinUtil.configFile()
                break
            default:
                die("z config: unknown '$sub' (ls|get|set|unset|path)", 2)
        }
    }
}
