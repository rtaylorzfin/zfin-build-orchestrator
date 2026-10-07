// StackConfig -- the ONE home for ZFIN-specific policy: image names, host/naming patterns,
// which services play which role, and the warm-volume classes. Everything else in
// lib/ is generic mechanism (ZfinUtil + the command classes). When ZFIN's
// conventions change, they change HERE.
//
// It's all `static` -- these are constants and pure functions of tag/slug/release, not
// per-invocation state -- so commands just reference `StackConfig.X` with no wiring. This is
// the "light seam": the policy/mechanism split is expressed purely by WHERE a literal lives.
class StackConfig {
    // A stock ZFIN image as compose names it: `ghcr.io/zfin/zfin-<name>:<release><arch>`, where
    // arch is DOCKER_ARCH (e.g. `-arm64`, empty for the amd64 default). The suffix matters: on
    // an arm64 host the bare tag is the amd64 image, which runs under emulation -- and the
    // amd64 compile image there predated pigz, so every capture fell back to one-core gzip.
    static String image(String name, String release, String arch = '') { "ghcr.io/zfin/zfin-$name:$release${arch ?: ''}" }
    // The DOCKER_ARCH-style tag suffix for a db platform: ZFIN tags amd64 bare and arm64 as
    // `-arm64`. Null for a platform ZFIN does not publish.
    static String archSuffix(String platform) {
        [ 'linux/amd64': '', 'linux/arm64': '-arm64' ][platform]
    }
    // Compile image used to run `tar` during capture/restore (GNU tar, root, always local).
    static String compileImage(String release, String arch = '') { image('compile', release, arch) }

    // Feature hostnames are <slug>.<domain>. The domain is a DEFAULT here; resolution
    // (environment -> config file -> this) is ZfinUtil.featureDomain(). Each stack's host is
    // frozen into its own .env when it is made, so changing the default renames nothing.
    // The versions of the ZFIN repo's compose file this tooling works with: the
    // `x-zfin-compose-version: N` its docker/docker-compose.yml declares (see
    // ZfinUtil.checkComposeVersion).
    // Add a version here once the tooling handles it; drop one once no checkout still has it.
    static final List<Integer> COMPOSE_VERSIONS_SUPPORTED = [1]

    // HOST SETTINGS: how this host runs the tooling, as opposed to what a stack is. They live in
    // the dev tree's zfin-dev.env or the user's file (see ZfinUtil's host-settings section),
    // never in a ZFIN checkout's docker/.env or copied into a feature's: they are inputs to
    // `z feature new` and to commands with no feature at all, and a copy per stack would go
    // stale the first time one changes.
    static final Map<String, String> HOST_SETTINGS = [
        ZFIN_DEV_ROOT         : 'the dev tree: where zfin-dev.env is, found by walking up (or --user, from outside one)',
        ZFIN_WORKTREES_DIR    : 'feature worktrees (default: $ZFIN_DEV_ROOT/worktrees)',
        ZFIN_ARCHIVE_DIR      : 'freeze archives, seeds, session history (default: $ZFIN_DEV_ROOT/archive)',
        ZFIN_CACHE_DIR        : 'download caches (default: $ZFIN_DEV_ROOT/cache)',
        ZFIN_SEED             : 'seed a new feature or the shared stack restores (default: the newest)',
        ZFIN_FEATURE_BIND     : 'address feature stacks publish their ports on (default: 127.0.0.1)',
        ZFIN_FEATURE_DOMAIN   : 'feature hostnames are <slug>.<this> (default: zfin.test)',
        ZFIN_PROXY_NETWORK    : 'the host\'s own nginx-proxy network for new stacks to join (default: z proxy\'s, while it runs)',
        ZFIN_PROXY_HTTP_PORT  : 'where `z proxy` listens for http, on ZFIN_FEATURE_BIND (default: 80)',
        ZFIN_PROXY_HTTPS_PORT : 'where `z proxy` listens for https, on ZFIN_FEATURE_BIND (default: 443)',
        ZFIN_SHARED_PROJECT   : 'the stack --shared-db features attach to (default: zfin_shared)',
        ZFIN_CLAUDE_TOKEN_FILE: 'the Claude sidecar\'s token (default: ~/.zfin/claude-token)',
        ZFIN_TAR_IMAGE        : 'image that tars volumes (default: the compile image)',
    ]

    static final String FEATURE_DOMAIN_DEFAULT = 'zfin.test'

    // The INSTANCE every stack this tooling makes generates its properties as (written into the
    // stack's .env by NewFeature and SeedBuild). Deliberately NOT an instance listed in the ZFIN
    // repo's commons/env/all-properties.yml: an unlisted instance gets the development defaults,
    // including SMTP_HOST=mailpit, so the stack's mail is caught rather than delivered. Inheriting
    // the base checkout's instance (coral, say) would instead pull in that host's overrides.
    // DOMAIN_NAME is the default zfin.org, so absolute links the app builds point at production.
    static final String FEATURE_INSTANCE = 'feature'

    // The main checkout's own stack. A last-resort fallback only: every command that acts on a
    // stack resolves one from the working directory first.
    static final String BASE_PROJECT        = 'zfin_org'

    // Published ports for a feature stack: base + ZFIN_PORT_OFFSET, one offset per stack.
    static final Map<String, Integer> PORT_BASES = [db: 5432, debug: 5000, jenkins: 9499,
                                                    http: 8080, https: 8443]

    // What a feature's .env carries beyond its identity keys, and the ONLY definition of it:
    // `z feature new` writes these at creation and `z feature refresh` backfills them into a
    // stack made before a key existed. Two writers, one list, so they cannot drift.
    //
    // They are VALUES on purpose. Anything a value can express belongs in the stack's own .env
    // and is read by docker-compose.yml; the overlay keeps only what a value cannot say.

    // What makes a seed's APP TIER usable. Checked on BOTH sides -- `z seed create` warns that
    // the seed it just wrote is thin, and `z feature new --seed` warns before restoring one --
    // because a seed outlives the session that made it and the create-time warning scrolls away
    // with it. The failure it prevents points nowhere near the cause: httpd includes
    // $TARGETROOT/server_apps/apache/inc-redirect out of www_data, so an empty www_data kills it
    // with an Apache syntax error, and an empty catalina_base kills tomcat with a missing
    // server.xml. Only these two are checked: keystore and tls_certs are legitimately a few KB,
    // so a size floor on them would cry wolf on every healthy seed.
    static final List<String> APP_TIER_VOLUMES = ['www_data', 'catalina_base']
    static final long         APP_TIER_MIN_BYTES = 1_000_000

    static final String FEATURE_SOLR_MEM  = '6g'
    static final String FEATURE_SOLR_HEAP = '4g'
    static Map<String, String> featureEnv(String gitCommon = null, String gitDir = null) {
        [ DOCKER_SOLR_MEM_LIMIT: FEATURE_SOLR_MEM,   // prod sizing (16g/12g) will not start on a
          DOCKER_SOLR_HEAP     : FEATURE_SOLR_HEAP, // host shared with other stacks
          // Where this tree's git lives. The containers bind both at these exact paths -- a
          // worktree's .git is a FILE pointing into the main repo, so git cannot work in a
          // container that has only the worktree. See docker-compose.overlay-worktree.yml.
          DOCKER_GIT_COMMON_DIR  : gitCommon,
          DOCKER_GIT_WORKTREE_DIR: gitDir ].findAll { k, v -> v != null }
    }

    // NOTE: no absolute directory defaults live here. Where a host keeps its archives and
    // worktrees is declared by ZFIN_DEV_ROOT (see ZfinUtil.devRoot) -- an absolute default is a
    // guess about someone else's machine that fails silently.

    // Data-tier readiness probe. The SAME check also appears as db's `healthcheck:` in
    // docker-compose.yml, which is what the app tier's `condition: service_healthy` waits on;
    // YAML cannot import this file, so the two are kept in step by hand and both carry a note.
    // This argv form exists because it covers the case compose cannot: a postgres started
    // OUTSIDE compose (`z seed create`'s throwaway container for the WAL trim) has no compose
    // healthcheck to read.
    static final List<String> DB_PROBE = ['pg_isready', '-U', 'postgres', '-d', 'zfindb']
    static List<String> dbHealthCheck(String container) {
        ['docker', 'exec', container] + DB_PROBE
    }

    // Service roles.
    static final List<String> DATA_SERVICES  = ['db', 'solr']
    static final List<String> APP_SERVICES   = ['tomcat', 'httpd']
    // The service that answers a stack's URL. `z feature ls` calls a stack "up" only when THIS
    // is running, because that column sits next to the URL and has to agree with it.
    static final String       WEB_SERVICE    = 'httpd'
    static final String        BUILD_SERVICE = 'compile'

    // Warm-volume contract: the SINGLE source read by Seed (producer) AND NewFeature
    // (consumer), so the lists + on-disk tarball layout can never drift apart.
    static final List<String> APP_VOLS   = ['www_data', 'catalina_base', 'keystore', 'tls_certs']
    static final List<String> CACHE_VOLS = ['gradle_cache', 'maven_cache', 'npm_cache']

    // The single-owner data volumes -- the ~19G + ~9G that make a feature stack expensive,
    // and the reason `z feature freeze` exists. A --shared-db stack has NEITHER (its data
    // lives in the zfin_shared project), which is why freeze derives its volume list from the
    // stack's composition rather than from a fixed list.
    static final List<String> DATA_VOLS  = ['pg_data', 'solr_var']

    // The agent's per-stack home, captured whenever it exists so a frozen stack keeps its
    // session history. Absent until the claude sidecar lands; freeze skips what is not there.
    static final String CLAUDE_VOL = 'claude_home'

    // Jenkins' home: jobs, plugins, and secrets/initialAdminPassword -- the credential
    // `jenkins-cli` authenticates with. Captured like CLAUDE_VOL rather than added to APP_VOLS,
    // because APP_VOLS is also the warm-app test (`haveTars(tag, APP_VOLS)` requires ALL of
    // them) and adding a member there would make every seed captured before today read as cold.
    // Freeze without it discarded a stack's whole Jenkins setup on `down -v`, and thaw could
    // not bring it back.
    static final String JENKINS_VOL = 'jenkins_data'
    static final String CLAUDE_SERVICE = 'claude'

    // Where the host keeps the sidecar's auth token (`claude setup-token`).
    //
    // Under $HOME, deliberately NOT under ZFIN_DEV_ROOT. A Claude token is a PERSONAL
    // credential, and on a shared host that tree is group-writable so developers can
    // collaborate on worktrees: a token there would hand one person's subscription to everyone
    // with an account. Override with the ZFIN_CLAUDE_TOKEN_FILE host setting.
    static final String CLAUDE_TOKEN_FILE_DEFAULT = "${System.getProperty('user.home')}/.zfin/claude-token"

    static final String FREEZE_MANIFEST = 'freeze.json'
    static final String SEED_MANIFEST   = 'seed.json'
}
