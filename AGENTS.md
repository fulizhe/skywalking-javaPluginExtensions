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

### Build & demo runtime convention (adr-01)

- **Build toolchain**: JDK 17 (plugin artifacts keep bytecode baseline 8 via `release 8`; building with JDK 8 is no longer supported)
- **Demo runtime**: JDK 8 default + JDK 17 explicit (`run-with-agent.ps1 -JavaHome <jdk17>`); scripts auto-pick a JDK 17 build toolchain via `-BuildJavaHome` / `jdk-17*` scan
- See `docs/adr/adr-01-jdk17-migration.md` for the full decision record
- **Archived**: all other modules — critical bugfix only; feature work needs pre-approval

### Known TODOs

- `agent/demo-app`: initial validation scope is logfile-reporter-plugin only; extend to override-httpclient-4.x and override-hutool-http-5.x later (structure already multi-plugin ready)

When invoking toolchains (to-tickets / triage / to-spec / domain-modeling), plan context & features for the active modules.

## Execution environment (Windows)

Run commands with `pwsh` (PowerShell 7): UTF-8 end-to-end, so Chinese script output round-trips cleanly. The default Windows PowerShell 5.1 console decodes output as GBK and garbles UTF-8 text — run scripts and long verifications via `pwsh -NoProfile -File <script>` / `Start-Process pwsh.exe` rather than the 5.1 shell.
