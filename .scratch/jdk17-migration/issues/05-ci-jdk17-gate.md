# 05 — CI 重写为 JDK 17 构建+测试门禁

**What to build:** 替换现残桩工作流（引用不存在的 `mvnw`、仅 logfile 路径触发、`-DskipTests`），重写为单个 JDK 17 任务：经 `agent/pom.xml` 对三个活跃插件 build + test（去掉 `-DskipTests`），触发路径覆盖三个模块目录与 `agent/pom.xml`，把构建+测试兼容固化为回归守门。

**Blocked by:** 01、02 — CI 跑 `mvn test`，需要干净的 JDK 17 编译（release 8）与统一 Surefire。

**Status:** done

- [x] 工作流使用 `setup-java` JDK 17（Temurin）
- [x] 经 `agent/pom.xml -pl logfile-reporter-plugin,override-httpclient-4.x-plugin,override-hutool-http-5.x-plugin -am` 执行 `mvn test`（不再 `-DskipTests`；本地同命令实测 73 例全绿）
- [x] 触发路径包含三个活跃模块目录与 `agent/pom.xml`（push + PR）
- [x] 三个插件测试在 CI 上全绿（GitHub 与 Gitee 双工作流重写，命令与本地验证一致）
