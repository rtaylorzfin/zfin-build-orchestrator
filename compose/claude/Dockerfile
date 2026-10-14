# The per-feature Claude sidecar: `./z run claude` in a feature worktree.
#
# Built ON the compile image rather than a minimal node base, deliberately. A minimal image
# would be more isolated but could not compile, test or deploy -- which makes the agent useless
# for the work we actually want it doing and pushes every build back to a human at the host
# shell. `compile` is already credential-free (no /var/run/docker.sock, no SSH agent socket, no
# ~/.ssh -- see docker/docker-compose.yml), so extending it costs little isolation and buys a
# fully capable agent.
#
# What this container still CANNOT do, structurally rather than by policy:
#   - push, or SSH anywhere:  no agent socket and no keys exist in it
#   - drive the host docker daemon:  no socket is mounted
#   - reach another feature's worktree:  only this stack's source is mounted
#   - restart tomcat:  that is a host operation (`z restart tomcat`), and buildfiles/tomcat.xml
#     <fail>s if you try it from in here
ARG ZFIN_RELEASE=main
ARG DOCKER_ARCH=
FROM ghcr.io/zfin/zfin-compile:${ZFIN_RELEASE}${DOCKER_ARCH}

USER root

# Claude Code updates ITSELF: its auto-updater runs `npm install -g` as whoever runs claude.
# Under the default global prefix (/usr/lib/node_modules, root-owned) that fails as gradle, so
# the CLI gets a prefix of its own, owned by gradle; NPM_CONFIG_PREFIX (set below, after the
# Playwright install, which stays in /usr) points the updater's npm -g at it. The version built
# here is only the starting point: the claude_code volume (docker-compose.overlay-feature.yml)
# mounts over this prefix, so an update outlives the `--rm` container it was installed in.
ARG CLAUDE_CODE_VERSION=latest
# The package's postinstall puts the platform-native binary in place; without it the CLI errors
# with "claude native binary not installed". npm 12 blocks install scripts not named in
# allow-scripts, so allow this one -- as ENV, because the updater's own `npm install -g` needs
# it as much as this build does.
ENV NPM_CONFIG_ALLOW_SCRIPTS=@anthropic-ai/claude-code
RUN NPM_CONFIG_PREFIX=/opt/claude-code npm install -g @anthropic-ai/claude-code@${CLAUDE_CODE_VERSION} \
    && npm cache clean --force \
    && chown -R gradle /opt/claude-code

# A headless browser, so the agent can check a page it changed the way a user would see it --
# JavaScript, rendering, console errors -- rather than stopping at a 200 from curl. Two pieces:
#   - the Playwright MCP server, which gives `claude` browser tools (navigate, click, snapshot,
#     screenshot); claude_profile registers it
#   - Playwright's own Chromium build, plus the system libraries it needs (--with-deps)
# Playwright's Chromium rather than Google Chrome or Ubuntu's: Chrome ships no Linux arm64
# build, and noble's chromium package is a snap stub that cannot run in a container.
# The browser is installed by the MCP server's OWN bundled playwright, because each Playwright
# release expects one exact browser revision; installing from a separate `playwright` package
# could fetch a revision the server then cannot find. The `playwright` wrapper exposes that
# same CLI (e.g. `playwright screenshot URL out.png`), so both always agree.
# Browsers go in a shared path rather than root's ~/.cache, where gradle could not read them.
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/ms-playwright
ARG PLAYWRIGHT_MCP_VERSION=0.0.83
RUN npm install -g @playwright/mcp@${PLAYWRIGHT_MCP_VERSION} \
    && pw="$(npm root -g)/@playwright/mcp/node_modules/playwright/cli.js" \
    && node "$pw" install --with-deps chromium \
    && printf '#!/bin/sh\nexec node %s "$@"\n' "$pw" > /usr/local/bin/playwright \
    && chmod 0755 /usr/local/bin/playwright \
    && npm cache clean --force \
    && rm -rf /var/lib/apt/lists/*

# Only now, so the Playwright install above landed in /usr and not in the updatable prefix.
# ENV rather than the profile: it must hold for whatever runs claude, login shell or not.
ENV NPM_CONFIG_PREFIX=/opt/claude-code
ENV PATH=/opt/claude-code/bin:${PATH}

# Defaults seeded into ~/.claude on first run. They cannot simply live at ~/.claude/ in the
# image: the claude_home VOLUME mounts over that path, so anything baked there is hidden.
# The profile below copies them in when the volume is empty, which also means an existing
# volume's settings are never overwritten.
COPY settings.json /opt/zfin/claude-defaults/settings.json
COPY block-push.sh /opt/zfin/claude-defaults/block-push.sh
# Seeded as ~/.claude/CLAUDE.md -- user memory that exists ONLY in this container, so it can
# say "you are in the sidecar, here is what differs" without qualifying the project CLAUDE.md
# for host sessions that do not need it.
COPY CLAUDE.sidecar.md /opt/zfin/claude-defaults/CLAUDE.md
RUN chmod 0755 /opt/zfin/claude-defaults/block-push.sh \
    && chown -R gradle /opt/zfin/claude-defaults

# The inherited compile profile runs generate_base.sh on every login shell: it creates the
# stack's TLS key and certificate, the Postgres password file and /mnt/research directories.
# The sidecar needs none of them -- it serves no TLS, and the stack's own compile container
# has already made the rest -- and running it here would mean mounting the volumes that hold
# the stack's private key. So the sidecar's profile does not call it.
RUN sed -i '\#docker/compile/generate_base.sh#d' /home/gradle/.profile

# Create ~/.claude in the IMAGE, owned by gradle, before the claude_home volume mounts over
# it. Docker seeds a new named volume from the image's content at that path -- including its
# ownership -- but when the path does not exist it creates the mount point as ROOT, and the
# unprivileged user then cannot write its own session directory.
RUN mkdir -p /home/gradle/.claude && chown gradle /home/gradle/.claude

ADD claude_profile /tmp/claude_profile
RUN cat /tmp/claude_profile >> /home/gradle/.profile && rm /tmp/claude_profile

USER gradle
