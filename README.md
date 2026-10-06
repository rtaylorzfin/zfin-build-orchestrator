# zfin-build-orchestrator

Per-feature dev stacks for the ZFIN repo: seeds, feature stacks, freeze/thaw, a shared
db+solr, and a Claude sidecar, driven from one front door, `z`. Opt-in: install it once,
outside any ZFIN checkout, and point it at the checkouts you want to run.

The ZFIN repo provides the hooks this tooling relies on (per-stack ports, memory, the
`feature` instance, healthchecks); nothing in it requires this tooling.

Groovy only, run on the host. Needs `groovy`, Docker with Compose v2, and git.

See [reference/dev-stacks.md](reference/dev-stacks.md) to start.
