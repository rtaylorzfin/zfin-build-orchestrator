# {{NNN}}: {{TICKET}}. Plan approved, implement.

<One line on what's approved.> My rulings on your questions:
1. <question>: <ruling>
2. ...

## Verification
- <unit tests: name them, and confirm the CI suite picks them up>
- <end to end in this stack: build/deploy, the exact pages or commands, before and after>
- <evidence I want in the report: tables, before/after samples, counts>
- Throwaway drivers stay uncommitted, in `.sidecar/data/`.

Commit once (`{{TICKET}}: ...`), then report in outbox/{{NNN}}.md.
