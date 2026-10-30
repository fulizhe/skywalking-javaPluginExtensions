# 04 异步路径原样复制（不改）

Status: done
Type: task
Blocked by: 01

## 目标

把官方 `apm-httpclient-5.x-plugin` 的异步增强逐字搬进本模块，使移除官方 jar 后异步 tracing 不丢失；**异步路径一律不加采集**。

## 范围

逐字复制（仅改 package 到 `org.apache.skywalking.apm.plugin.override.httpclient.v5`）：

- `HttpAsyncClientDoExecuteInterceptor`
- `IOSessionImplPollInterceptor`
- `wrapper/AsyncResponseConsumerWrapper`
- `wrapper/FutureCallbackWrapper`
- `Constants`（`SKYWALKING_CONTEXT_SNAPSHOT`）
- `define/HttpAsyncClientInstrumentation`
- `define/IOSessionImplInstrumentation`
- 在 `skywalking-plugin.def` 追加这两条注册

## 硬约束

- 逻辑、字段、调用顺序与官方 v9.4.0 完全一致，**不做任何采集改动**。
- 每个文件在 license 头后加简短注释：与官方同源、未修改、保持原样，理由见 README。

## 验收

- 异步 6 个文件 + 2 个 instrumentation 与官方 v9.4.0 内容一致（除 package 与说明注释）。
- `skywalking-plugin.def` 共 4 条（2 同步 + 2 异步）。
- 编译通过。
