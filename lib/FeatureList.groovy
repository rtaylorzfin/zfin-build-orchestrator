// FeatureList -- `z feature ls [--all|-v|--frozen]`: list feature stacks (project, branch, data
// mode own/shared, up/down, url). Read-only.
//
// ACTIVE ONLY by default. A frozen stack is one you have deliberately put away: its volumes are
// archived and its containers are gone, and the thing `ls` answers is "what am I working on".
// Closed tickets that were frozen rather than removed would otherwise accumulate at the top of
// that answer forever. --frozen lists just those, --all (or -v) lists both.
class FeatureList {
    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return
        boolean all = false, frozenOnly = false
        args.each { a ->
            switch (a) {
                case '--all': case '-v': case '--verbose': all = true; break
                case '--frozen': frozenOnly = true; break
                default: zfinUtil.die("z feature ls: unknown '$a' (--all|-v|--frozen)", 2)
            }
        }
        // The inventory itself lives in ZfinUtil.featureStacks() -- the same derivation
        // `z feature refresh` walks, so the two never disagree about what exists.
        def stacks = zfinUtil.featureStacks()
        if (!stacks) { println "no feature worktrees under ${zfinUtil.worktreesDir()}"; return }

        def frozen = stacks.findAll { it.state == 'frozen' }
        def shown  = all ? stacks : (frozenOnly ? frozen : stacks.findAll { it.state != 'frozen' })

        if (!shown) {
            println frozenOnly ? "no frozen feature stacks"
                               : "no active feature stacks${frozen ? " (${frozen.size()} frozen -- z feature ls --frozen)" : ''}"
            return
        }
        def row = { a, b, c, d, e, f -> println String.format("%-16s %-22s %-6s %-8s %-38s %s", a, b, c, d, e, f) }
        row('PROJECT', 'BRANCH', 'DATA', 'STATE', 'URL', 'WORKTREE')
        shown.each { st -> row(st.project, st.branch, st.data, st.state, st.url, st.worktree) }
        println "\nDATA: own = this stack's own db+solr copy; shared = the ${zfinUtil.sharedProject()} stack"
        if (shown.any { it.state == 'partial' })
            println "STATE: partial = containers running but no httpd, so the URL will not answer (z up)"
        if (shown.any { it.state == 'freezing' })
            println "STATE: freezing = a freeze that stopped part-way: `z feature freeze` finishes it, `z feature thaw` backs out"
        if (!all && !frozenOnly && frozen)
            println "${frozen.size()} frozen stack(s) not shown -- z feature ls --frozen (or --all / -v)"
        if (all || frozenOnly)
            println "STATE: frozen = archived by `z feature freeze`, restore with `z feature thaw`"
    }
}
