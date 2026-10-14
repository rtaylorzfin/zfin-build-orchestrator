import jenkins.model.Jenkins
import hudson.security.HudsonPrivateSecurityRealm
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy

// Makes Jenkins honor DISABLE_OIDC, which the ZFIN repo only applies to httpd. Runs on every
// Jenkins start. docker-compose.overlay-feature.yml mounts it read-only as $JENKINS_HOME/init.groovy,
// which Jenkins runs before init.groovy.d/*. Not mounted INTO init.groovy.d: Docker would create
// that directory as root to hold the mount point, and Jenkins (gradle) could then write nothing
// there -- neither the guard below nor `jenkins-cli configure-admin-token`'s script.
//
// DISABLE_OIDC=true  -> swap the OIDC (bouncer) security realm for Jenkins' own user database,
//                       so Jenkins works without keycloak. The OIDC realm and authorization
//                       matrix are saved first so they can be put back. If no local account
//                       exists yet, an 'admin' account is created (password from
//                       JENKINS_ADMIN_PASSWORD, or generated and written to
//                       secrets/initialAdminPassword, where jenkins-cli also picks it up).
// DISABLE_OIDC unset/false -> restore the saved OIDC configuration. If Jenkins is on its own
//                       user database and nothing was saved, shut Jenkins down with
//                       instructions. Loud on purpose: local accounts left in place by mistake
//                       are a security problem, so this must not fail quietly.
//
// THE GUARD COPY. A bind mount leaves only an empty placeholder in jenkins_data, so a volume on
// local accounts that ends up in a stack without this overlay (plain `docker compose`, a seed
// restored into a checkout's own stack) would otherwise keep local accounts and say nothing.
// So while Jenkins is on local accounts, a real copy of this script lives in the volume as
// init.groovy.d/zfin-security-realm-guard.groovy. A base-compose stack passes no DISABLE_OIDC
// to Jenkins, so the guard there restores OIDC or shuts Jenkins down. It deletes itself once
// OIDC is back, and stands aside whenever the mounted script is present: that one has already
// run, is the current version, and refreshes the guard from itself.

def isGuardCopy = false   // the guard is written with this set to true

def jenkins = Jenkins.get()
def initDir = new File(jenkins.rootDir, "init.groovy.d")
def mounted = new File(jenkins.rootDir, "init.groovy")
def guard   = new File(initDir, "zfin-security-realm-guard.groovy")
def backup  = new File(jenkins.rootDir, "zfin-oidc-security.xml")
def disableOidc = System.getenv("DISABLE_OIDC") == "true"
def log = { msg -> println "[zfin-security-realm${isGuardCopy ? ' (guard)' : ''}] ${msg}" }

def mountedText = mounted.isFile() ? mounted.text : ""
if (isGuardCopy && mountedText.trim()) return   // the mounted script already ran and did the work

def writeGuard = {
    if (!mountedText.trim()) return             // we are the guard; it is already in place
    def text = mountedText.replaceFirst(/def isGuardCopy = false/, "def isGuardCopy = true")
    if (!guard.isFile() || guard.text != text) {
        initDir.mkdirs()
        guard.text = text
        log "wrote ${guard}, so this volume restores OIDC even in a stack without the overlay"
    }
}
def removeGuard = {
    if (guard.isFile()) { guard.delete(); log "removed ${guard}" }
}

if (disableOidc) {
    // The guard first: if it cannot be written, this throws with OIDC still in place, rather
    // than leaving local accounts behind with nothing in the volume to undo them.
    writeGuard()
    if (!(jenkins.securityRealm instanceof HudsonPrivateSecurityRealm)) {
        backup.text = Jenkins.XSTREAM2.toXML([
                securityRealm        : jenkins.securityRealm,
                authorizationStrategy: jenkins.authorizationStrategy,
        ])
        // The OIDC matrix grants rights to keycloak groups, which local accounts don't have,
        // so any logged-in local user gets full control instead.
        def authz = new FullControlOnceLoggedInAuthorizationStrategy()
        authz.allowAnonymousRead = false
        jenkins.securityRealm = new HudsonPrivateSecurityRealm(false)
        jenkins.authorizationStrategy = authz
        jenkins.save()
        log "DISABLE_OIDC=true: switched to the Jenkins user database (OIDC config saved to ${backup})"
    }

    def realm = (HudsonPrivateSecurityRealm) jenkins.securityRealm
    if (realm.allUsers.isEmpty()) {
        def password = System.getenv("JENKINS_ADMIN_PASSWORD")
        if (!password) {
            password = UUID.randomUUID().toString().replace('-', '')
            def secret = new File(jenkins.rootDir, "secrets/initialAdminPassword")
            secret.parentFile.mkdirs()
            secret.text = password + "\n"
            secret.setReadable(false, false)
            secret.setReadable(true, true)
            log "created local account 'admin'; password is in ${secret}"
        } else {
            log "created local account 'admin' with the password from JENKINS_ADMIN_PASSWORD"
        }
        realm.createAccount("admin", password)
    }
} else if (jenkins.securityRealm instanceof HudsonPrivateSecurityRealm) {
    if (backup.exists()) {
        def saved = (Map) Jenkins.XSTREAM2.fromXML(backup.text)
        jenkins.securityRealm = saved.securityRealm
        jenkins.authorizationStrategy = saved.authorizationStrategy
        jenkins.save()
        backup.delete()
        log "DISABLE_OIDC is off: restored the saved OIDC security realm"
        removeGuard()
    } else {
        // Refuse to keep running on local accounts when OIDC was asked for: stop Jenkins so
        // the misconfiguration can't go unnoticed. The guard stays, so it keeps refusing.
        writeGuard()
        def config = new File(jenkins.rootDir, "config.xml")
        def msg = """
================================================================================
[zfin-security-realm] ERROR: DISABLE_OIDC is not 'true', but Jenkins is using its
own user database and there is no saved OIDC configuration to restore
(${backup} is missing). Jenkins will now shut down.

To restore OIDC (bouncer) login from server_apps/jenkins/config.xml:
    z stop jenkins
    z run -c "mv ${config} ${config}.local-users"
    z run -c "ant deploy-jobs"
    z up jenkins
(Without z, run the same from docker/ as `docker compose stop jenkins`,
`docker compose run --rm compile bash -lc "..."` and `docker compose up -d jenkins`.)
deploy-jobs only installs config.xml when none exists, hence moving it aside.
Any settings changed through the Jenkins UI must be re-applied afterwards.

To keep using the Jenkins user database instead, set DISABLE_OIDC=true in the
stack's docker/.env (it needs this tool's feature overlay to reach Jenkins).
================================================================================
"""
        System.err.println(msg)
        println msg
        System.exit(1)
    }
} else {
    removeGuard()   // on OIDC: a guard left from an earlier switch has nothing to guard
}
