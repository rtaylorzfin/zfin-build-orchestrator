#!/usr/bin/env bash
#
# freeze-project.sh -- archive or restore EVERY volume of any Compose project.
#
#   ./freeze-project.sh freeze  <project> <archive-dir> [--compress] [--no-down]
#   ./freeze-project.sh thaw    <project> <archive-dir> [--no-up]
#   ./freeze-project.sh list    <project>
#
# WHY THIS EXISTS SEPARATELY FROM `z feature freeze`.
#
# `z feature freeze` is built around a feature stack: it locates a worktree under the
# worktrees directory, and it captures a FIXED list of ten volumes (pg_data, solr_var, the
# four app volumes, three caches, claude_home) because for a feature stack everything else is
# empty or regenerable.
#
# A long-lived instance like `cell` is neither of those things. Its checkout is not a feature
# worktree, and the compose file declares TWENTY volumes -- jenkins_data, elasticsearch_data,
# certbot_data, static_data, downloads_data and others that a feature stack never fills but an
# instance does. Pointing the feature tooling at it would silently archive half the stack.
#
# So this enumerates volumes by PROJECT LABEL -- whatever is actually there -- and makes no
# assumption about what a stack contains.
#
# NEVER RUN A DATABASE OFF NFS. Archiving to it is fine; restoring back to local volumes is
# fine. Pointing PGDATA at an NFS mount is not: fsync and locking semantics will corrupt it.

set -euo pipefail

usage() { sed -n '3,12p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
[ $# -ge 2 ] || usage
ACTION=$1; PROJECT=$2; shift 2
ARCHIVE=""; COMPRESS=0; DO_DOWN=1; DO_UP=1
case "$ACTION" in
  freeze|thaw) [ $# -ge 1 ] || usage; ARCHIVE=$1; shift ;;
  list) ;;
  *) usage ;;
esac
while [ $# -gt 0 ]; do
  case "$1" in
    --compress)  COMPRESS=1 ;;
    --no-down)   DO_DOWN=0 ;;
    --no-up)     DO_UP=0 ;;
    *) echo "unknown arg: $1" >&2; usage ;;
  esac
  shift
done

LABEL="label=com.docker.compose.project=$PROJECT"
# The compile image is a convenient GNU-tar-with-root container that is already local. Any
# image with tar would do.
TAR_IMAGE="${ZFIN_TAR_IMAGE:-ghcr.io/zfin/zfin-compile:main}"

vols() { docker volume ls -q --filter "$LABEL" | sort; }

case "$ACTION" in

list)
  echo "volumes for project '$PROJECT':"
  docker system df -v 2>/dev/null | awk -v p="^${PROJECT}_" '/^VOLUME NAME/{f=1;next} f&&NF>=3&&$1~p{printf "  %-40s %s\n",$1,$3}'
  ;;

freeze)
  mkdir -p "$ARCHIVE"
  mapfile -t VOLS < <(vols)
  [ ${#VOLS[@]} -gt 0 ] || { echo "no volumes labelled for project '$PROJECT'" >&2; exit 1; }
  echo ">> $PROJECT: ${#VOLS[@]} volume(s) -> $ARCHIVE"

  # 1. STOP THE APP TIER FIRST. Nothing may be writing to the database while it shuts down,
  #    and compose gives no ordering guarantee unless depends_on says so.
  echo ">> stopping app/build tier"
  docker compose -p "$PROJECT" stop httpd tomcat tomcatdebug jenkins compile claude 2>/dev/null || true

  # 2. THEN THE DATA TIER, with a long timeout. The default is 10s; a large postgres will not
  #    finish its shutdown checkpoint in that, and past the timeout docker sends SIGKILL,
  #    leaving a PGDATA that needs crash recovery.
  echo ">> stopping data tier (up to 120s for postgres to checkpoint)"
  docker compose -p "$PROJECT" stop -t 120 db solr 2>/dev/null || true

  # 3. PROVE the database is down before tarring it. Note the 2>&1: postgres logs to stderr,
  #    and `docker logs` passes a container's stderr through on ITS stderr -- a check that
  #    forgets this compares against an empty string and can never pass.
  DB_CID=$(docker ps -aq --filter "$LABEL" --filter "label=com.docker.compose.service=db" | head -1)
  if [ -n "$DB_CID" ]; then
    RUNNING=$(docker inspect "$DB_CID" --format '{{.State.Running}}')
    [ "$RUNNING" = "false" ] || { echo "!! db is still running -- refusing to tar a live PGDATA" >&2; exit 1; }
    CLEAN=0
    for _ in $(seq 1 15); do
      if docker logs --tail 60 "$DB_CID" 2>&1 | grep -q 'database system is shut down'; then CLEAN=1; break; fi
      sleep 1
    done
    if [ "$CLEAN" = 1 ]; then echo ">> db shut down cleanly (checkpoint complete)"
    else
      echo "!! never saw 'database system is shut down'. The archive would capture a PGDATA" >&2
      echo "   postgres did not close cleanly. Investigate before continuing." >&2
      docker logs --tail 15 "$DB_CID" 2>&1 | sed 's/^/     /' >&2
      exit 1
    fi
  fi

  # 4. Capture. Compression is worth it TO NFS even though it is slower locally: you are
  #    paying for bytes over the wire, not just CPU. pigz is used when the image has it.
  if [ "$COMPRESS" = 1 ]; then
    if docker run --rm --entrypoint sh "$TAR_IMAGE" -c 'command -v pigz' >/dev/null 2>&1; then
      TAR_ARGS=(-I 'pigz -1' -cf); EXT=tgz
    else
      TAR_ARGS=(-I 'gzip -1' -cf); EXT=tgz
    fi
  else
    TAR_ARGS=(-cf); EXT=tar
  fi

  : > "$ARCHIVE/MANIFEST"
  for v in "${VOLS[@]}"; do
    out="$v.$EXT"
    echo ">> capturing $v -> $out"
    docker run --rm -u 0 --entrypoint tar \
      -v "$v:/data:ro" -v "$ARCHIVE:/out" "$TAR_IMAGE" "${TAR_ARGS[@]}" "/out/$out" -C /data .
    echo "$v  $out  $(stat -c%s "$ARCHIVE/$out" 2>/dev/null || stat -f%z "$ARCHIVE/$out")" >> "$ARCHIVE/MANIFEST"
  done
  { echo "# project: $PROJECT"; echo "# frozen:  $(date '+%F %T')"; } >> "$ARCHIVE/MANIFEST"
  echo ">> wrote $ARCHIVE/MANIFEST"

  # 5. Only NOW release the disk -- never before the tarballs exist and are listed.
  if [ "$DO_DOWN" = 1 ]; then
    echo ">> down -v (containers + volumes; the archive is the copy now)"
    docker compose -p "$PROJECT" down -v
  else
    echo ">> --no-down: stack left stopped, volumes intact"
  fi
  ;;

thaw)
  [ -f "$ARCHIVE/MANIFEST" ] || { echo "no MANIFEST in $ARCHIVE" >&2; exit 1; }
  while read -r v out _; do
    case "$v" in '#'*|'') continue ;; esac
    [ -f "$ARCHIVE/$out" ] || { echo "!! missing $out" >&2; exit 1; }
    echo ">> restoring $v from $out"
    docker volume create "$v" >/dev/null
    # `xf`, not `xzf`: GNU tar sniffs the compression, so one path handles .tar and .tgz.
    docker run --rm -u 0 --entrypoint tar \
      -v "$v:/data" -v "$ARCHIVE:/in:ro" "$TAR_IMAGE" xf "/in/$out" -C /data
  done < "$ARCHIVE/MANIFEST"
  [ "$DO_UP" = 1 ] && { echo ">> starting $PROJECT"; docker compose -p "$PROJECT" up -d; } || echo ">> --no-up: volumes restored, stack left down"
  ;;

esac
