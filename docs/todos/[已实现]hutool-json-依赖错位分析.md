# [已实现] hutool-json 依赖错位分析

> 来源：`agent/logfile-reporter-plugin` 的 `pom.xml` 声明了 `hutool-json`，但代码审计发现它实际未被使用，真正被依赖的是 `hutool-core`（作为 `hutool-json` 的传递依赖被顺带拉入）。本文记录结论、证据与两种修复方案。
> 状态：**已实现**（2026-10-09 走方案 A：声明即所用，接受运行期回落应用 classpath）
> 关联风险：运行期依赖回落到应用 classpath，存在 `ClassNotFoundException` 隐患（与 `MemoryModeGRPCChannelManager` 同类历史坑）。

---

## 0. 一句话结论

`pom.xml` 里声明的 `cn.hutool:hutool-json` **在代码中没有被任何地方使用**。真正用到的是 `cn.hutool.core.*`（hutool-core），它只是因为 `hutool-json` 依赖了 `hutool-core` 才被一并引入。JSON 序列化则完全没用 hutool，而是用的 SkyWalking Agent 自带的 Gson。

声明与使用的错位，是当前依赖脆弱性的根因。

---

## 1. 证据链

### 1.1 全量扫描：无 `cn.hutool.json` 导入

对 `agent/logfile-reporter-plugin/src/main` 下所有 `.java` 文件做 `hutool` 关键字 grep，命中全部落在 `cn.hutool.core.*`，**没有任何一处 `import cn.hutool.json`**，也没有 `JSONUtil` / `JSONObject` / `JSONArray` 等 hutool-json 专属 API 的调用。

### 1.2 JSON 序列化实际走 Gson（Agent 自带，无需应用 classpath）

| 位置 | 实际使用的 JSON 方案 |
|---|---|
| `HttpTraceAnomalyListener` | `org.apache.skywalking.apm.dependencies.com.google.gson.Gson`（webhook 请求体） |
| `H2TraceSegmentStorage` | `org.apache.skywalking.apm.dependencies.com.google.gson.Gson`（环形文件 payload 落盘/回读） |

`org.apache.skywalking.apm.dependencies.com.google.gson` 是 Agent 核心已 shade 进来的包，PluginClassLoader 直接可见，**不依赖宿主应用 classpath 提供**。

### 1.3 hutool-core 实际被用到的地方（全部是 Interceptor，非 BootService）

| 文件 | 用到的 hutool-core 类 | 用途 |
|---|---|---|
| `LogfileReporterEnableRuntimeInterceptor` | `Convert`, `ReflectUtil` | 运行时启停反射 |
| `LogfileReporterDisableRuntimeInterceptor` | `Convert`, `ReflectUtil` | 运行时停用反射 |
| `LogfileReporterStatusExposeInterceptor` | `ReflectUtil` | 反射读 BootService 状态 |
| `MetricsExposeInterceptor` | `ReflectUtil` | 同上 |
| `SelfStatExposeInterceptor` | `ReflectUtil` | 同上 |
| `TraceParityStatusExposeInterceptor` | `ReflectUtil` | 同上 |
| `LocalPorfileCallInterceptor` | `Convert`, `DateUtil`, `DatePattern`, `MapUtil`, `IdUtil`, `StrUtil` | 本地 profiling 调用 |
| `LocalProfileStatusExposeInterceptor` | `CollUtil`, `IdUtil`, `ReflectUtil` | profiling 状态暴露 |

> 注：`MemoryModeGRPCChannelManager` 仅在注释/异常串里提到 `ReflectUtil`，无实际 import——正是 `readme-classloader.md` 记录过的那个 `ClassNotFoundException` 教训：**BootService 不能依赖 hutool**。当前 8 个 Interceptor 全在数据采集/拦截层（PluginClassLoader 可见域），但依赖回落机制并未改变。

---

## 2. 真实问题

### 2.1 声明错位
- 声明：`hutool-json`
- 实际要：`hutool-core`
- 能工作纯属「`hutool-json` 顺带带进 `hutool-core`」的运气。语义不清，后人维护易误判。

### 2.2 运行期脆弱（回落到应用 classpath）
`pom.xml` 注释写明 hutool 运行期靠 PluginClassLoader **回落到应用 classpath**（与 H2 的 shade 做法不同）。`hutool-core` 没有被 shade 进插件 jar，因此：
- 宿主应用**自带 hutool** → 正常
- 宿主应用**不自带 hutool** → 上述 8 个 Interceptor 运行时 `ClassNotFoundException`，插件状态暴露/运行时启停/profile 调用全部失效

这与早年 `MemoryModeGRPCChannelManager` 踩的 `ClassNotFoundException` 坑同源：`readme-classloader.md` 第 36 行已自述「pom 仅有 `hutool-json`」，而 `hutool-core` 对 Agent 核心类加载器不可见。

---

## 3. 修复方案（二选一）

### 方案 A — 最小改动（声明即所用）
把 `pom.xml` 依赖从
```xml
<dependency>
    <groupId>cn.hutool</groupId>
    <artifactId>hutool-json</artifactId>
</dependency>
```
改为
```xml
<dependency>
    <groupId>cn.hutool</groupId>
    <artifactId>hutool-core</artifactId>
</dependency>
```
优点：语义清晰，不再拉无用的 json 模块。
缺点：**未消除回落脆弱性**——仍依赖应用 classpath 提供 hutool-core，宿主不带依然崩。

### 方案 B — 根治脆弱（shade 进插件 jar）
沿用 H2 的既有套路（maven-shade-plugin 重定位），把 `hutool-core` 也 shade 进插件 jar，PluginClassLoader 自带，彻底不依赖应用 classpath。
优点：与 H2 一致，运行期零外部依赖，消除整类 `ClassNotFoundException`。
缺点：改动稍大，需确认 shade 重定位包名不与宿主 hutool 冲突（建议重定位到 `org.apache.skywalking.apm.dependencies.cn.hutool.core`）。

---

## 4. 结论与待确认

**已决策：走方案 A**（2026-10-09）。理由：本插件在指定范围内使用，可确认宿主应用 classpath 携带 `hutool-core`（`demo-app` 本身即直接 import `cn.hutool.core.*`），接受运行期回落；本轮只做「声明即所用」，不 shade。

- [x] 走方案 A 还是方案 B？→ **方案 A**
- [x] 若选 B，确认 shade 重定位目标包名 → 不适用（未选 B）
- [ ] 是否需要顺手把 `MemoryModeGRPCChannelManager` 注释中提到的历史坑也补进 `readme-classloader.md` 作为案例

### 落地记录（2026-10-09）
- `agent/logfile-reporter-plugin/pom.xml`：依赖 `hutool-json` → `hutool-core`；两处回落注释同步改为 `hutool-core`。
- `agent/logfile-reporter-plugin/readme-classloader.md`：第 36 行「pom 仅有 `hutool-json`」改为「插件 pom 声明 `hutool-core` 但未 shade 进 jar」。
- 遗留（未纳入本轮）：`agent/demo-app/pom.xml` 同样声明 `hutool-json` 而代码只用 `cn.hutool.core.*` / `cn.hutool.http.*`，属同一「声明错位」模式，可另开小改。
- 遗留（未纳入本轮）：`dynamic-enable-runtime-8.x-plugin`、`dynamic-debug-runtime-8.x-plugin`、`sqlite-3.x-plugin` 直接声明 `hutool-core` 且未 shade，回落脆弱性同源。

---

## 5. 参考

- `agent/logfile-reporter-plugin/pom.xml`（依赖声明与回落注释）
- `agent/logfile-reporter-plugin/src/main/java/org/apache/skywalking/apm/agent/core/plugin/logfilereporter/*.java`（8 个 Interceptor）
- `agent/logfile-reporter-plugin/src/main/java/org/apache/skywalking/apm/agent/core/plugin/localprofile/*.java`（LocalPorfileCallInterceptor / LocalProfileStatusExposeInterceptor）
- `agent/logfile-reporter-plugin/src/main/java/org/apache/skywalking/apm/agent/core/reporter/logfile/alert/HttpTraceAnomalyListener.java`（Gson 用法）
- `agent/logfile-reporter-plugin/src/main/java/org/apache/skywalking/apm/agent/core/reporter/logfile/storage/H2TraceSegmentStorage.java`（Gson 用法）
- `readme-classloader.md`（hutool 对 Agent 核心类加载器不可见的记录）
