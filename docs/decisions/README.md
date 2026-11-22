# Architecture decision records

One file per decision: `NNNN-short-title.md`, from [TEMPLATE.md](TEMPLATE.md). Accepted ADRs are not edited except to mark them superseded; a new ADR replaces an old one.

Numbers 0001 and 0002 are reserved by the plan for the HA platform spike (P0.3) and constants (P0.4).

There is no hand-kept index, so parallel PRs never edit the same lines. `make adr-index` prints one from each ADR's header: the `# NNNN: Title` line and the `Status:` and `Packet:` lines under it. `make check` fails on a malformed header or two ADRs with the same number. If another PR merges your number first, renumber yours to the next free one.
