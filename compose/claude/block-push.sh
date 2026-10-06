#!/usr/bin/env bash
# PreToolUse hook: refuse `git push` from inside the sidecar.
#
# Defence in depth, not the actual guarantee. The real guarantee is structural -- this
# container has no SSH agent socket and no keys, so a push has nothing to authenticate with
# (see Dockerfile). This hook exists so the failure is a clear, early message
# rather than a confusing credential error, and because hooks still run under
# --dangerously-skip-permissions.
#
# Pushing stays a human action on the host, which is ZFIN's standing preference anyway.
set -euo pipefail
input=$(cat)
cmd=$(printf '%s' "$input" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("tool_input",{}).get("command",""))' 2>/dev/null || true)

# `git` as a word, then `push` as a word anywhere after it. Deliberately loose: an earlier
# version only allowed FLAGS between the two and so let `git -C /path push` through, because
# -C takes a value. Since the real guarantee is that this container has no credentials, the
# safe failure direction is over-matching -- `git log --grep push` being denied is a mild
# annoyance, a push slipping past is the thing worth avoiding.
# `git`, then optionally any run of arguments, then `push` as a word. The middle group
# excludes ; & | so a match cannot straddle two commands ("git status; echo push").
#
# Deliberately loose about what those arguments are: an earlier version allowed only FLAGS
# there and let `git -C /path push` through, because -C takes a value. Since the real
# guarantee is that this container holds no credentials, the safe failure direction is
# over-matching -- `git log --grep push` being denied is a mild annoyance; a push slipping
# past is the thing worth avoiding.
if printf '%s' "$cmd" | grep -qE '(^|[;&|[:space:]])git[[:space:]]+([^;&|]*[[:space:]]+)?push([[:space:]]|$)'; then
    printf '%s\n' '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"git push is blocked in the ZFIN sidecar: it has no SSH agent or keys, so a push cannot authenticate. Pushing is a human action on the host."}}'
    exit 0
fi
exit 0
