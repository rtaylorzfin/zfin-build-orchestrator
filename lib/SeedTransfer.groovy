// SeedTransfer -- `z seed push` / `z seed pull`: copy a seed from one host to another.
//
//   z seed push <tag> <host> [--remote-dir DIR] [--force]    this host's seed -> <host>
//   z seed pull <host> <tag> [--remote-dir DIR] [--force]    <host>'s seed -> this host
//
//   <host>          anything ssh takes: a name from ~/.ssh/config, host, user@host.
//   --remote-dir    the seeds directory on <host>, as an absolute path. Default: the same
//                   path as this host's ($ZFIN_SEEDS_DIR, else <dev root>/seeds) -- the dev
//                   trees sit at the same place on every ZFIN host. Give it when <host> keeps
//                   its seeds elsewhere.
//   --force         replace a seed of that tag already at the destination.
//
// Source first, destination second, as with cp and scp. Both directions run from ONE side,
// because reachability is often one-way: a Mac reaches a VM over ssh, and nothing reaches the
// Mac. Whichever host you are on, push sends and pull fetches.
//
// HOW. rsync over ssh into <seeds>/.incoming-<tag>/ at the destination, then a SHA-256 check
// of every tarball there against seed.json, then a rename to <seeds>/<tag>/. So a seed is only
// ever under its own name once it has arrived whole and verified; an interrupted transfer
// leaves .incoming-<tag>, which `z seed ls` mentions and which the same command, run again,
// resumes (rsync --partial keeps half-sent files and skips whole ones). Nothing needs `z` on
// the other host -- only ssh, rsync, and sha256sum (or shasum, on a Mac).
//
// Restore-time checks still apply at the far end: `z feature new` refuses a seed whose
// postgres major or platform does not fit that host's db image. That matters between a Mac
// and a VM only when the Mac's db image is arm64: one whose docker/.env sets
// DOCKER_DB_PLATFORM=linux/amd64 (as the VMs' setup does) writes seeds the VMs take as they
// are. ZFIN_ALLOW_PLATFORM_MISMATCH=1 overrides the check.
//
// THE DATA IS REAL ZFIN DATA. This copies it between hosts you can already ssh to, over ssh,
// into a seeds directory there -- storage you control on both ends. It is not an upload
// path: there is still no way to put a seed in a registry or anywhere public, and that stays
// so (docs/dev-stacks.md, "Data-sensitivity guardrail").
class SeedTransfer {
    def run(List args, ZfinUtil zfinUtil) {
        def die = zfinUtil.&die
        def sub = args[0]
        def rest = args.drop(1)
        if (zfinUtil.helpRequested(rest, this)) return

        String remoteDir = null
        boolean force = false
        def pos = []
        for (int i = 0; i < rest.size(); i++) {
            switch (rest[i]) {
                case '--remote-dir': remoteDir = rest[++i]; break
                case '--force': case '-f': force = true; break
                default:
                    if (rest[i].startsWith('-')) die("z seed $sub: unknown arg '${rest[i]}'", 2)
                    pos << rest[i]
            }
        }
        def usage = sub == 'push' ? "z seed push <tag> <host> [--remote-dir DIR] [--force]"
                                  : "z seed pull <host> <tag> [--remote-dir DIR] [--force]"
        if (pos.size() != 2) die("usage: $usage", 2)
        def (tag, host) = sub == 'push' ? [pos[0], pos[1]] : [pos[1], pos[0]]
        // Both end up in paths that go through a shell on the far side (rsync's, and ours).
        if (!(tag ==~ /[A-Za-z0-9][A-Za-z0-9._-]*/)) die("'$tag' is not a seed tag (letters, digits, '.', '_', '-')", 2)
        ['ssh', 'rsync'].each { if (!zfinUtil.onPath(it)) die("z seed $sub needs $it on this host") }

        def localDir = zfinUtil.seedsDir()
        remoteDir = (remoteDir ?: localDir.path).replaceFirst('/+$', '')
        // Absolute, and plain: it is single-quoted into commands for the far side's shell (so a
        // ~ would never expand) and handed to rsync, which passes it through that shell too.
        if (!(remoteDir ==~ /\/[A-Za-z0-9._\/-]*/))
            die("--remote-dir '$remoteDir': an absolute path of letters, digits and . _ / - (it goes through a shell on $host)", 2)

        def t = new Transfer(zfinUtil: zfinUtil, tag: tag, host: host, rdir: remoteDir, ldir: localDir, force: force)
        sub == 'push' ? t.push() : t.pull()
    }

    private static class Transfer {
        ZfinUtil zfinUtil
        String tag, host, rdir
        File ldir
        boolean force

        String getIncoming() { Seed.INCOMING + tag }
        String q(String s) { ZfinUtil.shq(s) }

        /** Run `script` on the far side; its trimmed stdout. ssh's own prompts (a password, a new
         *  host key) still reach the terminal: ssh asks on /dev/tty, not on the pipe. */
        String remote(String script) { zfinUtil.captureOutput(['ssh', host, script]) }

        /** What the far side has: 'nodir', 'norsync', or 'present'/'absent' for the tag, with
         *  'incoming' appended when an earlier transfer stopped part-way. Null: ssh failed. */
        String probe() {
            def out = remote("cd ${q(rdir)} 2>/dev/null || { echo nodir; exit 0; }; " +
                             "command -v rsync >/dev/null || { echo norsync; exit 0; }; " +
                             "if [ -f ${q(tag)}/seed.json ]; then printf present; else printf absent; fi; " +
                             "[ -d ${q(incoming)} ] && printf ' incoming'; echo")
            out ?: null
        }

        String need(String state) {
            def die = zfinUtil.&die
            if (state == null) die("could not reach $host over ssh (try:  ssh $host true)")
            if (state == 'nodir') die("$host has no seeds directory at $rdir.\n" +
                                      "   If it keeps them elsewhere, say where:  --remote-dir <dir>")
            if (state == 'norsync') die("$host has no rsync on its PATH (install it there; this needs it on both ends)")
            state
        }

        void push() {
            def die = zfinUtil.&die; def info = zfinUtil.&info; def runCommand = zfinUtil.&runCommand
            def seed = new File(ldir, tag)
            def m = zfinUtil.readSeedManifest(seed)
            if (!m) die("no seed '$tag' here at $seed (z seed ls lists them)")
            // Sizes only: a seed cut short here would arrive cut short there.
            def damaged = zfinUtil.seedProblems(seed, m)
            if (damaged) die("seed '$tag' does not match its manifest here:\n   ${damaged.join('\n   ')}\n" +
                             "   z seed verify $tag; a damaged seed has to be rebuilt or copied again.")
            def state = need(probe())
            if (state.startsWith('present') && !force)
                die("$host already has seed '$tag' in $rdir. Pass --force to replace it.")
            if (state.contains('incoming')) info("resuming an earlier, unfinished push to $host")

            info(String.format("push seed '%s' (%.1fG) -> %s:%s/%s", tag, bytes(seed) / 1073741824.0, host, rdir, tag))
            def timer = zfinUtil.stepTimer()
            if (rsync("${seed.path}/", "${host}:${rdir}/${incoming}/") != 0)
                die("rsync stopped. Run the same push again to resume it.")
            timer.mark('transfer')

            info("verifying on $host (SHA-256 of each tarball against seed.json)")
            def check = "cd ${q(rdir + '/' + incoming)} && " +
                        "if command -v sha256sum >/dev/null; then S=sha256sum; else S='shasum -a 256'; fi && " +
                        "printf '%s\\n' ${sums(m).collect { q(it) }.join(' ')} | \$S -c -"
            if (runCommand(['ssh', host, check], [check: false]) != 0)
                die("seed '$tag' did not arrive intact on $host -- left in $rdir/$incoming.\n" +
                    "   Run the push again: rsync re-sends what differs.")
            timer.mark('verify')

            def swap = "cd ${q(rdir)} && " + (force ? "rm -rf ${q(tag)} && " : "") + "mv ${q(incoming)} ${q(tag)}"
            if (runCommand(['ssh', host, swap], [check: false]) != 0)
                die("could not move $rdir/$incoming into place on $host")
            info("seed '$tag' is on $host. There:  z feature new <ticket> --seed $tag")
            if (m.platform) info("its data directory is ${m.platform}; z feature new there refuses it if that host's db is not")
            timer.report("seed push '$tag' timing")
        }

        void pull() {
            def die = zfinUtil.&die; def info = zfinUtil.&info
            def state = need(probe())
            if (!state.startsWith('present')) die("$host has no seed '$tag' in $rdir")
            def dest = new File(ldir, tag)
            if (dest.exists() && !force) die("seed '$tag' is already here ($dest). Pass --force to replace it.")
            def work = new File(ldir, incoming)
            if (work.isDirectory()) info("resuming an earlier, unfinished pull ($work)")

            info("pull seed '$tag' <- ${host}:${rdir}/${tag}")
            def timer = zfinUtil.stepTimer()
            work.mkdirs()
            if (rsync("${host}:${rdir}/${tag}/", "${work.path}/") != 0)
                die("rsync stopped. Run the same pull again to resume it.")
            timer.mark('transfer')

            def m = zfinUtil.readSeedManifest(work)
            if (!m) die("what arrived in $work has no readable ${StackConfig.SEED_MANIFEST}")
            info("verifying (SHA-256 of each tarball against seed.json)")
            def bad = []
            ((m.volumes ?: []) as List).each { v ->
                def p = zfinUtil.seedProblems(work, m, [v.name as String], true)
                bad.addAll(p)
                info(String.format("  %-16s %s", v.name, p ? '!! ' + p[0] : 'ok'))
            }
            if (bad) die("seed '$tag' did not arrive intact -- left in $work.\n" +
                         "   Run the pull again: rsync re-fetches what differs.")
            timer.mark('verify')

            if (dest.exists() && !dest.deleteDir()) die("could not remove the old $dest")
            java.nio.file.Files.move(work.toPath(), dest.toPath())
            info("seed '$tag' is here: $dest")
            def plat = zfinUtil.seedPlatformProblem(tag)
            if (plat) System.err.println("!! note: " + plat.readLines()[0] + "\n   z feature new will refuse it on this host.")
            info("use it with:  z feature new <ticket> --seed $tag")
            timer.report("seed pull '$tag' timing")
        }

        /** "<sha256>  <file>" per tarball, the format sha256sum -c and shasum -c both read. */
        List<String> sums(Map m) {
            ((m.volumes ?: []) as List).findAll { it.sha256 && it.file }.collect { "${it.sha256}  ${it.file}".toString() }
        }

        long bytes(File d) { (d.listFiles() ?: []).findAll { it.isFile() }.sum(0L) { it.length() } as long }

        /** -a keeps the mtimes, which is what lets a re-run skip the files that already made it;
         *  --partial keeps a half-sent tarball to continue from. Flags only from rsync 2.6.9,
         *  which is what a Mac has (openrsync). */
        int rsync(String from, String to) {
            zfinUtil.runCommand(['rsync', '-a', '--partial', '--progress', '--exclude', '*.part', from, to], [check: false])
        }
    }
}
