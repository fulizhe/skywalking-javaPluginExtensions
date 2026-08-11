# 编译

## 环境差异（自动探测）

脚本自动选择盘符：**存在 E 盘则用 E，否则用 D**（本机无 E 盘，所以落在 D）。仓库目录、agent 目录、JDK 路径都以选中的盘符为根。

```powershell
# 自动选盘（存在 E 盘用 E，否则用 D）
$root = if (Test-Path 'E:\') { 'E:' } else { 'D:' }
```

## 手动编译（PowerShell）

```powershell
# 1. 自动选盘
$root = if (Test-Path 'E:\') { 'E:' } else { 'D:' }

# 2. 变量（agent 目录、JDK 版本号按本机微调，盘符已自动）
$agentDir = "$root\apps\apache-skywalking-java-agent-9.4.0"
$jdk17    = "$root\apps\java\jdk-17.0.8"     # 构建工具链（release 8 需 JDK 9+ 编译器，见 docs/adr/adr-01-jdk17-migration.md）

# 3. 定位仓库根目录（兼容 E/D 盘及带/不带下划线的目录名）
$repo = @(
  'E:\gitRepository\_skywalking-javaPluginExtensions',
  'E:\gitRepository\skywalking-javaPluginExtensions',
  'D:\gitRepository\_skywalking-javaPluginExtensions',
  'D:\gitRepository\skywalking-javaPluginExtensions'
) | Where-Object { Test-Path $_ } | Select-Object -First 1

# 4. 启用 JDK 17 构建工具链（若已是默认 JDK 可跳过）
$env:path = "$jdk17\bin;$env:path"

# 5. 编译（模块内联到 agent 聚合模块，需带 -am；产物字节码经 release 8 保持基线 8）
cd "$repo\agent"
mvn clean package '-Dmaven.test.skip=true' -T 2C -pl logfile-reporter-plugin -am
```

产物：`logfile-reporter-plugin/target/logfile-reporter-plugin-1.0.0.jar`（class 版本仍为 8，可运行于 JDK 8 应用）

## 演示运行时

插件在两种应用 JVM 下都受支持（运行兼容，见 `docs/adr/adr-01-jdk17-migration.md`）：

- **JDK 8（默认）**：`demo-app/scripts/run-with-agent.ps1` 默认扫描 `D:\apps\java\jdk1.8*`；
- **JDK 17（显式）**：`pwsh ./scripts/run-with-agent.ps1 -JavaHome D:\apps\java\jdk-17.0.8`。

构建工具链与演示运行时解耦：无论演示运行时用 8 还是 17，插件均以 JDK 17 工具链构建（`-BuildJavaHome` 可覆盖）。

## 拷贝到 SkyWalking Agent

```powershell
cp "$repo\agent\logfile-reporter-plugin\target\logfile-reporter-plugin-1.0.0.jar" "$agentDir\plugins\logfile-reporter-plugin-1.0.0.jar"
```

## 验证

```powershell
# 拷贝成功
ls "$agentDir\plugins\" | findstr logfile-reporter-plugin-

# 加载成功（先清空旧日志再启动 agent，避免误判）
rm "$agentDir\logs\skywalking-api.log" -Force
cat "$agentDir\logs\skywalking-api.log" | findstr logfile-reporter-plugin-
```

## 一次性脚本

`packageAndCopy.bat` 已封装「编译 + 拷贝」，仓库路径从脚本自身所在目录推导，agent 目录盘符自动探测（存在 E 盘用 E，否则用 D），无需手动修改（见文件内注释）。
