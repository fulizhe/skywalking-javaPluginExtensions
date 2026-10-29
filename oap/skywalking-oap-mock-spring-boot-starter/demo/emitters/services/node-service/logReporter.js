'use strict';

// ---------------------------------------------------------------------------
// 业务日志上报(原生 LogReportService.collect)。
//
// skywalking-backend-js 0.9.0 只负责 trace,不带应用日志上报;但包内已经带了
// SkyWalking 的 logging protobuf 桩(Logging_pb / Logging_grpc_pb)和 @grpc/grpc-js,
// 这里直接复用它们,把日志发到与 trace 相同的 mock-OAP。
//
// 关键点:
//   1. 从 agent 的 ContextManager 取当前 traceId / traceSegmentId / spanId,
//      这样链路页「日志」页签能按 span 精确挂载(不是整条 trace 复制)。
//   2. 用一条长连接流上报,避免每条日志都重新建 gRPC 连接。
//   3. 上报失败只打 console.warn,绝不影响业务请求。
// ---------------------------------------------------------------------------

const grpc = require('@grpc/grpc-js');
const { ContextManager } = require('skywalking-backend-js');
const { LogReportServiceClient } = require('skywalking-backend-js/lib/proto/logging/Logging_grpc_pb');
const {
  LogData,
  LogDataBody,
  TextLog,
  TraceContext,
  LogTags,
} = require('skywalking-backend-js/lib/proto/logging/Logging_pb');
const { KeyStringValuePair } = require('skywalking-backend-js/lib/proto/common/Common_pb');

let config = null;
let client = null;
let stream = null;

function init(options) {
  config = options;
}

/** 取当前上下文的 trace 坐标;没有链路(比如启动日志)时返回 null。 */
function currentTraceContext() {
  const span = ContextManager.currentSpan;
  if (!span) return null;
  let traceId;
  try {
    traceId = ContextManager.current.traceId();
  } catch (e) {
    return null;
  }
  if (!traceId || traceId === 'N/A') return null;
  const segment = span.context && span.context.segment;
  const segmentId = segment && segment.segmentId ? segment.segmentId.toString() : '';
  return {
    traceId,
    segmentId,
    spanId: typeof span.id === 'number' ? span.id : 0,
  };
}

/** 复用一条客户端流;流断开后下一次上报会自动重连。 */
function ensureStream() {
  if (stream) return stream;
  if (!client) {
    client = new LogReportServiceClient(config.collector, grpc.credentials.createInsecure());
  }
  const call = client.collect(new grpc.Metadata(), (err) => {
    if (err && err.code !== grpc.status.CANCELLED) {
      console.warn(`[${config.serviceName}] log report closed: ${err.message}`);
    }
    if (stream === call) stream = null;
  });
  call.on('error', (err) => {
    console.warn(`[${config.serviceName}] log report stream error: ${err.message}`);
    if (stream === call) stream = null;
  });
  call.on('end', () => {
    if (stream === call) stream = null;
  });
  stream = call;
  return call;
}

function buildLogData(level, message, endpoint, tags, traceCtx) {
  const data = new LogData();
  data.setTimestamp(Date.now());
  data.setService(config.serviceName);
  data.setServiceinstance(config.serviceInstance);
  data.setEndpoint(endpoint || '');
  data.setLayer('Http');
  data.setBody(new LogDataBody().setType('text').setText(new TextLog().setText(message)));

  const logTags = new LogTags();
  logTags.addData(new KeyStringValuePair().setKey('level').setValue(level));
  logTags.addData(new KeyStringValuePair().setKey('logger').setValue('business'));
  Object.keys(tags || {}).forEach((key) => {
    const value = tags[key];
    if (value !== undefined && value !== null && value !== '') {
      logTags.addData(new KeyStringValuePair().setKey(key).setValue(String(value)));
    }
  });
  data.setTags(logTags);

  const ctx = traceCtx === undefined ? currentTraceContext() : traceCtx;
  if (ctx) {
    data.setTracecontext(new TraceContext()
      .setTraceid(ctx.traceId)
      .setTracesegmentid(ctx.segmentId)
      .setSpanid(ctx.spanId));
  }
  return data;
}

function report(level, message, endpoint, tags, traceCtx) {
  if (!config) return;
  try {
    ensureStream().write(buildLogData(level, message, endpoint, tags, traceCtx));
  } catch (e) {
    // 日志上报失败不能影响业务
    console.warn(`[${config.serviceName}] log report failed: ${e.message}`);
  }
}

module.exports = {
  init,
  currentTraceContext,
  info: (message, endpoint, tags, traceCtx) => report('INFO', message, endpoint, tags, traceCtx),
  warn: (message, endpoint, tags, traceCtx) => report('WARN', message, endpoint, tags, traceCtx),
  error: (message, endpoint, tags, traceCtx) => report('ERROR', message, endpoint, tags, traceCtx),
};
