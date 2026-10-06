// ShellInit -- `z shell-init [bash|zsh]`: print the shell lines that put `z` on PATH and turn
// on tab completion.
//
//   eval "$(./z shell-init)"          # this shell only
//   ./z shell-init >> ~/.bashrc       # every shell from now on
//
// Everything it prints is shell, comments included, so ONE stream serves both readers: a
// person running it bare sees what the lines do, `eval` ignores the comments, and appending to
// an rc file carries the explanation along to whoever reads that file next.
//
// There is no activation step to undo and no per-stack variant: `z` finds the stack that owns
// the working directory by asking git, so one copy on PATH serves every checkout and worktree.
// This command only saves typing ./z.
class ShellInit {
    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return

        // Shell from the argument, else from $SHELL. Only the completion line differs: the
        // script is bash, and zsh needs bashcompinit loaded before it can read a bash compspec.
        def shell = args ? args[0] : (System.getenv('SHELL') ?: 'bash').split('/')[-1]
        if (!(shell in ['bash', 'zsh'])) zfinUtil.die(
            "z shell-init: unknown shell '${shell}' (bash|zsh).\n" +
            "   Name one explicitly: z shell-init bash", 2)

        def utils = zfinUtil.UTILS.absolutePath
        def compl = new File(zfinUtil.LIB, 'z-completion.bash').absolutePath

        print """\
# ZFIN dev-stack tooling. These lines are shell -- run them, or keep them:
#
#   eval "\$(./z shell-init)"        # this shell only
#   ./z shell-init >> ~/.${shell}rc     # and every shell after
#
# `z` then works from any directory. There is nothing to activate per stack: it asks
# git which checkout owns your working directory, so one copy on PATH serves every
# checkout and worktree.

# Prepended only if absent, so this is safe to eval twice and safe in an rc file that
# something else also sources. POSIX `case`, so bash and zsh behave the same.
case ":\$PATH:" in *":${utils}:"*) ;; *) export PATH="${utils}:\$PATH" ;; esac

# Tab completion: subcommands, service names, and each subcommand's flags. Works for
# `z` and `./z` alike.
${shell == 'zsh' ? '''autoload -U +X compinit && compinit
autoload -U +X bashcompinit && bashcompinit
''' : ''}source "${compl}"
"""
    }
}
