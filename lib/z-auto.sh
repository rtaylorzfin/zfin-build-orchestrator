# The directory hook `z shell-init --auto` prints (and `--install` writes to ~/.bashrc.d/zfin.sh):
# `z`, its tab completion and the shortcuts (main, wt, ztree) are on while the working directory
# is inside a dev tree -- a zfin-dev.env at or above it -- and off again outside every tree.
# Moving from one tree into another switches to that tree's tooling.
#
#   export ZFIN_DEV_ROOT=<tree>    pin one tree: everything stays on, from anywhere, pointed there
#
# Re-checked before each prompt, in plain shell, so a `cd` costs no JVM start. Each tree's own
# orchestrator/ is used when it has one, else the one this hook came from (_ZFIN_AUTO_HOME,
# set just above by shell-init). Works in bash and in zsh.

_zfin_auto() {
    local tree="" d="$PWD" o p
    if [ -n "$ZFIN_DEV_ROOT" ]; then
        case "$ZFIN_DEV_ROOT" in "~"*) tree="$HOME${ZFIN_DEV_ROOT#\~}" ;; *) tree="$ZFIN_DEV_ROOT" ;; esac
    else
        while [ -n "$d" ]; do
            if [ -f "$d/zfin-dev.env" ]; then tree="$d"; break; fi
            d="${d%/*}"
        done
    fi
    [ "$tree" = "$_ZFIN_ACTIVE" ] && return     # same tree, or still outside: nothing to do

    if [ -n "$_ZFIN_ACTIVE" ]; then             # leaving a tree: take back what entering added
        p=":$PATH:"; p="${p//:$_ZFIN_ACTIVE_HOME:/:}"; p="${p#:}"; PATH="${p%:}"
        complete -r z wt 2>/dev/null
        unset -f main wt ztree _zfin_tree _zfin_worktrees _zfin_wt_complete _z_complete _z_features 2>/dev/null
        unset _ZFIN_INIT_TREE _ZFIN_ACTIVE _ZFIN_ACTIVE_HOME
    fi
    [ -n "$tree" ] || return

    o="$tree/orchestrator"
    [ -x "$o/z" ] || o="$_ZFIN_AUTO_HOME"
    [ -x "$o/z" ] || return                     # a tree with no tooling to hand: stay off
    # The same lines `z shell-init` prints, without starting a JVM to print them.
    case ":$PATH:" in *":$o:"*) ;; *) PATH="$o:$PATH" ;; esac
    if [ -n "$ZSH_VERSION" ] && ! whence complete >/dev/null; then
        autoload -U +X compinit && compinit
        autoload -U +X bashcompinit && bashcompinit
    fi
    source "$o/lib/z-completion.bash"
    _ZFIN_INIT_TREE="$tree"
    source "$o/lib/z-nav.sh"
    _ZFIN_ACTIVE="$tree"
    _ZFIN_ACTIVE_HOME="$o"
}

if [ -n "$ZSH_VERSION" ]; then
    autoload -U add-zsh-hook && add-zsh-hook precmd _zfin_auto
else
    case ";$PROMPT_COMMAND;" in
        *";_zfin_auto;"*) ;;
        *) PROMPT_COMMAND="_zfin_auto${PROMPT_COMMAND:+;$PROMPT_COMMAND}" ;;
    esac
fi
_zfin_auto     # this directory, now, rather than at the first prompt
