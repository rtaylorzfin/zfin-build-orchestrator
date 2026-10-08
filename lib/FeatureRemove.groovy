// FeatureRemove -- `z feature rm <ticket> [--force]`: tear down a feature stack (the
// teardown block new-feature prints, automated): `docker compose down -v` its stack +
// per-feature volumes, remove the worktree + branch, and drop its
// lo0 alias and (if `feature new --tmux` left one) its tmux session. DESTRUCTIVE -- discards
// the stack's copies AND any uncommitted work in the worktree -- so it prompts unless
// --force. Do it once the feature's PR is merged.
class FeatureRemove {
    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return
        def die = zfinUtil.&die; def info = zfinUtil.&info
        def runCommand = zfinUtil.&runCommand; def captureOutput = zfinUtil.&captureOutput
        def REPO = zfinUtil.REPO

        def force = false; def name = null; def keepArchive = false; def keepSession = true
        args.each {
            if (it in ['--force', '-f']) force = true
            else if (it == '--keep-archive') keepArchive = true
            else if (it == '--no-session') keepSession = false
            else if (it.startsWith('-')) die("z feature rm: unknown arg '$it'", 2)
            else name = it
        }
        // Inferred like the others, but this one is destructive -- so the confirmation
        // prompt below is what actually protects you, and it always names the stack.
        if (!name) name = zfinUtil.featureSlugFromCwd()
        if (!name) die("usage: z feature rm <ticket> [--force] [--keep-archive]\n" +
                       "   (or run it from inside a feature worktree and the ticket is inferred)", 2)

        def slug = name.toLowerCase()
        // Before anything else, and before --force is ever mentioned.
        zfinUtil.requireFeature(slug)
        def wt   = new File(zfinUtil.worktreesDir(), slug)
        def env  = new File(wt, 'docker/.env')
        def field = { String k -> zfinUtil.envField(env, k) }
        def branch = wt.isDirectory() ? captureOutput(['git', '-C', wt.absolutePath, 'rev-parse', '--abbrev-ref', 'HEAD']) : slug
        // `z feature new --existing-branch` set the stack up on a branch that predates it,
        // so the branch outlives the stack: remove the worktree, keep the work.
        def keepBranch = field('ZFIN_FEATURE_BRANCH_PREEXISTING') == '1'
        // `z feature new --tmux` leaves a session named after the slug. Probed BEFORE the
        // confirmation so the prompt lists everything that's about to go ('-t=' is exact
        // match: a plain '-t zfin-10' would also match a running 'zfin-104').
        def hasTmux = zfinUtil.onPath('tmux') && zfinUtil.runQuietly(['tmux', 'has-session', '-t=' + slug]) == 0

        // A frozen stack's archive outlives its containers by design -- that is the point of
        // freeze. But `rm` is the end of the feature's life, so leaving the archive behind just
        // orphans tens of GB on whatever storage ZFIN_ARCHIVE_DIR points at, with nothing left
        // on disk to say what it belonged to. Remove it with the rest, name it in the prompt so
        // it is never a surprise, and offer --keep-archive for the case where the archive is
        // the thing you actually want to keep.
        def archive = new File(new File(zfinUtil.archiveDir(false)), slug)   // absent storage: nothing to remove
        def archiveBytes = archive.isDirectory() ?
                ((archive.listFiles() ?: []).sum(0L) { it.isFile() ? it.length() : 0L } as long) : 0L
        def dropArchive = archive.isDirectory() && !keepArchive

        if (!force) {
            def con = System.console()
            if (!con) die("z feature rm: no TTY -- pass --force to confirm", 2)
            println "About to REMOVE feature '$slug' (destructive):"
            println "  - docker compose down -v            (containers + this feature's volumes/copies)"
            if (wt.isDirectory())
                println "  - git worktree remove --force $wt" +
                        (keepBranch ? "  (drops uncommitted work; branch '$branch' KEPT -- it predates this stack)"
                                    : " + branch -D $branch  (drops uncommitted work)")
            if (keepSession) println "  - archiving the sidecar session first (--no-session to skip)"
            if (dropArchive)
                println String.format("  - rm -rf %s   (%.1f GB freeze archive -- --keep-archive to spare it)",
                        archive, archiveBytes / 1073741824.0)
            else if (archive.isDirectory())
                println "  - KEEPING the freeze archive at $archive (--keep-archive)"
            if (hasTmux) println "  - tmux kill-session -t $slug        (the feature's shell)"
            def a = con.readLine("Proceed? [y/N]: ")?.trim()?.toLowerCase()
            if (!(a?.startsWith('y'))) die("aborted.", 0)
        }

        // 1. tear down the Docker stack, using the feature's OWN project + compose list (read
        //    from its docker/.env by stackSpec) so down -v resolves exactly this stack.
        //    check:false because teardown has to work on a half-provisioned or already-mangled
        //    feature -- that is when you need it most -- so a stack whose .env is missing or
        //    unreadable falls back to the origin's compose files rather than dying.
        def spec = zfinUtil.stackSpec(wt)
        def compose = ['docker', 'compose', '-p', spec?.project ?: slug]
        if (env.isFile()) compose += ['--env-file', env.absolutePath]
        // Teardown must work on a half-provisioned stack too -- that is when you need it most
        // -- so fall back to the base compose file alone rather than dying.
        compose += (spec?.compose ?: new File(zfinUtil.DOCKER, 'docker-compose.yml').absolutePath)
                   .tokenize(':').collectMany { ['-f', it] }
        // The sidecar's session history, BEFORE down -v removes the volume holding it. This is
        // the last moment it exists, and it is usually the only record of what the agent was
        // asked and why the branch looks the way it does. Kept in <archive>/sessions/, a
        // sibling of this stack's freeze archive, so removing the stack does not remove it.
        if (keepSession) {
            def sess = zfinUtil.archiveClaudeSession(slug, slug)
            if (sess) info(String.format("archived the sidecar session (%.1f MB) -> %s",
                    sess.length() / 1048576.0, sess))
        }

        // --rmi local removes the images compose BUILT for this stack and auto-named
        // <project>-<service> (ncbiload, blast, certbot), while leaving anything with an
        // explicit `image:` alone -- so ghcr.io/zfin/* images are
        // untouched. Without it those per-stack images outlive the stack: five removed
        // features had left ~17G of them behind, which matters on a host chosen for having
        // little disk to spare.
        info("down -v the '$slug' stack (and its own built images)")
        runCommand(compose + ['down', '-v', '--rmi', 'local'], [check: false])
        zfinUtil.sweepProjectVolumes((spec?.project ?: slug).toString())

        // A --shared-db feature has the SHARED db/solr connected into its `<slug>_default`
        // network; those foreign containers block `down -v` from removing the network, leaving
        // it dangling (and a later recreate hits "endpoint already exists"). Disconnect any
        // still-attached containers, then remove the network.
        def net = "${slug}_default".toString()
        if (zfinUtil.runQuietly(['docker', 'network', 'inspect', net]) == 0) {
            def attached = captureOutput(['docker', 'network', 'inspect', net, '--format',
                '{{range .Containers}}{{.Name}} {{end}}']).split() as List
            attached.each { runCommand(['docker', 'network', 'disconnect', '-f', net, it], [check: false]) }
            runCommand(['docker', 'network', 'rm', net], [check: false])
        }

        // 2. worktree + branch
        if (wt.isDirectory()) {
            // Container-owned files (node_modules, build/) block `git worktree remove` on Linux.
            zfinUtil.reclaimOwnership(wt)
            info("removing worktree $wt")
            runCommand(['git', '-C', REPO.absolutePath, 'worktree', 'remove', '--force', wt.absolutePath], [check: false])
            // `git worktree remove` leaves the directory behind when it could not empty it.
            // Say so rather than letting a stale tree look like a successful teardown.
            if (wt.isDirectory() && !wt.deleteDir())
                System.err.println("!! $wt still exists -- remove it by hand:\n" +
                                   "     docker run --rm -u 0 -v ${wt.absolutePath}:/wt alpine rm -rf /wt")
        }
        if (keepBranch) {
            info("keeping branch $branch (pre-existing -- created before this feature stack)")
        } else if (branch && branch != 'HEAD') {
            info("deleting branch $branch")
            runCommand(['git', '-C', REPO.absolutePath, 'branch', '-D', branch], [check: false])
        }

        // 3. Nothing host-level to undo. Name resolution is a wildcard record for the whole
        //    zone, and published ports are offsets on 127.0.0.1 rather than per-stack loopback
        //    aliases -- so provisioning creates no host state and teardown removes none.

        // 4. the feature's tmux session, if `feature new` left one. Last, because killing it
        //    can kill the shell this command is running in -- when you tore the feature down
        //    from inside its own session, that's the intended end of the story, but everything
        //    else has to have happened first.
        if (hasTmux) {
            info("killing tmux session '$slug'")
            runCommand(['tmux', 'kill-session', '-t=' + slug], [check: false])
        }

        if (dropArchive) {
            info(String.format("removing freeze archive %s (%.1f GB)", archive, archiveBytes / 1073741824.0))
            archive.deleteDir()
        } else if (archive.isDirectory()) {
            info("kept the freeze archive at $archive -- nothing references it now, so remove it by hand when done")
        }

        info("removed feature '$slug'")
    }
}
