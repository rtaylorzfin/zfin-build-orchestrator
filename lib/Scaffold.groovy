// Scaffold -- `z scaffold`: create the recommended dev-tree layout under one parent.
//
//   z scaffold [--root DIR] [--no-mounts] [--clone URL | --no-clone] [--dry-run]
//
//   --root DIR    where to build it. Defaults to the dev tree you are in; otherwise required.
//   --no-mounts   skip mounts/ -- on a server those DOCKER_*_PATH directories usually point
//                 at existing organisation-wide locations, not at this tree.
//   --clone URL   clone the ZFIN repo into worktrees/main from URL, without asking.
//   --no-clone    leave worktrees/main alone. With neither, a terminal is asked (Enter takes
//                 the suggested URL: the origin of the ZFIN checkout you are in, else
//                 ZFIN/zfin on GitHub); without a terminal, the clone command is printed.
//   --dry-run     print what it would do.
//
// Everything this tooling reads or writes lives under one directory (docs/
// dev-tree-layout.md). That is several `mkdir`s and one .env line to get right by hand, on
// every machine, which is exactly the sort of setup that drifts between developers.
//
// CREATES WHAT IS MISSING, never clobbers. It does not require an empty parent: by the time
// you run this you have usually already cloned the repo, so the parent is not empty -- and
// refusing then would make the command useless in its most common case. Anything already
// present is reported and left alone; a path that exists but is a FILE is an error, because
// that is a real conflict rather than work already done.
class Scaffold {
    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return
        def die = zfinUtil.&die; def info = zfinUtil.&info

        def rootArg = null; def doMounts = true; def dryRun = false
        def cloneUrl = null; def noClone = false
        for (int i = 0; i < args.size(); i++) {
            switch (args[i]) {
                case '--root':       rootArg = args[++i]; break
                case '--no-mounts':  doMounts = false; break
                case '--dry-run':    dryRun = true; break
                case '--clone':      cloneUrl = args[++i]; break
                case '--no-clone':   noClone = true; break
                default: die("z scaffold: unknown arg '${args[i]}'", 2)
            }
        }

        // Resolve without going through ZfinUtil.devRoot(), which prompts when there is no tree
        // -- and --root is this command's own way of answering that.
        def rootPath = rootArg ?: zfinUtil.setting('ZFIN_DEV_ROOT')
        if (!rootPath)
            die("no root given.\n" +
                "   z scaffold --root <dir>      e.g. --root ${zfinUtil.devRootSuggestion()}\n" +
                "   It becomes a dev tree: a ${ZfinUtil.TREE_CONFIG} file marks it. See docs/dev-tree-layout.md.")
        def root = new File(rootPath.replaceFirst('^~', System.getProperty('user.home'))).absoluteFile

        if (root.exists() && !root.isDirectory())
            die("$root exists and is not a directory.")

        // The tree. Every directory here is named by a ZfinUtil accessor and read by something
        // -- worktreesDir(), seedsDir(), archiveDir(), cacheDir(), devCertDir(), the mount paths. Not the
        // checkouts themselves: they are clones you make (see the suggestions at the end), and
        // the tooling finds them by asking git, not by looking in a fixed place.
        def dirs = ['worktrees', 'seeds',
                    'archive', 'archive/sessions',
                    'cache', 'config', 'config/certs']
        if (doMounts) dirs += ['mounts', 'mounts/unloads', 'mounts/unloads/db', 'mounts/unloads/solr',
                               'mounts/research', 'mounts/blast', 'mounts/downloads',
                               'mounts/loadUp', 'mounts/gff3', 'mounts/hh_atlas']

        info("dev tree root: $root${dryRun ? '   (--dry-run)' : ''}")

        // seeds/, archive/ and cache/ may be symlinks to other storage; worktrees/ may not (see
        // ZfinUtil.worktreesDir). A symlink is reported with its target, a dangling one refused.
        def isLink = { File f -> java.nio.file.Files.isSymbolicLink(f.toPath()) }
        // Checked before anything is created, so a refusal leaves the tree as it was.
        def dangling = dirs.findAll { rel -> def d = new File(root, rel); isLink(d) && !d.exists() }
        if (dangling)
            die("symlink(s) to something that does not exist on this host -- is that disk or share mounted?\n" +
                dangling.collect { "     $root/$it -> ${java.nio.file.Files.readSymbolicLink(new File(root, it).toPath())}" }.join('\n'))
        if (isLink(new File(root, 'worktrees')))
            die("$root/worktrees is a symlink. It must be a real directory: git records a worktree's\n" +
                "   real path, and the containers mount it at the path z records.")
        def made = [], had = [], clashed = []
        dirs.each { rel ->
            def d = new File(root, rel)
            if (d.isDirectory())  { had << (isLink(d) ? "$rel -> ${d.canonicalPath}".toString() : rel) }
            else if (d.exists())  { clashed << rel }
            else if (dryRun)      { made << rel }
            else if (d.mkdirs())  { made << rel }
            else                  { clashed << rel }
        }

        if (clashed)
            die("cannot create (exists as a file, or permission denied):\n" +
                clashed.collect { "     $root/$it" }.join('\n'))

        made.each  { println "  ${dryRun ? 'would create' : 'created'}  $it" }
        had.each   { println "  exists        $it" }
        if (!made) info("nothing to do -- the tree is already in place")

        // The one thing that is not a directory: the zfin-dev.env that makes this a dev tree,
        // and that z finds by walking up from wherever you are inside it.
        def marker = new File(root, ZfinUtil.TREE_CONFIG)
        println ""
        if (marker.isFile()) {
            info("already a dev tree ($marker)")
        } else if (dryRun) {
            info("would create $marker")
        } else {
            def elsewhere = zfinUtil.markTree(root)
            info("created $marker -- this directory is now a dev tree")
            if (elsewhere) info("z finds it from inside it; from elsewhere:  export ZFIN_DEV_ROOT=$root")
        }

        // Suggestions, never actions: which remote and credentials to clone with are yours.
        def main = new File(root, 'worktrees/main')
        def orch = new File(root, 'orchestrator')
        // The main checkout: clone it on request. The suggested URL is the origin of the ZFIN
        // checkout you are standing in (one with docker/docker-compose.yml, so not this tool's
        // own checkout), else ZFIN's GitHub repo; which remote, and so which credentials, is
        // yours to confirm.
        if (main.exists() && !new File(main, '.git').isDirectory()) {
            System.err.println("!! ${main} exists but is not a git checkout -- left alone")
        } else if (!main.exists() && !noClone) {
            def top = zfinUtil.captureOutput(['git', 'rev-parse', '--show-toplevel'])
            def here = top && new File(top, 'docker/docker-compose.yml').isFile()
                    ? zfinUtil.captureOutput(['git', '-C', top, 'remote', 'get-url', 'origin']) : null
            def suggested = here ?: 'git@github.com:ZFIN/zfin.git'
            def con = System.console()
            def url = cloneUrl
            println ""
            if (!url && con && !dryRun) {
                info("the main ZFIN checkout goes beside the features, at worktrees/main.")
                def answer = con.readLine("   clone it from [${suggested}] (or 'n' to skip): ")?.trim()
                url = answer?.toLowerCase() in ['n', 'no'] ? null : (answer ?: suggested)
            }
            if (url && dryRun) {
                info("would clone ${url} into ${main}")
            } else if (url) {
                info("cloning ${url} into ${main}")
                if (zfinUtil.runCommand(['git', 'clone', url, main.absolutePath], [check: false]) != 0)
                    die("git clone failed -- nothing else was changed. Retry, or clone it yourself:\n" +
                        "     git clone <url> ${main}")
            } else {
                info("the main ZFIN checkout goes beside the features, at worktrees/main. Clone it there:")
                println "     git clone ${suggested} ${main}"
            }
        }
        if (new File(main, '.git').isDirectory() && !new File(main, 'docker/.env').isFile()) {
            println ""
            info("worktrees/main needs a docker/.env. From inside it:  z env init")
            info("(from docker/environment_mac|linux; missing paths point at mounts/). See z env --help.")
        }
        if (zfinUtil.HOME.canonicalFile != orch.canonicalFile) {
            println ""
            info("this tooling runs from ${zfinUtil.HOME}. Its suggested home is ${orch}:")
            println "     git clone ${zfinUtil.captureOutput(['git', '-C', zfinUtil.HOME.absolutePath, 'remote', 'get-url', 'origin']) ?: '<orchestrator repo URL>'} ${orch}"
            println "     eval \"\$(${orch}/z shell-init)\""
        }

        if (doMounts) {
            println ""
            info("mounts/ is empty scaffolding. Point the DOCKER_*_PATH vars at it in docker/.env,")
            info("or leave them at whatever this host already uses -- those paths are often shared")
            info("with other tooling rather than owned by this tree. See docs/dev-tree-layout.md.")
        }
    }
}
