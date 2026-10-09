# Shortcuts for moving around a dev tree. `z shell-init` prints the line that sources this.
#   main            -> worktrees/main, the main checkout
#   wt              -> worktrees/
#   wt <name>       -> worktrees/<name>; a bare number is a ticket: wt 12345 -> worktrees/zfin-12345
#   wt <TAB>        -> the worktrees there are
#   ztree           -> the dev tree itself
#
# Functions, because only the shell itself can change its directory. Plain shell rather than a
# call to z, so a keystroke costs no JVM start. They find the tree as z does -- $ZFIN_DEV_ROOT,
# else the nearest zfin-dev.env at or above here -- and, from outside any tree, fall back to
# the one shell-init ran in (_ZFIN_INIT_TREE). Works in bash and in zsh.

_zfin_tree() {
    if [ -n "$ZFIN_DEV_ROOT" ]; then
        case "$ZFIN_DEV_ROOT" in "~"*) echo "$HOME${ZFIN_DEV_ROOT#\~}" ;; *) echo "$ZFIN_DEV_ROOT" ;; esac
        return
    fi
    local d="$PWD"
    while [ -n "$d" ]; do
        if [ -f "$d/zfin-dev.env" ]; then echo "$d"; return; fi
        d="${d%/*}"
    done
    if [ -n "$_ZFIN_INIT_TREE" ] && [ -f "$_ZFIN_INIT_TREE/zfin-dev.env" ]; then echo "$_ZFIN_INIT_TREE"; return; fi
    echo "no dev tree here: no zfin-dev.env at or above $PWD (set ZFIN_DEV_ROOT)" >&2
    return 1
}

# ZFIN_WORKTREES_DIR from the environment, else from the tree's zfin-dev.env, else <tree>/worktrees.
_zfin_worktrees() {
    local root w
    root="$(_zfin_tree)" || return 1
    w="${ZFIN_WORKTREES_DIR:-$(sed -n 's/^ZFIN_WORKTREES_DIR=//p' "$root/zfin-dev.env" 2>/dev/null | tail -n 1)}"
    case "$w" in "") w="$root/worktrees" ;; "~"*) w="$HOME${w#\~}" ;; esac
    echo "$w"
}

ztree() { local root; root="$(_zfin_tree)" && cd "$root"; }

main() { local w; w="$(_zfin_worktrees)" && cd "$w/main"; }

wt() {
    local w
    w="$(_zfin_worktrees)" || return 1
    if [ -z "$1" ]; then cd "$w"; return; fi
    case "$1" in
        *[!0-9]*) ;;
        *) if [ ! -d "$w/$1" ] && [ -d "$w/zfin-$1" ]; then cd "$w/zfin-$1"; return; fi ;;
    esac
    if [ -d "$w/$1" ]; then cd "$w/$1"; return; fi
    echo "wt: no worktree '$1' in $w" >&2
    return 1
}

_zfin_wt_complete() {
    local w cur="${COMP_WORDS[COMP_CWORD]}"
    COMPREPLY=()
    (( COMP_CWORD == 1 )) || return
    w="$(_zfin_worktrees 2>/dev/null)" || return
    COMPREPLY=( $(compgen -W "$(find "$w" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sed 's#.*/##')" -- "$cur") )
}
complete -F _zfin_wt_complete wt
