# 2026-09-29：跨 ClassLoader 传自定义类为何不 CNFE——炸的是语义，不是链接

何时读：改动插件内"要被宿主持有的数据结构"（如 `Log.Tag` 这类替代 `Map` 的 POJO）、或排查宿主读口 JSON 契约（`tag-key`/`tag-value` 之类键名）时。

关联提交：

- `a03df55` —— 引入：`tags: HashMap -> Log.Tag(@SerializedName)`（用自定义类替代每-tag `Map`）。
- `ad27531` —— 回退：`Log.Tag` 的 `@SerializedName` 是 Gson 专属，被宿主 Jackson 序列化成 `key/value`，破坏契约；`tags` 恢复 `List<Map>`，保留 logs 预分配循环。

## 现象

`a03df55` 把 span 的 `tagList` 从 `List<Map<String,Object>>` 换成 `List<Log.Tag>`（`Log.java`），
`Log.toMap()` 把 `span.getTagList()` **按引用**塞进返回的 `Map`（`Log.java:116`）。
这个 Map 经插件 → 宿主一路传到 Spring MVC 的 `/statistic` 读口（`StatisticController.java`），由 **Jackson** 序列化。

预期会"因为宿主 ClassLoader 没有 `Log.Tag` 而 ClassNotFoundException"。**实际没有抛**。

## 为什么没有 ClassNotFoundException（链接层）

跨 ClassLoader 传**对象引用**，与"接收方加载该类"是两回事：

1. **宿主从没写过 `Log.Tag` 这个名字。** 宿主桩 `SWTraceParityUtils` 的签名是 `Map<String,Object>` /
   `List<Map<String,Object>>`，全程只当 `Object`/`Map<?,?>` 用，没有 `Class.forName("...Log$Tag")` 之类的按名加载。
   CNFE 只在"某个 classloader 按名字找不到类"时抛——这条路径不存在。
2. **引用已带着解析好的 `Class`。** `Log.Tag` 由 agent 的 `AgentClassLoader` 加载；宿主拿到引用后，
   `value.getClass()` 直接返回那份**已加载**的元数据，不需要宿主 classloader 再去 find/load。
3. **Jackson 走运行时类型 + 反射。** 它反射调用 public getter `getKey()/getValue()`（`Log.Tag` 是 public
   static 嵌套类、方法 public），跨 classloader 可见性检查通过，于是能正常序列化。
4. **读回路径同样是泛型。** H2 影子层回读时是 `GSON.fromJson(..., TypeToken<Map<String,Object>>)`，
   目标是 `Map` 而非 `Log`，也不触发对 `Log.Tag` 的加载。

## 真正炸的是语义

Jackson **不认** Gson 的 `@SerializedName("tag-key")`，字段名退化成 `key`/`value`，
输出 `{"key":..,"value":..}`；而前端 `trace-view.html` 的 Span 详情读的是 `tag-key`/`tag-value` → 显示 `undefined`。
契约被破坏，与 ClassLoader 无关——这就是 `ad27531` 回退它的原因。

## 边界：什么时候会 CNFE / 类型不匹配

- 宿主改用**同款 Gson** 反序列化到具体类型 `Log`/`Log.Tag`；
- 宿主直接 `instanceof Log.Tag`、强转、或 `Class.forName` 去认这个类；
- 反射访问到**非 public** 的类/成员（跨 CL 可见性检查会失败）。

本次侥幸绕过，是因为"泛型 `Map` + Jackson 运行时反射"两条同时避开了**按名加载**。

## 结论 / 约定

- 跨 ClassLoader（agent 插件 ↔ 宿主应用）的数据，**保持 JSON-friendly 的通用容器**（`Map`/`List`/基本类型），
  不要在跨边界的结构里塞自定义类；序列化注解（Gson `@SerializedName` / Jackson）**只在本侧生效**，别指望被对端识别。
- 若确需自定义类型，须保证宿主侧也能按 FQCN 加载到它（同 jar / 同 CL），否则迟早触发上面"边界"里的任一条。

## 涉及文件与链路

- 引入/回退点：`agent/logfile-reporter-plugin/.../reporter/logfile/Log.java`（`toMap()` tag 出处）、`SegmentLogConverter.java`（`tagsToTagList`）
- 跨 CL 桥：`.../plugin/logfilereporter/TraceParityStatusExposeInterceptor.java`（反射 `getTraceViewFromMemory`）→ 宿主桩 `agent/demo-app/.../toolkit/SWTraceParityUtils.java`
- 宿主序列化口：`agent/demo-app/.../controller/StatisticController.java`（`/statistic`，Jackson）
- H2 泛型回读：`.../reporter/logfile/storage/H2TraceSegmentStorage.java`（`readLogPayload`）
- 消费侧：`agent/demo-app/src/main/resources/static/dashboards/trace-view.html`
