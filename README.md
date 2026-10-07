# zfin-build-orchestrator

Per-feature dev stacks for the ZFIN repo: seeds, feature stacks, freeze/thaw, a shared
db+solr, and a Claude sidecar, driven from one front door, `z`. Opt-in: install it once,
outside any ZFIN checkout, and point it at the checkouts you want to run.

The ZFIN repo provides the hooks this tooling relies on (per-stack ports, memory, the
healthchecks); nothing in it requires this tooling.

Groovy only, run on the host. Needs `groovy`, Docker with Compose v2, and git.

```bash
git clone git@github.com:rtaylorzfin/zfin-build-orchestrator.git ~/zfin-dev/zfin-build-orchestrator
~/zfin-dev/zfin-build-orchestrator/z shell-init >> ~/.bashrc
```

| | |
|---|---|
| `z` | the front door |
| `lib/` | one class per command, plus `ZfinUtil` (helpers, roots) and `StackConfig` (ZFIN policy) |
| `compose/` | compose overlays layered on the ZFIN checkout's base file, and the sidecar's build context |
| `check` | compiles every command class; run it after editing `lib/` |
| `docs/` | [dev-stacks.md](docs/dev-stacks.md) to start; [dev-stacks-by-example.md](docs/dev-stacks-by-example.md) as terminal sessions |
