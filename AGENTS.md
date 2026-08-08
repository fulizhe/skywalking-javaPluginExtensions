## Agent skills

### Issue tracker

Issues and specs live as markdown files under `.scratch/<feature>/` in this repo. See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical roles, label string equal to role name: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.

### Module maintenance status

- **Active**: `agent/logfile-reporter-plugin` — ongoing iteration (bugfix & feature)
- **Archived**: all other modules — critical bugfix only; feature work needs pre-approval

When invoking toolchains (to-tickets / triage / to-spec / domain-modeling), plan context & features for the active module.