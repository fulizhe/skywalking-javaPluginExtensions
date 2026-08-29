# 2026-08-29: OpenCode 插件安装 + Oh-My-OpenAgent 配置经验

> 范围：仅在当前项目（skywalking-javaPluginExtensions）内生效。
> 状态：已配置完成，等待重启 opencode 验证。

## 本次做了什么

1. 安装两个 OpenCode 插件（项目级，写入根目录 `opencode.json` 的 `plugin` 数组）：
   - `opencode-worktree` — git worktree TUI 管理
   - `oh-my-openagent` — Oh-My-OpenAgent（OMO），全能 harness
2. 按"matt-skills 为主力流程、OMO 做底层工具箱"的定位配置了 OMO。
3. 配置产物：
   - `opencode.json` — 插件注册（见下"插件名陷阱"）
   - `.omo/omo.jsonc` — 项目级 OMO 统一配置
   - `.gitignore` — 新增 `.omo/*`（保留 `.omo/omo.jsonc` 入库）

## 关键经验（本轮最有价值的沉淀）

### 1. 插件名陷阱：`oh-my-opencode` 已改名为 `oh-my-openagent`

- npm 上两者是同一包（版本号一致，4.19.4）；`opencode.json` 里写 `oh-my-opencode` 仍能加载但会打 deprecated 警告。
- 规范名应写 **`oh-my-openagent`**。

### 2. 配置文件已统一到 `omo.jsonc`

- 旧文件 `oh-my-openagent.json[c]` / `oh-my-opencode.json[c]` **运行时已不再读取**，只被一次性 migration 引擎导入。
- 统一文件分层：用户层 `~/.omo/omo.jsonc`（最低优先级）→ 项目层 `.omo/omo.jsonc`（就近项目文件优先）。
- OpenCode 相关配置写在 **`"[opencode]": { ... }`** 块内。
- schema：`https://raw.githubusercontent.com/code-yeongyu/oh-my-openagent/dev/assets/omo.schema.json`

### 3. `sisyphus_agent.disabled: true` 是"被动后台"姿态的唯一正解（源码级验证）

读 `dist/index.js` 的 `assembleSisyphusEnabledConfig` / `assembleSisyphusDisabledConfig` 得出：

- Sisyphus **启用**时（默认），无论怎么配，都会：
  - 强制 `default_agent = sisyphus`；
  - 无条件把 `build` 降级为 `{ mode: "subagent", hidden: true }`（约 161933 行）；
  - `planner_enabled && replace_plan` 时把 `plan` 也降级。
- Sisyphus **禁用**（`disabled: true`）时：
  - 默认 `build`/`plan` 完整保留（matt-skills 工作流不受干扰）；
  - **11 个 discipline agent 仍全部注册**（它们来自 `createBuiltinAgents`，与该开关无关）——多 Agent、task 工具、后台探索不受影响；
  - `ultrawork`/`ulw` 关键字仍会注入编排提示；要完整编排就切到 `sisyphus` agent。

> 结论：想让 matt-skills 跑在默认 build/plan 上、又保留 OMO 全部底层能力，就必须 `sisyphus_agent.disabled: true`。没有"部分接管"的中间档。

### 4. `hephaestus` 是 GPT 专用，无 OpenAI 凭据时应禁用

- 受 `no-hephaestus-non-gpt` guard hook 保护，非 GPT 模型会被拦。
- 只有 `opencode-go` provider 时它永远不可用 → `disabled_agents: ["hephaestus"]`。

### 5. 只认证了 `opencode-go`：必须显式固定模型

- 只检查 provider 凭据（`~/.local/share/opencode/auth.json`）后，全局只有一个 `opencode-go`（api key）。
- OMO 内置 11 个 agent 的 fallback 链都以 Anthropic/OpenAI/Google/Copilot 开头，不固定的话每次都会去撞不可用 provider 再回退。
- 固定到 opencode-go 家族：`opencode-go/kimi-k3`、`opencode-go/qwen3.7-plus`（1M 上下文，适合 explore/librarian/deep）、`opencode-go/minimax-m2.7`/`m3`（快，适合 quick）、`opencode-go/glm-5.2`。
- 给主 agent（sisyphus 等）加了同 provider 内的 `fallback_models`，避免单模型故障时无路可退。

### 6. 别忘了 **categories**：task() 后台委托按 category 走，不按 agent 名

- 背景多 Agent 由 `task()` 工具按 category 选模型，只 pin agent 不 pin category 等于白配。
- 8 个内置 category（quick / deep / ultrabrain / writing / visual-engineering / unspecified-low / unspecified-high / artistry）默认链同样不可用，全部固定到 opencode-go。

### 7. `experimental.task_system`（默认 false）不 gate 后台 task() 工具

- 它只拦截 `TodoRead`/`TodoWrite` 换成文件持久化的 task 工具（跨 session 任务列表）。
- 后台并行 agent 由 `background_task`（默认 `defaultConcurrency: 5`）控制，与该开关无关。想少干扰 matt-skills 就保持默认 false。

### 8. 其他环境相关

- Windows 下 `opencode` 不在 PATH（`Get-Command opencode` 为空），auth 在 `~/.local/share/opencode/auth.json`。
- 装插件+写配置后必须**重启 opencode** 才生效（config 非热加载）。
- 首次加载会拉 codegraph 二进制、ast-grep `sg` 等，网络慢时属正常。
- `.omo/` 目录会积累运行时状态（plans、ralph-loop、ulw-loop、codegraph…），已用 `.gitignore` 排除、只允许 `omo.jsonc` 提交。

### 9. migration 引擎会误写 `models`，4.19.4 agent schema 不认（真踩坑）

- 首次重启 opencode 时，`2026-08-reasoning-unification` migration 会把 agent 的 `fallback_models` 数组**改写为 `models`**，并写入 `_migrations` 标记。
- 但 4.19.4 的 `AgentOverrideConfigSchema`（zod）只认 `model` + `fallback_models`，**不认 `models`**（`dist/index.js:26771`）→ 被改写的 3 个 agent（sisyphus/explore/librarian）静默回退到 anthropic/openai 默认链。
- 验证方法：`npx --yes oh-my-openagent doctor --verbose` 会报 `Unknown config key: agents.sisyphus.models`；修好后这些 agent 应显示为 `●`（user override）而非 `○`（fallback）。
- 修复：把 `models` 改回 `model` + `fallback_models`，并**保留 `_migrations` 标记**防止 migration 复写。
- 注意：5.0.0-beta 已正式把 `fallback_models` 改名为 `models`（schema 也认了），升级到 5.x 时又得改回去。

## 待办 / 风险

- [x] 重启 opencode，跑 doctor 验证模型解析与 hook 加载（2026-08-29 完成）
  - 本机无 `bun` → `bunx` 不可用，改用 `npx --yes oh-my-openagent doctor --verbose`。
  - 结果：4 passed / 1 failed / 1 warning；全部 agent 已解析到 opencode-go（sisyphus→kimi-k3、explore/librarian→qwen3.7-plus、其余→kimi-k3）。
  - 剩余 1 failed / 1 warning 均为**环境噪音**，非配置问题：
    - `OpenCode binary not found`：`opencode` 不在 PATH，CLI 运行 doctor 时检测不到；当前 opencode 会话内插件已实际加载（有 OMO 工具/skills 为证）。
    - `oh-my-openagent is not registered`：doctor 只查全局 `~/.config/opencode/opencode.json`，本项目是 project-scoped 注册，属误报。
    - `GitHub CLI missing`：`gh` 未装，仅影响 GitHub 自动化（本仓库走 gitee 镜像，可选装）。
- [ ] 验证 `ultrawork`/`ulw` 关键字在 build agent 下仍能按需触发编排
  - 源码机制已确认完好（keyword-detector 不拦 `build` agent、`sisyphus` agent 即使 `disabled` 也注册）；端到端触发需在会话里手动敲 `ultrawork`/`ulw` 实测。
- [ ] OMO 版本走 `5.0.0-beta` 快（`latest` 4.19.4 与 beta 26 差距大）；升级到 5.x 时注意 `fallback_models`→`models` 改名与 config 迁移。