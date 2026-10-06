// Scaffold -- `z scaffold`: create the recommended dev-tree layout under one parent.
//
//   z scaffold [--root DIR] [--no-mounts] [--dry-run]
//
//   --root DIR    where to build it. Defaults to $ZFIN_DEV_ROOT if set; otherwise required.
//   --no-mounts   skip mounts/ -- on a server those DOCKER_*_PATH directories usually point
//                 at existing organisation-wide locations, not at this tree.
//   --dry-run     print what it would do.
//
// Everything this tooling reads or writes lives under one directory (reference/
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

        // Resolve without going through ZfinUtil.devRoot(), which DIES when unset -- and being
        // unset is the normal state the first time anyone runs this.
        def rootPath = rootArg ?: zfinUtil.env('ZFIN_DEV_ROOT')
        if (!rootPath)
            die("no root given.\n" +
                "   z scaffold --root <dir>      e.g. --root ${System.getProperty('user.home')}/zfin-dev\n" +
                "   ...or set ZFIN_DEV_ROOT in docker/.env first.\n" +
                "   See reference/dev-tree-layout.md.")
        def root = new File(rootPath.replaceFirst('^~', System.getProperty('user.home'))).absoluteFile

        if (root.exists() && !root.isDirectory())
            die("$root exists and is not a directory.")

        // The tree. Every directory here is named by a ZfinUtil accessor and read by something
        // -- worktreesDir(), archiveDir(), seedsDir(), cacheDir(), the mount paths. A `repos/`
        // used to sit alongside them for the checkout itself; it was dropped because nothing
        // created into it and nothing read it, so it documented a convention rather than
        // serving the tooling, and no host actually followed it. Where the checkout lives is
        // found by asking git, not by looking in a fixed place.
        def dirs = ['worktrees',
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

        // The one thing that is not a directory: the variable that makes any of it findable.
        def envFile = new File(zfinUtil.DOCKER, '.env')
        def current = zfinUtil.envField(envFile, 'ZFIN_DEV_ROOT')
        println ""
        if (current && new File(current.replaceFirst('^~', System.getProperty('user.home'))).absoluteFile == root) {
            info("docker/.env already points here (ZFIN_DEV_ROOT=$current)")
        } else if (current) {
            System.err.println("!! docker/.env has ZFIN_DEV_ROOT=$current, which is NOT this tree.")
            System.err.println("   Update it to:  ZFIN_DEV_ROOT=$root")
        } else {
            info("add this to ${envFile} to make the tree findable:")
            println "     ZFIN_DEV_ROOT=$root"
        }

        if (doMounts) {
            println ""
            info("mounts/ is empty scaffolding. Point the DOCKER_*_PATH vars at it in docker/.env,")
            info("or leave them at whatever this host already uses -- those paths are often shared")
            info("with other tooling rather than owned by this tree. See reference/dev-tree-layout.md.")
        }
    }
}
