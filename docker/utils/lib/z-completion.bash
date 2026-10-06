# Bash tab-completion for `z`. Nothing sources this for you any more -- add it to ~/.bashrc:
#   source /path/to/checkout/docker/utils/lib/z-completion.bash
# `./z` completes too: bash falls back to the basename's compspec when the word has a slash.
#   z <TAB>          -> subcommands           z build <TAB>   -> phases
#   z run <TAB>      -> services              z feature <TAB> -> new | ls | rm | refresh | ...
# The short names below (zrun/zfeature/...) are no longer defined for you; they still complete
# if you define them yourself, e.g. `zrun() { ./z run "$@"; }`. For `z` the subcommand is $1;
# for a short name the command word IS the subcommand (basename minus the leading z).
_z_complete() {
    local cur base sub argstart
    cur="${COMP_WORDS[COMP_CWORD]}"
    # Basename, so `./z` and `/path/to/z` behave like `z`. bash already hands a slashed word to
    # the `z` compspec; without stripping the directory here the comparison below misses and the
    # word falls into the short-name branch, where `./z` becomes the subcommand and matches
    # nothing. `./z` is how the repo documents every command, so that is the common case.
    base="${COMP_WORDS[0]##*/}"

    if [[ "$base" == "z" ]]; then
        if (( COMP_CWORD == 1 )); then
            COMPREPLY=( $(compgen -W "run exec up stop down pull log restart status build feature seed shared scaffold fresh-install shell-init help" -- "$cur") )
            return
        fi
        sub="${COMP_WORDS[1]}"; argstart=2
    else
        sub="${base#z}"; argstart=1        # zrun->run, zfeature->feature, zbuild->build, ...
    fi

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
complete -F _z_complete z zrun zexec zup zstop zdown zpull zlog zrestart zstatus zhelp zfeature zbuild
