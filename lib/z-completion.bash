# Bash tab-completion for `z`. `z shell-init` prints the line that sources it.
#   z <TAB>          -> subcommands           z build <TAB>   -> phases
#   z run <TAB>      -> services              z feature <TAB> -> new | ls | rm | refresh | ...
#   z feature rm|freeze|thaw|refresh <TAB>   -> feature names (the worktrees, less main)
# Feature names for `z feature rm <TAB>` and friends: the worktrees there are, less `main`.
# Read off the directory, as `wt <TAB>` does (lib/z-nav.sh, which shell-init sources too),
# rather than from `z feature ls` -- a keystroke should not cost a JVM start and a docker call.
_z_features() {
    local w
    type _zfin_worktrees >/dev/null 2>&1 || return
    w="$(_zfin_worktrees 2>/dev/null)" || return
    find "$w" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sed 's#.*/##' | grep -vx main
}

_z_complete() {
    local cur sub argstart=2
    cur="${COMP_WORDS[COMP_CWORD]}"
    if (( COMP_CWORD == 1 )); then
        COMPREPLY=( $(compgen -W "run exec up stop down pull log restart status build feature seed shared scaffold fresh-install shell-init env config cert proxy help" -- "$cur") )
        return
    fi
    sub="${COMP_WORDS[1]}"

    local services="base compile claude db solr httpd tomcat tomcatdebug blast mailpit jenkins ncbiload fail2ban certbot elasticsearch filebeat metricbeat kibana jbrowse processgff"
    case "$sub" in
        run|exec)                  COMPREPLY=( $(compgen -W "$services -u" -- "$cur") ) ;;
        up|stop|pull|log|restart)  COMPREPLY=( $(compgen -W "$services" -- "$cur") ) ;;
        status)                    COMPREPLY=( $(compgen -W "-v --verbose" -- "$cur") ) ;;
        down)                      COMPREPLY=( $(compgen -W "$services -v" -- "$cur") ) ;;
        build)                     COMPREPLY=( $(compgen -W "configure load-db load-solr deploy-jenkins deploy all --build --pull-missing --test" -- "$cur") ) ;;
        shared)                    COMPREPLY=( $(compgen -W "up down status freeze thaw --tag --rm-data --stop-sharers --compress --no-compress --to --from" -- "$cur") ) ;;
        seed)
            if (( COMP_CWORD == argstart )); then
                COMPREPLY=( $(compgen -W "new restore add-volumes verify ls rm" -- "$cur") )
            else
                case "${COMP_WORDS[argstart]}" in
                    new|build)   if [[ " ${COMP_WORDS[*]} " == *" --from "* ]]; then
                                     COMPREPLY=( $(compgen -W "--tag --no-app --caches" -- "$cur") )
                                 else
                                     COMPREPLY=( $(compgen -W "--from --db --solr --tag --ref --db-platform --caches --build --pull --keep --tmux --resume --clean" -- "$cur") )
                                 fi ;;
                    create)      COMPREPLY=( $(compgen -W "--from --tag --no-app --caches" -- "$cur") ) ;;
                    restore)     COMPREPLY=( $(compgen -W "--app --caches --force" -- "$cur") ) ;;
                    rm)          COMPREPLY=( $(compgen -W "--force" -- "$cur") ) ;;
                    add-volumes) COMPREPLY=( $(compgen -W "--from --force gradle_cache maven_cache npm_cache jenkins_data static_data" -- "$cur") ) ;;
                esac
            fi ;;
        scaffold)                  COMPREPLY=( $(compgen -W "--root --no-mounts --dry-run" -- "$cur") ) ;;
        shell-init)                COMPREPLY=( $(compgen -W "bash zsh" -- "$cur") ) ;;
        cert)                      COMPREPLY=( $(compgen -W "show install" -- "$cur") ) ;;
        proxy)                     COMPREPLY=( $(compgen -W "up down status attach --all" -- "$cur") ) ;;
        env)
            if (( COMP_CWORD == argstart )); then
                COMPREPLY=( $(compgen -W "show init get set" -- "$cur") )
            elif [[ "${COMP_WORDS[argstart]}" == init ]]; then
                COMPREPLY=( $(compgen -W "--from --op --no-mounts --force --dry-run mac linux" -- "$cur") )
            fi ;;
        config)
            if (( COMP_CWORD == argstart )); then
                COMPREPLY=( $(compgen -W "ls get set unset path" -- "$cur") )
            else
                COMPREPLY=( $(compgen -W "ZFIN_DEV_ROOT ZFIN_WORKTREES_DIR ZFIN_SEEDS_DIR ZFIN_ARCHIVE_DIR ZFIN_CACHE_DIR ZFIN_SEED ZFIN_FEATURE_BIND ZFIN_FEATURE_DOMAIN ZFIN_PROXY_NETWORK ZFIN_PROXY_HTTP_PORT ZFIN_PROXY_HTTPS_PORT ZFIN_SHARED_PROJECT ZFIN_CLAUDE_TOKEN_FILE ZFIN_TAR_IMAGE" -- "$cur") )
            fi ;;
        feature)
            if (( COMP_CWORD == argstart )); then
                COMPREPLY=( $(compgen -W "new ls rm refresh freeze thaw session" -- "$cur") )
            else
                case "${COMP_WORDS[argstart]}" in
                    ls|list)         COMPREPLY=( $(compgen -W "--all --frozen" -- "$cur") ) ;;
                    new)             COMPREPLY=( $(compgen -W "-y --yes --base --branch --existing-branch --no-existing-branch --seed --no-seed --port-offset --up --no-up --no-app --no-caches --shared-db --no-shared-db --node --no-node --deploy --no-deploy --liquibase --no-liquibase --tmux --no-tmux" -- "$cur") ) ;;
                    rm)              COMPREPLY=( $(compgen -W "$(_z_features) --force --keep-archive --no-session" -- "$cur") ) ;;
                    session)         if (( COMP_CWORD == argstart + 1 )); then
                                         COMPREPLY=( $(compgen -W "export ls" -- "$cur") )
                                     elif [[ "${COMP_WORDS[argstart+1]}" == export ]]; then
                                         COMPREPLY=( $(compgen -W "$(_z_features)" -- "$cur") )
                                     fi ;;
                    freeze)          COMPREPLY=( $(compgen -W "$(_z_features) --caches --compress --no-compress --to --force" -- "$cur") ) ;;
                    thaw)            COMPREPLY=( $(compgen -W "$(_z_features) --from --no-up --no-caches --force" -- "$cur") ) ;;
                    refresh)         COMPREPLY=( $(compgen -W "$(_z_features) --all --dry-run" -- "$cur") ) ;;
                esac
            fi ;;
    esac
}
complete -F _z_complete z
