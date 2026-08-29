# skywalking-javaPluginExtensions

#### 介绍
Skywalking Java Agent插件 和 OAP端的扩展。

#### 开发约定

- **给 AI/Agent 看的约定**：见 [`AGENTS.md`](./AGENTS.md) —— 包含模块维护状态、JDK 17 构建/运行约定，以及 OpenCode/OMO 工具怪癖的排查指引（遇到"发送命令失败"等怪问题，先看那里，别在插件代码里找）。顺带读一下能少走弯路。
- **OMO 教学+避坑**：见 [`docs/tutorial/`](./docs/tutorial/) —— 从 `index.html` 进入，包含课程、速查手册和避坑手册。工具相关的坑都沉淀在 [`reference/omo-pitfalls.html`](./docs/tutorial/reference/omo-pitfalls.html)。

#### Github同步

##### 方案一

借助GITEE提供的镜像功能.

```shell

仓库 > "管理" > "仓库镜像管理"

```

https://help.gitee.com/repository/settings/sync-between-gitee-github#%E5%A6%82%E4%BD%95%E7%94%B3%E8%AF%B7-github-%E7%A7%81%E4%BA%BA%E4%BB%A4%E7%89%8C 【如何申请 GitHub 私人令牌？】


##### 方案二（未启用）
```shell
# 使用 GitHub Actions + Gitee Mirror （待选）
#
# http://172.16.3.3:9000/bladex251/8.1%20Git%E8%BF%9C%E7%A8%8B%E5%88%86%E6%94%AF%E5%90%88%E5%B9%B6.html
git remote set-url origin https://github.com/fulizhe/skywalking-javaPluginExtensions.git
git remote add gitee https://gitee.com/lqzkcx3/skywalking-javaPluginExtensions.git 

```


111111