// Cert -- `z cert`: this host's development TLS certificate.
//
//   z cert                    where the certificate is, creating it if needed, and how to trust it
//   z cert install [<ticket>] install it into a stack: the named feature, else the stack that
//                             owns the working directory. Restart httpd and tomcat afterwards.
//
// One self-signed certificate per host and feature domain (ZFIN_FEATURE_DOMAIN), covering
// zfin.org, *.<domain>, <domain>, localhost and 127.0.0.1. `z feature new` installs it into
// every stack it makes, so trusting it once in a browser or keychain covers every stack. It is
// kept in the dev tree's config/certs/<domain>/ (ZfinUtil.devCertDir), never in a stack.
class Cert {
    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return
        def die = zfinUtil.&die; def info = zfinUtil.&info
        def sub = args ? args[0] : 'show'
        switch (sub) {
            case 'show': case 'path':
                def crt = new File(zfinUtil.ensureDevCert(), 'zfin.org.crt')
                println crt
                println ""
                println "Trust it once and every stack's https URL is accepted:"
                println "  macOS:  security add-trusted-cert -r trustRoot -k ~/Library/Keychains/login.keychain-db ${crt}"
                println "  Linux:  import ${crt} under your browser's certificate settings (Authorities)"
                break
            case 'install':
                def project
                if (args.size() > 1) {
                    def slug = args[1].toLowerCase()
                    zfinUtil.requireFeature(slug)
                    project = zfinUtil.stackSpec(new File(zfinUtil.worktreesDir(), slug))?.project ?: slug
                } else {
                    def top = zfinUtil.captureOutput(['git', 'rev-parse', '--show-toplevel'])
                    project = top ? zfinUtil.stackSpec(new File(top))?.project : null
                    if (!project) die("no stack here -- run it inside a checkout or feature worktree, or name one: z cert install <ticket>", 2)
                }
                if (!zfinUtil.installDevCert(project)) die("could not install the certificate into '$project'")
                info("installed into '$project'. Restart to serve it:  z restart httpd tomcat")
                break
            default:
                die("z cert: unknown '$sub' (show|install)", 2)
        }
    }
}
