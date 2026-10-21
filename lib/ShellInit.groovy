// ShellInit -- `z shell-init [bash|zsh] [--auto|--install]`: print the shell lines that put `z`
// on PATH, turn on tab completion, and define the navigation shortcuts (main, wt, ztree:
// lib/z-nav.sh).
//
//   eval "$(<orchestrator>/z shell-init)"          # this shell only
//   <orchestrator>/z shell-init >> ~/.bashrc       # every shell from now on
//
//   --auto      print a directory hook instead (lib/z-auto.sh): all of the above is on while
//               you are inside a dev tree and off again outside it. `export ZFIN_DEV_ROOT=<tree>`
//               pins one tree, on everywhere.
//   --install   write that hook to ~/.bashrc.d/zfin.sh (~/.zshrc.d/ for zsh) and source the
//               directory from ~/.bashrc -- after listing every change and asking first.
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

        def flags = args.findAll { it.startsWith('--') }
        def unknown = flags - ['--auto', '--install']
        if (unknown) zfinUtil.die("z shell-init: unknown option ${unknown[0]} (--auto|--install)", 2)
        def positional = args - flags
        // Shell from the argument, else from $SHELL. Only the completion line differs: the
        // script is bash, and zsh needs bashcompinit loaded before it can read a bash compspec.
        def shell = positional ? positional[0] : (System.getenv('SHELL') ?: 'bash').split('/')[-1]
        if (!(shell in ['bash', 'zsh'])) zfinUtil.die(
            "z shell-init: unknown shell '${shell}' (bash|zsh).\n" +
            "   Name one explicitly: z shell-init bash", 2)

        if ('--install' in flags) install(shell, zfinUtil)
        else if ('--auto' in flags) print autoHook(zfinUtil)
        else print staticLines(shell, zfinUtil)
    }

    String staticLines(String shell, ZfinUtil zfinUtil) {
        def utils = zfinUtil.HOME.absolutePath
        def compl = new File(zfinUtil.LIB, 'z-completion.bash').absolutePath
        def nav = new File(zfinUtil.LIB, 'z-nav.sh').absolutePath
        // The tree shell-init ran in, if any: where the shortcuts go from outside every tree.
        // Looked up without prompting -- treeRoot(), not devRoot().
        def tree = zfinUtil.treeRoot()?.absolutePath
        def initTree = tree ? "_ZFIN_INIT_TREE='${tree}'\n" : ''

        """\
# ZFIN dev-stack tooling. These lines are shell -- run them, or keep them:
#
#   eval "\$(${utils}/z shell-init)"        # this shell only
#   ${utils}/z shell-init >> ~/.${shell}rc     # and every shell after
#
# `z` then works from any directory. There is nothing to activate per stack: it asks
# git which checkout owns your working directory, so one copy on PATH serves every
# checkout and worktree. (`z shell-init --auto` turns it on only inside a dev tree.)

# Prepended only if absent, so this is safe to eval twice and safe in an rc file that
# something else also sources. POSIX `case`, so bash and zsh behave the same.
case ":\$PATH:" in *":${utils}:"*) ;; *) export PATH="${utils}:\$PATH" ;; esac

# Tab completion: subcommands, service names, and each subcommand's flags.
${shell == 'zsh' ? '''autoload -U +X compinit && compinit
autoload -U +X bashcompinit && bashcompinit
''' : ''}source "${compl}"

# Shortcuts: main -> worktrees/main, wt [<name>|<ticket#>] -> worktrees/<name> (tab completes),
# ztree -> the dev tree; zrun/zexec -> z run/z exec. They follow the tree you are in${tree ? ", else this one:" : "."}
${initTree}source "${nav}"
"""
    }

    /** The hook, inlined rather than sourced from this checkout: an rc file that sources a path
     *  inside one tree breaks every new shell once that tree is gone. This orchestrator is
     *  baked in only as the fallback for a tree with no orchestrator/ of its own. */
    String autoHook(ZfinUtil zfinUtil) {
        def utils = zfinUtil.HOME.absolutePath
        """\
# ZFIN dev-stack tooling, on only inside a dev tree. Written by `z shell-init --auto`;
# re-run it (or `z shell-init --install`) to refresh this copy.
#
#   eval "\$(${utils}/z shell-init --auto)"     # this shell only
#   ${utils}/z shell-init --install             # and every shell after

_ZFIN_AUTO_HOME='${utils}'
""" + new File(zfinUtil.LIB, 'z-auto.sh').text
    }

    void install(String shell, ZfinUtil zfinUtil) {
        def home = System.getProperty('user.home')
        def tilde = { File f -> f.path.startsWith(home + '/') ? '~' + f.path.substring(home.length()) : f.path }
        def rc = new File(home, ".${shell}rc")
        def dir = new File(home, ".${shell}rc.d")
        def script = new File(dir, 'zfin.sh')
        def hook = autoHook(zfinUtil)

        // Each change is [file, what we do to it, the text]; nothing is written until approved.
        def changes = []
        if (!script.isFile()) changes << [script, 'create', hook]
        else if (script.text != hook) changes << [script, 'replace (an older hook)', hook]

        def rcDir = "~/.${shell}rc.d"
        if (!(rc.isFile() && rc.text.contains("${rcDir}/"))) {
            def glob = shell == 'zsh' ? "${rcDir}/*.sh(N)" : "${rcDir}/*.sh"   // zsh dies on a glob with no match
            changes << [rc, rc.isFile() ? 'append to' : 'create', """
# Source every script in ${rcDir}/ (added by `z shell-init --install`).
for f in ${glob}; do [ -r "\$f" ] && . "\$f"; done; unset f
"""]
        }

        // A login shell -- what Terminal and iTerm start on macOS -- reads the first of these that
        // exists and never ~/.bashrc on its own. zsh reads ~/.zshrc either way.
        File profile = null
        if (shell == 'bash') {
            profile = ['.bash_profile', '.bash_login', '.profile'].collect { new File(home, it) }.find { it.isFile() }
            if (!(profile?.text?.contains('.bashrc'))) {
                // Guarded: ~/.profile may be the one, and sh reads that too.
                changes << [profile ?: new File(home, '.bash_profile'), profile ? 'append to' : 'create', '''
# Login shells (macOS terminals) skip ~/.bashrc unless told to read it.
[ -n "$BASH_VERSION" ] && [ -f ~/.bashrc ] && . ~/.bashrc
''']
            }
        }

        // An older, always-on setup would keep z on PATH outside every tree, defeating the hook.
        def static_ = [rc, profile].findAll { it?.isFile() }.collectMany { File f ->
            def n = 0
            f.readLines().collect { [f, ++n, it] }.findAll { _, i, line ->
                !line.trim().startsWith('#') && (line =~ /z shell-init(?! --)|z-completion\.bash|z-nav\.sh/)
            }
        }

        def warnStatic = {
            if (!static_) return
            println "!! these lines set z up everywhere, so it would not go away outside a dev tree."
            println "   The hook replaces them; delete them yourself:"
            static_.each { f, n, line -> println "     ${tilde(f)}:${n}: ${line.trim()}" }
            println ""
        }

        if (!changes) {
            warnStatic()
            zfinUtil.info("already installed: ${tilde(script)} is current and ${tilde(rc)} sources ${rcDir}/")
        } else {
            def con = System.console()
            if (!con) zfinUtil.die("z shell-init --install: no TTY to ask on. To do it by hand:\n" +
                    "     mkdir -p ${rcDir} && z shell-init --auto > ${tilde(script)}\n" +
                    "   then source ${rcDir}/*.sh from ${tilde(rc)}", 2)
            println "z shell-init --install will:"
            changes.each { f, what, _ -> println "  - ${what} ${tilde(f)}" }
            println ""
            changes.findAll { f, _, __ -> f != script }.each { f, what, text ->
                println "${what.capitalize()} ${tilde(f)}:"
                text.readLines().findAll { it }.each { println "    $it" }
            }
            println "${tilde(script)} is \"z shell-init --auto\": z, completion and main/wt/ztree inside a dev tree only."
            println ""
            warnStatic()
            def a = con.readLine("Proceed? [y/N]: ")?.trim()?.toLowerCase()
            if (!(a?.startsWith('y'))) zfinUtil.die("aborted; nothing changed.", 0)

            dir.mkdirs()
            changes.each { f, what, text ->
                if (what == 'append to') f.append((f.text.endsWith('\n') ? '' : '\n') + text)
                else f.text = text
                zfinUtil.info("${what == 'append to' ? 'appended to' : what == 'create' ? 'created' : 'replaced'} ${tilde(f)}")
            }
            zfinUtil.info("open a new shell to use it, or in this one:  source ${tilde(rc)}")
        }
    }
}
