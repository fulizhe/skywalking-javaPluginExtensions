# 01 — 构建基座：H2 依赖打包 + 测试基建

**What to build:** 插件构建产物能带上（shade 重定位后的）H2，并且全部既有单元测试真正被运行——为影子存储的落地扫清打包与测试基建障碍。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

- [ ] 插件引入 H2 依赖（版本 2.1.212，与 OAP 9.4 / 实验环境一致），构建产物包含重定位后的 H2 类；字节码基线 `release 8` 不变
- [ ] 确认现有 hutool-json 依赖的打包方式，H2 沿用同一策略；若发现既有依赖并未随 jar 分发，如实记录并按同一策略处理
- [ ] surefire include 扩为 `**/*Test.java`：既有 `KeyedLocalStoreTest`、队列测试等恢复运行
- [ ] 沉睡测试翻出的历史失败：本票内修绿，或在票内明确豁免记录（不带病前进）
- [ ] 验证回路不受打包变化影响（构建 → 安装 → 启动链路照常）

## Comments
