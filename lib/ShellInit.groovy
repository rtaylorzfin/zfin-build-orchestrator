// ShellInit -- `z shell-init [bash|zsh]`: print the shell lines that put `z` on PATH, turn
// on tab completion, and define the navigation shortcuts (main, wt, ztree: lib/z-nav.sh).
//
//   eval "$(<orchestrator>/z shell-init)"          # this shell only
//   <orchestrator>/z shell-init >> ~/.bashrc       # every shell from now on
//
// Everything it prints is shell, comments included, so ONE stream serves both readers: a
// person running it bare sees what the lines do, `eval` ignores the comments, and appending to
// an rc file carries the explanation along to whoever reads that file next.
//
// There is no activation step to undo and no per-stack variant: `z` finds the stack that owns
// the working directory by asking git, so one copy on PATH serves every checkout and worktree.
class ShellInit {
    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return

        // Shell from the argument, else from $SHELL. Only the completion line differs: the
        // script is bash, and zsh needs bashcompinit loaded before it can read a bash compspec.
        def shell = args ? args[0] : (System.getenv('SHELL') ?: 'bash').split('/')[-1]
        if (!(shell in ['bash', 'zsh'])) zfinUtil.die(
            "z shell-init: unknown shell '${shell}' (bash|zsh).\n" +
            "   Name one explicitly: z shell-init bash", 2)

        def utils = zfinUtil.HOME.absolutePath
        def compl = new File(zfinUtil.LIB, 'z-completion.bash').absolutePath
        def nav = new File(zfinUtil.LIB, 'z-nav.sh').absolutePath
        // The tree shell-init ran in, if any: where the shortcuts go from outside every tree.
        // Looked up without prompting -- treeRoot(), not devRoot().
        def tree = zfinUtil.treeRoot()?.absolutePath
        def initTree = tree ? "_ZFIN_INIT_TREE='${tree}'\n" : ''

        print """\
# ZFIN dev-stack tooling. These lines are shell -- run them, or keep them:
#
#   eval "\$(${utils}/z shell-init)"        # this shell only
#   ${utils}/z shell-init >> ~/.${shell}rc     # and every shell after
#
# `z` then works from any directory. There is nothing to activate per stack: it asks
# git which checkout owns your working directory, so one copy on PATH serves every
# checkout and worktree.

# Prepended only if absent, so this is safe to eval twice and safe in an rc file that
# something else also sources. POSIX `case`, so bash and zsh behave the same.
case ":\$PATH:" in *":${utils}:"*) ;; *) export PATH="${utils}:\$PATH" ;; esac

# Tab completion: subcommands, service names, and each subcommand's flags.
${shell == 'zsh' ? '''autoload -U +X compinit && compinit
autoload -U +X bashcompinit && bashcompinit
''' : ''}source "${compl}"

# Shortcuts: main -> worktrees/main, wt [<name>|<ticket#>] -> worktrees/<name> (tab completes),
# ztree -> the dev tree. They follow the tree you are in${tree ? ", else this one:" : "."}
${initTree}source "${nav}"
"""
    }
}
