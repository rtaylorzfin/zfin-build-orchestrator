// FeatureRefresh -- `z feature refresh <ticket>|--all [--dry-run]`
//
// Backfills keys a feature's docker/.env is missing. A stack's .env is written once, by
// `z feature new`, and then outlives every change to what that file should contain: a stack made
// before a key existed keeps working right up until the missing key changes what compose
// renders. DOCKER_EXTERNAL_VHOST is the sharp one -- absent, it falls back to
// DOCKER_VIRTUAL_HOST, and the stack advertises itself to a proxy that cannot reach it, which
// surfaces as a 502 from someone else's nginx-proxy rather than as an error here.
//
// ONLY ADDS. An existing key is never rewritten, whatever its value: a .env is a developer's
// file, and a deliberate local override must survive a refresh. Use --dry-run to see what
// would be appended.
//
// What "should be there" is StackConfig.featureEnv, the same list `z feature new` writes from,
// so the two cannot disagree about it.
class FeatureRefresh {
    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return
        def info = zfinUtil.&info
        def die  = zfinUtil.&die

        boolean all = false, dryRun = false
        def targets = []
        args.each { a ->
            switch (a) {
                case '--all':     all = true; break
                case '--dry-run': dryRun = true; break
                default:
                    if (a.startsWith('-')) die("unknown flag: $a", 2)
                    targets << a
            }
        }
        if (!all && !targets) die("name a ticket, or --all.\n" +
                                  "   z feature refresh <ticket> [--dry-run]\n" +
                                  "   z feature refresh --all", 2)

        def stacks = zfinUtil.featureStacks()
        if (!stacks) { info("no feature worktrees under ${zfinUtil.worktreesDir()}"); return }
        if (!all) {
            def known = stacks.collect { it.slug }
            targets.each { t -> if (!(t in known)) die("no such feature: $t\n   known: ${known.join(', ')}") }
            stacks = stacks.findAll { it.slug in targets }
        }

        int changed = 0
        stacks.each { st ->
            def envF = new File(new File(zfinUtil.worktreesDir(), st.worktree), 'docker/.env')
            if (!envF.isFile()) { println "  ${st.slug}: no docker/.env -- skipped"; return }
            // The hostname every other key is derived from. Without it we cannot know what this
            // stack is called, and guessing it would write a wrong value into a working stack.
            def host = zfinUtil.envField(envF, 'DOCKER_VIRTUAL_HOST')
            if (!host) { println "  ${st.slug}: no DOCKER_VIRTUAL_HOST -- skipped (cannot derive the rest)"; return }

            def present = envF.readLines().collect { (it =~ /^([A-Za-z_][A-Za-z0-9_]*)=/) }
                              .findAll { it.find() }.collect { it.group(1) } as Set
            def (gitCommon, gitDir) = zfinUtil.gitDirs(new File(zfinUtil.worktreesDir(), st.worktree))
            // Advertise to an outside proxy only if this stack joins one's network.
            def advertise = (st.spec?.overlays ?: []).any { it.contains('proxy-network') }
            def missing = StackConfig.featureEnv(host, gitCommon, gitDir, advertise).findAll { k, v -> !(k in present) }
            if (!missing) { println "  ${st.slug}: up to date"; return }

            changed++
            println "  ${st.slug}: ${dryRun ? 'would add' : 'adding'} ${missing.keySet().join(', ')}"
            if (dryRun) return
            envF << "\n# added by `z feature refresh` -- see StackConfig.featureEnv\n"
            missing.each { k, v -> envF << "$k=$v\n" }
        }

        if (!changed) { info("every feature .env is up to date"); return }
        info(dryRun ? "--dry-run: nothing written"
                    : "${changed} .env file(s) updated. Recreate the affected containers to pick " +
                      "them up:\n   cd <worktree> && z up -d")
    }
}
