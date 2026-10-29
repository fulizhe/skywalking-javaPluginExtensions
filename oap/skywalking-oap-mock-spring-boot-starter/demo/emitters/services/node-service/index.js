'use strict';

// ---------------------------------------------------------------------------
// 必须在加载被支持的模块(express)之前启动 agent —— agent.start() 会 patch 它们。
// ---------------------------------------------------------------------------
const serviceName = process.env.SW_AGENT_NAME || 'node-service';
const serviceInstance = process.env.SW_AGENT_INSTANCE || serviceName + '-1';
const collector = process.env.SW_AGENT_COLLECTOR_BACKEND_SERVICES || '127.0.0.1:11800';

// skywalking-backend-js 是 TS 的 export default(编译成 CJS 后挂在 .default),
// 故 require() 拿到的命名空间对象上没有 start;需回退到 .default。
const swPackage = require('skywalking-backend-js');
const agent = swPackage && swPackage.default ? swPackage.default : swPackage;
if (!agent || typeof agent.start !== 'function') {
  throw new Error('skywalking-backend-js: agent.start not found (unexpected package export shape)');
}
agent.start({
  serviceName: serviceName,
  serviceInstance: serviceInstance,
  collectorAddress: collector,
});

// agent 只负责 trace;业务日志走包内自带的 LogReportService 桩上报到同一台 mock-OAP。
const logReporter = require('./logReporter');
logReporter.init({ serviceName, serviceInstance, collector });

const express = require('express');
const app = express();
const port = parseInt(process.env.SERVER_PORT || '8081', 10);

app.get('/healthz', (req, res) => {
  res.type('text/plain; charset=utf-8').send('ok');
});

// 链路终点:只做本地工作,不再往下调
app.get('/api/forward', (req, res) => {
  const latency = parseInt(req.query.latency || '0', 10);
  const fail = req.query.fail === 'true';
  const orderId = req.query.orderId || `NODE-${Date.now().toString(36)}`;
  const endpoint = 'GET /api/forward';
  const begin = Date.now();
  // 在请求入口抓一次 trace 坐标:setTimeout 回调里即使上下文丢失,完成日志也能挂回同一个 span
  const traceCtx = logReporter.currentTraceContext();

  logReporter.info(
    `node-service 收到转发请求: orderId=${orderId}, latency=${latency}ms, fail=${fail}`,
    endpoint,
    { orderId, latencyMs: latency, hops: req.query.hops },
    traceCtx
  );

  const finish = () => {
    const cost = Date.now() - begin;
    res.type('text/plain; charset=utf-8');
    if (fail) {
      logReporter.error(
        `node-service 处理订单失败: orderId=${orderId}, cost=${cost}ms`,
        endpoint,
        { orderId, status: 500, costMs: cost },
        traceCtx
      );
      res.status(500).send(serviceName);
      return;
    }
    logReporter.info(
      `node-service 处理订单完成: orderId=${orderId}, cost=${cost}ms`,
      endpoint,
      { orderId, status: 200, costMs: cost },
      traceCtx
    );
    res.send(serviceName);
  };

  // Keep the Express handler (and therefore the agent's Entry span) alive for the
  // simulated latency. A setTimeout returns immediately, so skywalking-backend-js
  // 0.9.0 can close the span before the async response is sent and drop this request.
  if (latency > 0) {
    const until = Date.now() + latency;
    while (Date.now() < until) {
      // demo-only blocking delay
    }
  }
  finish();
});

app.listen(port, () => {
  console.log(`[${serviceName}] listening on :${port}, collector=${collector}`);
});
