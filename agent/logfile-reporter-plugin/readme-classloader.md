# SkyWalking Agent 类加载器与 ClassNotFoundException 问题说明

## 问题现象

在 `MemoryModeGRPCChannelManager` 中引用 `cn.hutool.core.util.ReflectUtil` 时，会抛出：

```
ClassNotFoundException: Can't find cn.hutool.core.util.ReflectUtil
    at org.apache.skywalking.apm.agent.core.plugin.loader.AgentClassLoader.findClass(AgentClassLoader.java:117)
    at java.lang.ClassLoader.loadClass(ClassLoader.java:424)
```

而 `LocalProfileStatusExposeInterceptor` 中同样使用 Hutool 的 `ReflectUtil`、`CollUtil`、`IdUtil` 等却不会报错。

---

## 根本原因：类加载器与加载时机不同

| 项目 | MemoryModeGRPCChannelManager | LocalProfileStatusExposeInterceptor |
|------|------------------------------|-------------------------------------|
| **角色** | BootService / @OverrideImplementor | 插件拦截器（Interceptor） |
| **加载时机** | Agent 启动早期 | 插件应用增强时 |
| **加载者** | AgentClassLoader（核心 classpath） | PluginClassLoader（插件 jar + 依赖） |
| **Hutool 可见性** | ❌ 不可见 | ✅ 可见 |

---

## 详细说明

### 1. BootService 的加载流程

`MemoryModeGRPCChannelManager` 在 `META-INF/services/org.apache.skywalking.apm.agent.core.boot.BootService` 中注册，是 **BootService** 实现类。

- 在 Agent **启动的最早阶段** 被加载
- 由 **AgentClassLoader** 加载，其 classpath 仅包含 Agent 核心和部分基础依赖
- Hutool 不在 Agent 核心依赖中（pom 仅有 `hutool-json`），`ReflectUtil` 所在 `hutool-core` 不可见
- 加载 `MemoryModeGRPCChannelManager` 时，若字节码引用了 `ReflectUtil`，JVM 会解析并加载 `ReflectUtil`，导致 `ClassNotFoundException`

### 2. 拦截器的加载流程

`LocalProfileStatusExposeInterceptor` 在 `LocalProfileRuntimeInstrumentation` 中被声明为拦截器类。

- 在**插件应用字节码增强时**才加载
- 由 **PluginClassLoader** 加载，其 classpath 包含**当前插件 jar 及其 Maven 依赖**
- Hutool 作为插件依赖被打进插件包，对 PluginClassLoader 可见
- 加载拦截器时能同时解析 `ReflectUtil`、`CollUtil` 等，不会抛错

---

## 结论

- **BootService / @OverrideImplementor**：由 Agent 核心类加载器加载，**不能依赖** Hutool 等非核心库
- **拦截器（Interceptor）**：由插件类加载器加载，**可以依赖** Hutool 等插件声明的依赖

---

## 解决方案

在 BootService 实现类中：

1. **不要**使用 Hutool（`ReflectUtil`、`CollUtil` 等）
2. **使用 JDK 自带反射**：

```java
// ❌ 不可用（会触发 ClassNotFoundException）
// ReflectUtil.setFieldValue(this, "reconnect", false);

// ✅ 使用 JDK 反射
try {
    java.lang.reflect.Field field = GRPCChannelManager.class.getDeclaredField("reconnect");
    field.setAccessible(true);
    field.set(this, false);
} catch (NoSuchFieldException | IllegalAccessException e) {
    LOGGER.error("Failed to set 'reconnect' field via reflection.", e);
}
```

3. **删除未使用的 import**：若已改用 JDK 反射，务必移除 `import cn.hutool.core.util.ReflectUtil;`，避免编译后仍残留对 `ReflectUtil` 的引用

---

## 相关文件

- BootService 注册：`src/main/resources/META-INF/services/org.apache.skywalking.apm.agent.core.boot.BootService`
- 正确实现示例：`MemoryModeGRPCChannelManager.java`（已用 JDK 反射替代 ReflectUtil）
- 可使用 Hutool 的示例：`LocalProfileStatusExposeInterceptor.java`、`LogfileReporterStatusExposeInterceptor.java`
