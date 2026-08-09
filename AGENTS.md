## Agent skills

### Issue tracker

Issues and specs live as markdown files under `.scratch/<feature>/` in this repo. See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical roles, label string equal to role name: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.

### Module maintenance status

- **Active**: `agent/logfile-reporter-plugin`, `agent/override-httpclient-4.x-plugin`, `agent/override-hutool-http-5.x-plugin` — ongoing iteration (bugfix & feature)
- **Sample & validation**: `agent/demo-app` — plugin capability demo + one-command validation loop for the active modules. Standalone pom, deliberately NOT in the agent reactor, so plugin builds (`-pl <plugin> -am`) and CI paths stay untouched
- **Archived**: all other modules — critical bugfix only; feature work needs pre-approval

### Known TODOs

- `agent/demo-app`: initial validation scope is logfile-reporter-plugin only; extend to override-httpclient-4.x and override-hutool-http-5.x later (structure already multi-plugin ready)

When invoking toolchains (to-tickets / triage / to-spec / domain-modeling), plan context & features for the active modules.
