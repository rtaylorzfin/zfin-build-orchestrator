// Scaffold -- `z scaffold`: create the recommended dev-tree layout under one parent.
//
//   z scaffold [--root DIR] [--no-mounts] [--dry-run]
//
//   --root DIR    where to build it. Defaults to the dev tree you are in; otherwise required.
//   --no-mounts   skip mounts/ -- on a server those DOCKER_*_PATH directories usually point
//                 at existing organisation-wide locations, not at this tree.
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
        for (int i = 0; i < args.size(); i++) {
            switch (args[i]) {
                case '--root':       rootArg = args[++i]; break
                case '--no-mounts':  doMounts = false; break
                case '--dry-run':    dryRun = true; break
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
        // -- worktreesDir(), seedsDir(), archiveDir(), cacheDir(), the mount paths. Not the
        // checkouts themselves: they are clones you make (see the suggestions at the end), and
        // the tooling finds them by asking git, not by looking in a fixed place.
        def dirs = ['worktrees', 'seeds',
                    'archive', 'archive/sessions',
                    'cache']
        if (doMounts) dirs += ['mounts', 'mounts/unloads', 'mounts/unloads/db', 'mounts/unloads/solr',
                               'mounts/research', 'mounts/blast', 'mounts/downloads',
                               'mounts/loadUp', 'mounts/gff3', 'mounts/hh_atlas']

        info("dev tree root: $root${dryRun ? '   (--dry-run)' : ''}")

        def made = [], had = [], clashed = []
        dirs.each { rel ->
            def d = new File(root, rel)
            if (d.isDirectory())      { had << rel }
            else if (d.exists())      { clashed << rel }
            else if (dryRun)          { made << rel }
            else if (d.mkdirs())      { made << rel }
            else                      { clashed << rel }
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
            def recorded = zfinUtil.markTree(root)
            info("created $marker -- this directory is now a dev tree")
            if (recorded) info("recorded it as this user's ZFIN_DEV_ROOT (${zfinUtil.userConfigFile()}), since it is not above here")
        }

        // Suggestions, never actions: which remote and credentials to clone with are yours.
        def main = new File(root, 'worktrees/main')
        def orch = new File(root, 'orchestrator')
        if (!new File(main, '.git').isDirectory()) {
            def url = zfinUtil.captureOutput(['git', 'remote', 'get-url', 'origin']) ?: '<ZFIN repo URL>'
            println ""
            info("the main ZFIN checkout goes beside the features, at worktrees/main. Clone it there:")
            println "     git clone ${url} ${main}"
            println "   and give it a docker/.env (see docs/dev-tree-layout.md)."
        }
        if (zfinUtil.HOME.canonicalFile != orch.canonicalFile) {
            println ""
            info("this tooling runs from ${zfinUtil.HOME}. Its suggested home is ${orch}:")
            println "     git clone ${zfinUtil.captureOutput(['git', '-C', zfinUtil.HOME.absolutePath, 'remote', 'get-url', 'origin']) ?: '<orchestrator repo URL>'} ${orch}"
            println "     ${orch}/z shell-init >> ~/.bashrc"
        }

        if (doMounts) {
            println ""
            info("mounts/ is empty scaffolding. Point the DOCKER_*_PATH vars at it in docker/.env,")
            info("or leave them at whatever this host already uses -- those paths are often shared")
            info("with other tooling rather than owned by this tree. See docs/dev-tree-layout.md.")
        }
    }
}
