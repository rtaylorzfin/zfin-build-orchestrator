// FeatureSession -- `z feature session`: the sidecar's session history, kept past the stack.
//
//   z feature session export [<ticket>]   archive this stack's ~/.claude (sidecar) now
//   z feature session ls                  list what has been archived
//
// The ticket is inferred from the worktree you are standing in, like freeze/thaw/rm.
//
// WHY THIS EXISTS. A feature's stack is disposable; what the agent did in it often is not.
// The session holds what it was asked, what it tried, and the reasoning behind a branch that
// someone will read months later without that context. `z feature rm` archives it
// automatically -- teardown is the last moment it exists -- and this command covers the rest:
// exporting mid-flight, before a risky change, or when handing work to someone else.
//
// Archives live in `<$ZFIN_ARCHIVE_DIR>/sessions/<slug>-<timestamp>.tgz`, deliberately a
// SIBLING of the per-stack freeze archives: `z feature rm` deletes a stack's freeze archive,
// and the session is exactly what you might still want afterwards.
//
// They carry NO credential. The sidecar's token is a host file mounted read-only at
// /run/secrets/claude-token and never enters claude_home, which is why the token lives on the
// host at all. So these are safe to keep on shared storage.
class FeatureSession {
    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return
        def die = zfinUtil.&die; def info = zfinUtil.&info

        def sub = args ? args[0] : 'ls'
        def rest = args.drop(1)
        def name = rest.find { !it.startsWith('-') }

        def sessionDir = new File(zfinUtil.archiveDir(), 'sessions')

        switch (sub) {
            case 'export':
                // Same inference as freeze/thaw/rm: standing in the worktree is enough.
                if (!name) name = zfinUtil.featureSlugFromCwd()
                if (!name) die("usage: z feature session export [<ticket>]\n" +
                               "   (or run it from inside a feature worktree)", 2)
                def slug = name.toLowerCase()
                def wt = new File(zfinUtil.worktreesDir(), slug)
                def project = zfinUtil.envField(new File(wt, 'docker/.env'), 'COMPOSE_PROJECT_NAME') ?: slug

                def out = zfinUtil.archiveClaudeSession(project, slug)
                if (!out) die("no sidecar session for '$slug' -- ${project}_${StackConfig.CLAUDE_VOL} does not exist.\n" +
                              "   The volume appears the first time you run:  z run claude")
                info(String.format("archived %.1f MB -> %s", out.length() / 1048576.0, out))
                break

            case 'ls': case 'list':
                if (!sessionDir.isDirectory()) {
                    info("no archived sessions yet ($sessionDir)")
                    return
                }
                def files = (sessionDir.listFiles() ?: []).findAll { it.isFile() && it.name.endsWith('.tgz') }
                                                          .sort { -it.lastModified() }
                if (!files) { info("no archived sessions yet ($sessionDir)"); return }
                println String.format("%-44s %8s %9s  %s", 'ARCHIVE', 'KIND', 'SIZE', 'ARCHIVED')
                files.each {
                    def kind = it.name.endsWith('.transcripts.tgz') ? 'chat' : 'full'
                    println String.format("%-44s %8s %8.1fM  %s", it.name, kind,
                            it.length() / 1048576.0,
                            new Date(it.lastModified()).format('yyyy-MM-dd HH:mm'))
                }
                println "\nin $sessionDir"
                println "  chat = the conversation transcripts only (projects/**.jsonl) -- what you read"
                println "  full = the whole sidecar home, for resuming a session rather than reading it"
                println "  tar xzf <archive> -C <somewhere>"
                break

            default: die("z feature session: unknown '$sub' (export|ls)", 2)
        }
    }
}
