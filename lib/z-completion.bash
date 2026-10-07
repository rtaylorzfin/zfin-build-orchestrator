# Bash tab-completion for `z`. `z shell-init` prints the line that sources it.
#   z <TAB>          -> subcommands           z build <TAB>   -> phases
#   z run <TAB>      -> services              z feature <TAB> -> new | ls | rm | refresh | ...
_z_complete() {
    local cur sub argstart=2
    cur="${COMP_WORDS[COMP_CWORD]}"
    if (( COMP_CWORD == 1 )); then
        COMPREPLY=( $(compgen -W "run exec up stop down pull log restart status build feature seed shared scaffold fresh-install shell-init config cert help" -- "$cur") )
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
                COMPREPLY=( $(compgen -W "new create build restore ls rm" -- "$cur") )
            else
                case "${COMP_WORDS[argstart]}" in
                    new|create)  COMPREPLY=( $(compgen -W "--from --tag --no-app --caches" -- "$cur") ) ;;
                    build)       COMPREPLY=( $(compgen -W "--db --solr --tag --ref --db-platform --caches --build --pull --keep --tmux --resume --clean" -- "$cur") ) ;;
                    restore)     COMPREPLY=( $(compgen -W "--app --caches --force" -- "$cur") ) ;;
                    rm)          COMPREPLY=( $(compgen -W "--force" -- "$cur") ) ;;
                esac
            fi ;;
        scaffold)                  COMPREPLY=( $(compgen -W "--root --no-mounts --dry-run" -- "$cur") ) ;;
        shell-init)                COMPREPLY=( $(compgen -W "bash zsh" -- "$cur") ) ;;
        cert)                      COMPREPLY=( $(compgen -W "show install" -- "$cur") ) ;;
        config)
            if (( COMP_CWORD == argstart )); then
                COMPREPLY=( $(compgen -W "ls get set unset path" -- "$cur") )
            else
                COMPREPLY=( $(compgen -W "ZFIN_DEV_ROOT ZFIN_WORKTREES_DIR ZFIN_ARCHIVE_DIR ZFIN_CACHE_DIR ZFIN_SEED ZFIN_FEATURE_BIND ZFIN_FEATURE_DOMAIN ZFIN_PROXY_NETWORK ZFIN_SHARED_PROJECT ZFIN_CLAUDE_TOKEN_FILE ZFIN_TAR_IMAGE" -- "$cur") )
            fi ;;
        feature)
            if (( COMP_CWORD == argstart )); then
                COMPREPLY=( $(compgen -W "new ls rm refresh freeze thaw session" -- "$cur") )
            else
                case "${COMP_WORDS[argstart]}" in
                    ls|list)         COMPREPLY=( $(compgen -W "--all --frozen" -- "$cur") ) ;;
                    new)             COMPREPLY=( $(compgen -W "-y --yes --base --branch --existing-branch --no-existing-branch --seed --no-seed --port-offset --up --no-up --no-app --no-caches --shared-db --no-shared-db --node --no-node --deploy --no-deploy --liquibase --no-liquibase --tmux --no-tmux" -- "$cur") ) ;;
                    rm)              COMPREPLY=( $(compgen -W "--force --keep-archive --no-session" -- "$cur") ) ;;
                    session)         COMPREPLY=( $(compgen -W "export ls" -- "$cur") ) ;;
                    freeze)          COMPREPLY=( $(compgen -W "--caches --compress --no-compress --to --force" -- "$cur") ) ;;
                    thaw)            COMPREPLY=( $(compgen -W "--from --no-up --no-caches --force" -- "$cur") ) ;;
                    refresh)         COMPREPLY=( $(compgen -W "--all --dry-run" -- "$cur") ) ;;
                esac
            fi ;;
    esac
}
complete -F _z_complete z
