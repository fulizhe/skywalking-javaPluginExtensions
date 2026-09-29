# verify:容器化场景验证回路(Linux/Docker + bash 第一路径)

`verify/` 是插件端到端验证的**主路径**:在 Linux / CI / Windows(Docker Desktop)任一环境,用一条
`bash` 命令完成「构建插件 → 构建 demo-app → 装配 agent → 启动 → 造数 → 断言 → 报告」,以退出码表达结果。

形态借鉴 [apache/skywalking-java 的 `test/plugin`](https://github.com/apache/skywalking-java/tree/main/test/plugin):
**通用运行器镜像 + 声明式场景 + 版本矩阵 + 数据驱动断言**;按本仓库约束裁剪(单运行器镜像、场景内跑 maven、
`pwsh` 脚本保留为次选)。

## 快速开始

```bash
# 跑全部场景(各自默认依赖版本)
bash verify/run.sh

# 只跑某场景
bash verify/run.sh --scenario logfile-reporter

# 跑某场景的版本矩阵(读 support-version.list)
bash verify/run.sh --scenario override-httpclient --matrix

# 列出场景
bash verify/run.sh --list
```

首次运行会构建运行器镜像(下载 agent + 两个 Maven 基础镜像)并下载依赖(经 `agent/settings.xml` 的
aliyun 镜像);之后依赖缓存于 Docker 命名卷 `skywalking-verify_verify-m2`。

## 退出码

| 码 | 含义 |
|----|------|
| 0 | 全绿 |
| 1 | 构建失败(插件 / demo-app / 镜像) |
| 2 | 场景缺失或应用未就绪 |
| 3 | 插件未加载(agent 日志无加载记录) |
| 5 | 断言失败 |
| 64 | 参数错误 |

`verify/run.sh` 自身:全绿 0;任一场景失败 1。

## 场景结构

```
verify/scenarios/<plugin>/
├── scenario.conf          # 声明(被 run-scenario.sh source):见下表
├── checks.sh              # 定义 run_checks():bash+jq 断言(HTTP 契约黑盒)
└── support-version.list   # 可选:版本矩阵,每行一个依赖版本
```

`scenario.conf` 变量:

| 变量 | 含义 |
|------|------|
| `PLUGIN_MODULES` | `mvn -pl` 的模块列表(逗号分隔) |
| `PLUGIN_JAR_GLOBS` | 安装进 agent `plugins/` 的 jar(相对 `agent/`,空格分隔,支持通配) |
| `PLUGIN_LOAD_REGEX` | agent 日志中"插件已加载"的 `grep -E` 正则 |
| `HEALTH_PATH` | 就绪探测路径(默认 `/`) |
| `AGENT_OPTS` | 追加的 `-Dskywalking.*` 启动参数 |
| `REMOVE_OFFICIAL_HTTPCLIENT` | `true` 时移除官方 `apm-httpClient-4.x-plugin`(override 场景避免重复增强) |

断言助手(见 `verify/lib.sh`):`assert_eq` / `assert_ge` / `assert_le` / `assert_jq`(jq 表达式求值为 `true` 通过)、
`http_get` / `http_get_or_empty` / `http_post` / `wait_ready`。

## 现有场景

| 场景 | 覆盖 |
|------|------|
| `logfile-reporter` | 与 `agent/demo-app/scripts/validate.ps1` 的 A/B/C/D 等价:trace 缓存合并、告警链端到端 + webhook 收讫、运行时开关、五类数据流读口冒烟 |
| `override-httpclient` | 采集开关读口 + enable/disable;httpclient POST 表单体采为 `http.request.params` tag 端到端;httpclient `4.5.13`/`4.5.14` 版本矩阵 |

## 版本矩阵

`support-version.list` 每行一个库版本;`--matrix` 时运行器以 `-D<name>=<v>` 构建 demo-app。
`override-httpclient` 用 `-Dhttpclient.version=<v>` 覆盖 demo-app 的 Spring Boot 托管版本
(见 `agent/demo-app/pom.xml` 的 `<httpclient.version>`)。

## 新增一个插件场景

1. 建 `verify/scenarios/<name>/`,填 `scenario.conf` 与 `checks.sh`(在 `run_checks` 里写断言);
2. 需要版本矩阵就加 `support-version.list`;
3. `bash verify/run.sh --scenario <name>` 验证;无需改运行器。

## 与 pwsh 脚本的关系

`agent/demo-app/scripts/*.ps1`(validate / stress / run-with-agent / …)保留为**本地调试次选**:
它们与 `verify` 断言同一批 HTTP 契约,互为对照。默认请用 `verify`(可跨平台、可进 CI)。

## 不在范围

- mock collector 端到端对账(借上游 `skywalking-mock-collector`,另立 spec);
- override-hutool 场景(demo-app 暂无 hutool 读口桩/控制器);
- 上游式 Freemarker 配置生成与 tomcat-container 形态。
