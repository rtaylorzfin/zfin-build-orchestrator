// Env -- `z env`: the ZFIN checkout's docker/.env, the stack settings every stack starts from.
//
//   z env [show]                       where it is, and the values z reads from it
//   z env init [opts]                  create it from one of the checkout's docker/environment_*
//   z env get <KEY>                    one value from it
//   z env set <KEY>=<value>            change (or add) one line in it
//
// init options:
//   --from mac|linux|FILE   the template: docker/environment_<name>, or any file. Default:
//                           environment_mac on macOS, environment_linux elsewhere.
//   --op                    fill in the secrets from 1Password: `op inject` the template's
//                           .op_tmpl (signed in first: eval $(op signin)). Without it the
//                           secrets are the template's ChangeMe! placeholders, fine on a laptop.
//   --no-mounts             keep every DOCKER_*_PATH as the template has it. By default a path
//                           that does not exist on this host is pointed at the dev tree's
//                           mounts/ instead, when that directory is there (z scaffold makes it).
//   --force                 replace an existing docker/.env (the old one is kept as .env.bak-<time>)
//   --dry-run               print the file instead of writing it
//
// Not `z config`: that is how this HOST runs the tooling (zfin-dev.env). This file describes
// the checkout's own stack -- ZFIN_RELEASE, COMPOSE_PROJECT_NAME, the DOCKER_*_PATH mounts --
// and is the base each new feature's docker/.env is copied from. It is untracked, so it
// survives branch switches.
class Env {
    // A template path that is missing on this host -> the dev tree's mounts/ directory for it
    // (the layout z scaffold makes; docs/dev-tree-layout.md).
    static final Map<String, String> MOUNTS = [
        DOCKER_DB_UNLOADS_PATH                : 'unloads/db',
        DOCKER_SOLR_UNLOADS_PATH              : 'unloads/solr',
        DOCKER_RESEARCH_PATH                  : 'research',
        DOCKER_BLASTSERVER_BLAST_DATABASE_PATH: 'blast',
        DOCKER_ABBLAST_PATH                   : 'blast/ab-blast',
        DOCKER_DOWNLOADS_PATH                 : 'downloads',
        DOCKER_LOADUP_PATH                    : 'loadUp',
        DOCKER_GFF3_PATH                      : 'gff3',
        DOCKER_HHATLAS_PATH                   : 'hh_atlas',
    ]
    // What `z env` shows: the keys the tooling itself reads from this file.
    static final List<String> SHOWN = ['COMPOSE_PROJECT_NAME', 'ZFIN_RELEASE', 'DOCKER_VIRTUAL_HOST',
                                       'DOCKER_SOURCE_ROOTS_PATH', 'DOCKER_DB_UNLOADS_PATH', 'DOCKER_SOLR_UNLOADS_PATH',
                                       'DOCKER_ARCH', 'DOCKER_DB_ARCH', 'DOCKER_DB_PLATFORM']

    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return
        def die = zfinUtil.&die; def info = zfinUtil.&info
        def envFile = new File(zfinUtil.DOCKER, '.env')
        def sub = args ? args[0] : 'show'
        switch (sub) {
            case 'show': case 'path':
                if (!envFile.isFile()) {
                    println "$envFile  (not created yet)"
                    println ""
                    println "Create it from a template:  z env init   (z env --help for the options)"
                    return
                }
                println envFile
                def d = zfinUtil.dotenv()
                def w = SHOWN*.size().max()
                SHOWN.each { k -> println String.format("  %-${w}s  %s", k, d.containsKey(k) ? d[k] : '-') }
                break
            case 'get':
                if (args.size() != 2) die("usage: z env get <KEY>", 2)
                if (!envFile.isFile()) die(zfinUtil.noBaseEnv())
                println zfinUtil.envField(envFile, args[1])
                break
            case 'set':
                if (args.size() != 2 || !args[1].contains('=')) die("usage: z env set <KEY>=<value>", 2)
                if (!envFile.isFile()) die(zfinUtil.noBaseEnv())
                def (k, v) = args[1].split('=', 2)
                if (!(k ==~ /[A-Za-z_][A-Za-z0-9_]*/)) die("not a variable name: '$k'", 2)
                // Replace in place, so the line stays where the template put it; append when new.
                def lines = envFile.readLines()
                def hit = false
                def out = lines.collect { l ->
                    if (!l.startsWith("${k}=")) return l
                    if (hit) return null                    // a duplicate: last-wins would hide the edit
                    hit = true
                    "${k}=${v}".toString()
                }.findAll { it != null }
                if (!hit) out << "${k}=${v}".toString()
                envFile.text = out.join('\n') + '\n'
                info("$k=$v  -> $envFile")
                break
            case 'init':
                init(args.drop(1), envFile, zfinUtil)
                break
            default:
                die("z env: unknown '$sub' (show|init|get|set)", 2)
        }
    }

    private void init(List args, File envFile, ZfinUtil zfinUtil) {
        def die = zfinUtil.&die; def info = zfinUtil.&info
        def DOCKER = zfinUtil.DOCKER
        String from = null
        boolean op = false, mounts = true, force = false, dryRun = false
        for (int i = 0; i < args.size(); i++) {
            switch (args[i]) {
                case '--from':      if (i + 1 >= args.size()) die("--from needs mac, linux or a file", 2); from = args[++i]; break
                case '--op':        op = true; break
                case '--no-mounts': mounts = false; break
                case '--force':     force = true; break
                case '--dry-run':   dryRun = true; break
                default: die("z env init: unknown arg '${args[i]}'", 2)
            }
        }

        if (envFile.isFile() && !force && !dryRun)
            die("$envFile already exists -- edit it, change one value with  z env set KEY=value,\n" +
                "   or start over with  z env init --force  (the old one is kept as a backup)")

        // The template: a name picks docker/environment_<name>, anything else is a path.
        def mac = System.getProperty('os.name').toLowerCase().contains('mac')
        def name = from ?: (mac ? 'mac' : 'linux')
        def template = (name ==~ /[A-Za-z0-9_-]+/ && !new File(name).isFile())
                ? new File(DOCKER, "environment_${name}") : new File(name).absoluteFile
        if (op) template = new File(template.path + '.op_tmpl')
        if (!template.isFile()) {
            def have = (DOCKER.listFiles() ?: []).findAll { it.name.startsWith('environment_') && !it.name.endsWith('.op_tmpl') }
                    .collect { it.name - 'environment_' }.sort()
            die("no template at $template\n   in ${DOCKER}: ${have.join(', ') ?: 'none'}  (or --from <file>)", 2)
        }

        List<String> lines
        if (op) {
            if (!zfinUtil.onPath('op')) die("--op needs the 1Password CLI (op) -- see docker/README_1password.md")
            // op writes the resolved file itself (0600), so the secrets never pass through here
            // as a captured string or land in a world-readable temp file.
            def tmp = File.createTempFile('zfin-env', '.env', DOCKER)
            try {
                info("op inject --in-file ${template.name}   (sign in first if this fails: eval \$(op signin))")
                if (zfinUtil.runCommand(['op', 'inject', '--force', '--in-file', template.path, '--out-file', tmp.path], [check: false]) != 0)
                    die("op inject failed -- nothing written. Sign in (eval \$(op signin)) and retry, or drop --op.")
                lines = tmp.readLines()
            } finally { tmp.delete() }
        } else {
            lines = template.readLines()
        }

        // Point missing host paths at the dev tree's mounts/, where z scaffold put the directories.
        def home = System.getProperty('user.home')
        def expand = { String p -> new File(p.replaceAll(/^["']|["']$/, '').replaceFirst('^~', home)) }
        def root = zfinUtil.setting('ZFIN_DEV_ROOT')
        def mountsDir = root ? new File(root, 'mounts') : null
        def remapped = [:]
        if (mounts && mountsDir?.isDirectory()) {
            lines = lines.collect { l ->
                def m = l =~ /^([A-Za-z_][A-Za-z0-9_]*)=(.*)$/
                if (!m.matches() || !MOUNTS.containsKey(m.group(1))) return l
                def (k, v) = [m.group(1), m.group(2)]
                def target = new File(mountsDir, MOUNTS[k])
                if (expand(v).exists() || !target.exists()) return l
                remapped[k] = [was: v, now: target.path]
                "${k}=${target.path}".toString()
            }
        }

        def header = "# created by `z env init` from ${template.name} on ${new Date().format('yyyy-MM-dd')} -- see `z env --help`"
        def text = ([header] + lines).join('\n') + '\n'

        if (dryRun) { print text; return }

        if (envFile.isFile()) {
            def bak = new File(envFile.parentFile, ".env.bak-${new Date().format('yyyyMMdd-HHmmss')}")
            java.nio.file.Files.copy(envFile.toPath(), bak.toPath())
            info("kept the old one as ${bak.name}")
        }
        envFile.text = text
        // Secrets, or placeholders for them: not for other users of the host either way.
        envFile.setReadable(false, false); envFile.setReadable(true, true)
        envFile.setWritable(false, false); envFile.setWritable(true, true)
        info("wrote $envFile from ${template.name}")
        remapped.each { k, r -> println "   $k=${r.now}   (template's ${r.was} is not on this host)" }

        // What is still worth a look: host paths that do not exist, and the template's own user.
        def d = [:]
        lines.each { l -> def m = l =~ /^([A-Za-z_][A-Za-z0-9_]*)=(.*)$/; if (m.matches()) d[m.group(1)] = m.group(2) }
        def missing = d.findAll { k, v -> k ==~ /DOCKER_.*_PATH/ && k != 'DOCKER_SOURCE_ROOTS_PATH' && v && !expand(v).exists() }
        if (missing) {
            println ""
            info("these paths do not exist on this host (the db and solr unloads are needed to load a stack):")
            missing.each { k, v -> println "   $k=$v" }
        }
        if (d['DOCKER_SSH_USER'] && d['DOCKER_SSH_USER'] != System.getProperty('user.name')) {
            println ""
            info("DOCKER_SSH_USER=${d['DOCKER_SSH_USER']} is the template's; yours:  z env set DOCKER_SSH_USER=<you>")
        }
        if (!op && lines.any { it.contains('ChangeMe!') || it.contains('changeme') }) {
            println ""
            info("secrets are the template's placeholders; real ones:  z env init --op --force")
        }
    }
}
