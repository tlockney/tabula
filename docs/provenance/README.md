# Provenance by packet

Each packet that adds user-visible behavior records it here, in its own file, so parallel PRs never edit the same lines. The project's provenance rules and the rows recorded before this directory existed are in [PROVENANCE.md](../../PROVENANCE.md). `make provenance` prints all rows as one table.

- Name the file after the packet (`P3.15.md`), or `issue-N.md` for a Task issue with no packet. Follow-up PRs for the same packet append to its file.
- The file holds one table with the header below: one row per behavior, every cell filled. Source is a `docs/FEATURES.md` item ID or "independent design" (with the ADR, if any).
- Rows are append-only: correct a row by adding a new one, never by editing or removing it.
- These files follow the public framing rule in AGENTS.md: no restricted names.

```markdown
# P3.15: Start page

| Behavior | Source | Packet |
| --- | --- | --- |
| The tablet opens a chosen dashboard page at start | F-DSH-02 | P3.15 |
```

`make check` fails on a misnamed file or a malformed table.
