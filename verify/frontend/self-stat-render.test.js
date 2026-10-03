/*
 * 「监控的监控」页（self-stat.html）的**渲染层**回归测试（纯 Node，无浏览器、无依赖）。
 *
 * 页面里的格式化/渲染逻辑**原样切出来**跑（不手抄一份），DOM 用桩。
 *
 * ## 为什么要有这个测试
 *
 * 2026-10-03 这一页的首版带着四个 bug 上线，**编译、`node --check`、`mvn test` 全绿**，
 * 只有真正渲染出来才看得见：
 *
 *   ① 「进程已运行 20729.5 天」—— 把**时长** uptimeMs 当**绝对时间戳**喂给 "距今多久" 格式化函数。
 *      纯算术错误，语法检查抓不到。
 *   ② 结论卡那几个数字**根本没渲染**—— 容器是 `<dl>`，填充函数产出 `<tr>`，
 *      而 `<tr>` 在 table 上下文之外会被 HTML 解析器**直接丢弃**（"in body" 模式忽略该标签）。
 *      页面其余部分照常显示，最容易误判成"就是这个数据"。
 *   ③ 页面文案写着"请看下面标红的计数"，其实一点没红—— 传的是裸类名 `err`，
 *      而 dashboard.css 里只有 `.badge.err`、**没有裸 `.err`**。
 *   ④ 「估算」角标显示成 `&lt;span…&gt;` —— 标签里塞了 HTML，而填充函数对标签做 esc()。
 *
 * 这四条与 `slow-topn-render.test.js` 抓到的是同一类问题（那里是漏闭合标签导致演示模式
 * 把主榜裁掉）。共性：**只有渲染/运行时才暴露**。所以这个测试刻意不测"数字算得对不对"
 * （那属于读口与插件侧，由 checks.sh 与 Java 单测负责），只测**渲染层不会静默丢东西**。
 *
 * ## 跑法
 *
 *   node verify/frontend/self-stat-render.test.js
 *   退出码 0 = 全通过
 */
'use strict';

const fs = require('fs');
const path = require('path');

const PAGE = process.argv[2] || path.resolve(
  __dirname, '../../agent/demo-app/src/main/resources/static/dashboards/self-stat.html');

let fails = 0;
function ok(msg, cond, extra) {
  if (cond) {
    console.log('  OK   ' + msg);
  } else {
    fails++;
    console.log('  FAIL ' + msg + (extra ? '  →  ' + extra : ''));
  }
}

/** 从页面原文切出一段（含边界标记），切不出就报错——避免"切失败=静默通过"。 */
function slice(src, from, to) {
  const a = src.indexOf(from);
  if (a < 0) throw new Error('extract failed (start not found): ' + from);
  const b = src.indexOf(to, a);
  if (b < 0) throw new Error('extract failed (end not found): ' + to);
  return src.slice(a, b);
}

// ---------------------------------------------------------------- 最小 DOM 桩
const nodes = {};
function stubNode() {
  return { innerHTML: '', textContent: '', className: '' };
}
global.document = {
  getElementById(id) { return nodes[id] || (nodes[id] = stubNode()); },
  createElement: stubNode,
  querySelector() { return stubNode(); },
  querySelectorAll() { return []; },
  head: { appendChild() {} },
  body: { appendChild() {} },
  addEventListener() {},
};
// 页面末尾会按 SWPoll 有无决定走哪条分支；这里给 null 走 else（load + startPolling）。
global.SWPoll = null;
global.setInterval = () => 1;      // 别让定时器把 node 挂住
global.clearInterval = () => {};
global.location = { search: '' };
global.window = global;

// ---------------------------------------------------------------- 切片并 require
const src = fs.readFileSync(PAGE, 'utf8');
// 从第一个函数切到轮询那段之前：拿全部格式化函数 + 六个渲染器 + load()。
// **不能从 `(function () {` 起**——那是 IIFE 的开括号，切到这里就少了配对的收尾，
// 模块会因括号不平衡报 "Unexpected end of input"。函数声明会提升，顺序无所谓。
const body = slice(src, 'function esc(s) {', 'var pollTimer = null;');
const tmp = path.join(require('os').tmpdir(), 'self-stat-under-test-' + process.pid + '.js');
fs.writeFileSync(tmp, body + `
module.exports = {
  esc: esc, n: n, f: f, pct: pct, bytes: bytes, dur: dur, since: since, fmtTime: fmtTime,
  row: row, table: table, kvRow: kvRow, bar: bar, badge: badge,
  renderVerdict: renderVerdict, renderChips: renderChips, renderCarrier: renderCarrier,
  renderPipeline: renderPipeline, renderH2: renderH2, renderBacklog: renderBacklog,
  renderCapped: renderCapped, renderHeap: renderHeap, renderCrossCheck: renderCrossCheck,
  renderHotLayer: renderHotLayer,
  load: load
};
`, 'utf8');
const M = require(tmp);

// ---------------------------------------------------------------- payload（贴近实战形状）
const NOW = Date.parse('2026-10-02T12:00:00+08:00');
const HOUR = 3600 * 1000;

/** 环已绕 198 圈、H2 有行、堆用了 337MB、零丢弃 —— 与真实实例截图同量级。 */
const LIVE = {
  startedAtMs: NOW - 3 * HOUR,
  uptimeMs: 3 * HOUR,
  reporterEnabled: true, h2Enabled: true, metricsEnabled: true,
  carrier: { strategy: 'IF_POSSIBLE', channelSize: 5, bufferSize: 30000,
             produced: 26680489, dropped: 0, ignored: 12, skippedWhenDisabled: 0 },
  pipeline: { consumeBatches: 913, consumedSegments: 26680489,
              lastConsumeMillis: 1.8, maxConsumeMillis: 37.4,
              orphanSegments: 4021, alertEnabled: false },
  dataLoss: { carrierDropped: 0, storageDropped: 0, total: 0 },
  h2: { enabled: true, rows: 87461, maxRows: 100000, rowUsageRatio: 0.87461,
        bytesPerRowEstimate: 800, estimatedBytes: 87461 * 800,
        insertedRows: 87461, evictedRows: 0, errorCount: 0, auditWaterLevel: 1000,
        // 最老存活段（按插入序）= NOW-50min、最新 = NOW-2min → 跨度 48 min
        oldestStartTimeMs: NOW - 50 * 60000, newestStartTimeMs: NOW - 2 * 60000,
        coveredSpanMs: 48 * 60000 },
  backlog: { enabled: true, depth: 3, capacity: 4096, dropped: 0 },
  capped: { enabled: true, file: 'D:\\apps\\trace-payload.capped.db',
            currIndex: 26680489111, sizeBytes: 134217728, dataLenBytes: 134217712,
            usedRatio: 1, wrapCount: 198, oldestLiveIndex: 26546271399,
            oldestPayloadId: 26546300000,   // < oldestLiveIndex → 已过期
            oldestPayloadReadable: false,
            stats: { writeCount: 26680489, readCount: 512, missReadCount: 4096,
                     expiredReadCount: 4096, oversizedRejectedCount: 0, fsyncCount: 266804,
                     ioErrorCount: 0, compressionRatio: 0.85,
                     avgBytesPerWriteBefore: 660, avgBytesPerWriteAfter: 99,
                     avgMillisPerWrite: 0.0015, avgMillisPerRead: 1.7, totalWriteMillis: 41000 } },
  jvmHeap: { usedBytes: 353374208, committedBytes: 996432232, maxBytes: 7570000000, usedRatio: 0.0467 },
  // 热层已打满 1000/1000 → FIFO 正在淘汰；窗口 22.5 min 远短于 H2 的 48 min
  hotLayer: { enabled: true, maxTraces: 1000, traces: 1000,
              segments: 2381, spans: 10442,
              oldestSpanTimeMs: NOW - 25 * 60000, newestSpanTimeMs: NOW - 2.5 * 60000,
              coveredSpanMs: 22.5 * 60000, estimatedBytes: 2381 * 800 + 10442 * 200 },
};

/** 热层刚启动、还没攒够：窗口与 H2 同量级。 */
const HOT_WIDE = JSON.parse(JSON.stringify(LIVE));
HOT_WIDE.hotLayer = { enabled: true, maxTraces: 1000, traces: 12,
                      segments: 15, spans: 61,
                      oldestSpanTimeMs: NOW - 50 * 60000, newestSpanTimeMs: NOW - 2 * 60000,
                      coveredSpanMs: 48 * 60000, estimatedBytes: 15 * 800 + 61 * 200 };

/** 热层尚无 span：时间三端 -1，估算 0。 */
const HOT_EMPTY = JSON.parse(JSON.stringify(LIVE));
HOT_EMPTY.hotLayer = { enabled: true, maxTraces: 1000, traces: 0, segments: 0, spans: 0,
                       oldestSpanTimeMs: -1, newestSpanTimeMs: -1,
                       coveredSpanMs: -1, estimatedBytes: 0 };

/** 热层未初始化。 */
const HOT_OFF = JSON.parse(JSON.stringify(LIVE));
HOT_OFF.hotLayer = { enabled: false, maxTraces: 1000, traces: 0, segments: 0, spans: 0,
                     oldestSpanTimeMs: -1, newestSpanTimeMs: -1, coveredSpanMs: -1, estimatedBytes: 0 };

/** 载荷全部可读（刚写完、环没绕圈）时，交叉验证必须给"可读"而不是"过期"。 */
const NOT_EXPIRED = JSON.parse(JSON.stringify(LIVE));
NOT_EXPIRED.capped.oldestPayloadId = 26680000000;   // > oldestLiveIndex
NOT_EXPIRED.capped.oldestPayloadReadable = true;

/** 持久层尚无行：时间三端都是 -1，交叉验证不可用。 */
const NO_ROWS = JSON.parse(JSON.stringify(LIVE));
NO_ROWS.h2.rows = 0;
NO_ROWS.h2.oldestStartTimeMs = -1;
NO_ROWS.h2.newestStartTimeMs = -1;
NO_ROWS.h2.coveredSpanMs = -1;
NO_ROWS.capped.oldestPayloadId = -1;

/** 存储未启用（h2.enabled=false）：页面必须显示"未启用"，且**不得**报"有损耗"。 */
const DISABLED = Object.assign({}, LIVE, {
  h2Enabled: false, metricsEnabled: false,
  h2: { enabled: false }, backlog: { enabled: false },
  capped: { enabled: false, file: '' },
});

function renderAll(s) {
  ['verdictText', 'verdictWhy', 'verdictKv', 'chips', 'carrier', 'pipeline', 'h2', 'backlog', 'capped', 'heap', 'hotLayer', 'absent']
    .forEach((id) => { nodes[id] = stubNode(); });
  M.renderVerdict(s); M.renderChips(s); M.renderCarrier(s); M.renderPipeline(s);
  M.renderH2(s); M.renderBacklog(s); M.renderCapped(s); M.renderHeap(s); M.renderHotLayer(s);
  return nodes;
}
function textOf(html) { return String(html).replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim(); }

console.log('== 格式化函数：时长 vs 时间戳（bug ① 的根因）==');
// 固定时钟**必须在同步断言之前**装上：否则 since() 里的 Date.now() 用真实时间，
// 而入参是 NOW 这个固定时刻，两者不在同一时基 —— 算出来是 1.4 天而不是 3.0 小时。
// 这与 bug ① 是同一个签名（时间基准错配），别在测试里重犯。
global.Date.now = () => NOW;

// "距今多久" 的入参必须是绝对时间戳。传时长会得到天文数字 —— 这条断言把两者的差别钉死。
ok('since(绝对时间戳 3 小时前) = 3.0 小时', M.since(NOW - 3 * HOUR) === '3.0 小时', M.since(NOW - 3 * HOUR));
ok('since(9 天前) = 9.0 天', M.since(NOW - 9 * 86400000) === '9.0 天', M.since(NOW - 9 * 86400000));
ok('页面不得把时长当时间戳喂 since（uptimeMs 只能进 duration 类格式化）',
   !/since\(\s*s\.uptimeMs/.test(src));
ok('since(undefined) = —（未挂载插件时不编数字）', M.since(undefined) === '—', M.since(undefined));
ok('dur 是时长口径：37.4 → "37.4 ms"', M.dur(37.4) === '37.4 ms', M.dur(37.4));
// 环形文件单次写耗时在 0.002ms 量级：四舍五入成 "0.0 ms" 等于没显示，故低于 1ms 单列一档
ok('dur 保留亚毫秒精度：0.0015 → "0.002 ms"', M.dur(0.0015) === '0.002 ms', M.dur(0.0015));
ok('dur 分档到分钟：2880000 → "48.0 分钟"', M.dur(2880000) === '48.0 分钟', M.dur(2880000));
ok('dur 分档到秒：2880 → "2.88 s"', M.dur(2880) === '2.88 s', M.dur(2880));
ok('dur 对负值（无行时的 -1）返回 —', M.dur(-1) === '—', M.dur(-1));
ok('bytes 二进制换算：134217728 → "128.00 MB"', M.bytes(134217728) === '128.00 MB', M.bytes(134217728));

console.log('\n== 结论卡：结构与内容（bug ②）==');
let n = renderAll(LIVE);
ok('结论卡不含 <tr>（<dl>/<div> 里的 <tr> 会被解析器丢弃）', !/<tr/.test(n.verdictKv.innerHTML));
ok('结论卡不含 <dl>（容器已从 dl 换成 div）', !/<dl/.test(n.verdictKv.innerHTML));
['丢段总数', '采集入口丢', '存储队列丢', '单批消费峰值', 'H2 行数 / 水位', '环形文件占用']
  .forEach((k) => ok('结论卡含「' + k + '」', textOf(n.verdictKv.innerHTML).indexOf(k) >= 0));
ok('结论卡：零丢弃时判为健康（判据在 #verdictText，不在 #verdictKv）',
   textOf(n.verdictText.innerHTML).indexOf('健康') >= 0, textOf(n.verdictText.innerHTML));
ok('结论卡：单批峰值渲染成 "37.4 ms"', textOf(n.verdictKv.innerHTML).indexOf('37.4 ms') >= 0);

console.log('\n== 六段面板：全部渲染、无占位符泄漏 ==');
['carrier', 'pipeline', 'h2', 'backlog', 'capped', 'heap', 'chips'].forEach((id) => {
  ok('#' + id + ' 渲染出内容', n[id].innerHTML.length > 0);
});
const allHtml = ['verdictKv', 'chips', 'carrier', 'pipeline', 'h2', 'backlog', 'capped', 'heap']
  .map((id) => n[id].innerHTML).join('');
ok('无 NaN', allHtml.indexOf('NaN') < 0);
ok('无 undefined', allHtml.indexOf('undefined') < 0);
ok('无 Infinity', allHtml.indexOf('Infinity') < 0);
ok('无二次转义残留 &lt; / &gt;（bug ④：标签里塞 HTML 会被 esc 掉）',
   allHtml.indexOf('&lt;') < 0 && allHtml.indexOf('&gt;') < 0);
ok('页面源码里 row()/kvRow() 的标签不含内嵌 HTML',
   !/row\("[^"]*</.test(src) && !/kvRow\("[^"]*</.test(src));
ok('写指针 currIndex 原样渲染', textOf(n.capped.innerHTML).indexOf('26,680,489,111') >= 0);
ok('绕圈后占用率封顶 100%（不显示 100%+）', textOf(n.capped.innerHTML).indexOf('100.0%') >= 0);
ok('已完整覆盖圈数渲染出 198', textOf(n.capped.innerHTML).indexOf('198') >= 0);
ok('H2 估算字节 = rows × 800B（66.73 MB）', textOf(n.h2.innerHTML).indexOf('66.73 MB') >= 0,
   textOf(n.h2.innerHTML));
ok('堆面板显示 3.0 小时（修复前是 20721.9 天）',
   textOf(n.heap.innerHTML).indexOf('3.0 小时') >= 0
   // 判"不出天数"要卡数字：说明文案里有"天花板"这种含「天」的字，
   // 用裸 indexOf('天') 会误判。
   && !/[\d.]+ 天/.test(textOf(n.heap.innerHTML)),
   textOf(n.heap.innerHTML));

console.log('\n== H2 时间区间与"丢了 vs 过期"交叉验证 ==');
// 最老/最新段时间：能格式化成可读时刻，且口径写明是"段"不是"链路"
const h2text = textOf(n.h2.innerHTML);
ok('H2 面板显示「存活段数」而不是「存活行数」', h2text.indexOf('存活段数') >= 0, h2text);
ok('H2 面板点明一行 = 一个 segment', h2text.indexOf('H2 一行 = 一个 segment') >= 0);
ok('H2 面板提醒段数 ≠ 链路数（别与 h2Size 相加）',
   h2text.indexOf('distinct traceId') >= 0 && h2text.indexOf('不是一个口径') >= 0);
ok('最老段时间渲染成可读时刻', /最老段的时间\s*\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/.test(h2text), h2text);
ok('最新段时间渲染成可读时刻', /最新段的时间\s*\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/.test(h2text));
ok('覆盖跨度渲染成 48.0 分钟', h2text.indexOf('48.0 分钟') >= 0, h2text);
ok('口径注明按插入序而非严格 MIN', h2text.indexOf('而非严格 MIN') >= 0, h2text);

// 交叉验证：LIVE 里 oldestPayloadId < oldestLiveIndex → 必须是"过期"且说明是预期结果
ok('交叉验证：指针落在窗口左侧 → 判为「过期」', textOf(n.capped.innerHTML).indexOf('过期') >= 0,
   textOf(n.capped.innerHTML));
ok('交叉验证：明说过期是环写满的预期结果、不是故障',
   textOf(n.capped.innerHTML).indexOf('预期结果，不是故障') >= 0);
ok('交叉验证：给出"怀疑丢失该看哪两处"', textOf(n.capped.innerHTML).indexOf('若怀疑') >= 0);

// 反向：指针在窗口内 → 判为「可读」。判据必须打**结论句**而不是"过期"二字：
// 区块标题「丢了还是过期了」本身就含"过期"，用 indexOf('过期') < 0 判必然误报。
n = renderAll(NOT_EXPIRED);
const readableText = textOf(n.capped.innerHTML);
ok('交叉验证：指针在窗口内 → 判为「可读」',
   readableText.indexOf('存活段指向的载荷都还在环的可读窗口内') >= 0
   && readableText.indexOf('有行指向已被环覆盖的载荷') < 0, readableText);

// 无行时：时间三端是 -1，页面必须显示 "—" 而不是 1970/负数
n = renderAll(NO_ROWS);
ok('无行时最老/最新段时间显示 "—"（不是 -1 也不是 1970）',
   textOf(n.h2.innerHTML).indexOf('最老段的时间 —') >= 0
   && textOf(n.h2.innerHTML).indexOf('最新段的时间 —') >= 0, textOf(n.h2.innerHTML));
ok('无行时交叉验证报「不可用」而不是误判', textOf(n.capped.innerHTML).indexOf('不可用') >= 0,
   textOf(n.capped.innerHTML));
ok('fmtTime 对 -1 / 0 / null 一律返回 —',
   M.fmtTime(-1) === '—' && M.fmtTime(0) === '—' && M.fmtTime(null) === '—');
ok('fmtTime 对合法时间戳给出可读时刻', /\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/.test(M.fmtTime(NOW)), M.fmtTime(NOW));

console.log('\n== 存储未启用：不得被误判成"有损耗" ==');
n = renderAll(DISABLED);
ok('判为未挂载/未启用，而不是"有损耗"', textOf(n.verdictKv.innerHTML).indexOf('有损耗') < 0,
   textOf(n.verdictKv.innerHTML));
ok('H2 面板显示"未启用"', textOf(n.h2.innerHTML).indexOf('未启用') >= 0, textOf(n.h2.innerHTML));
ok('环形文件面板显示"未启用"', textOf(n.capped.innerHTML).indexOf('未启用') >= 0);

console.log('\n== 标红/标黄必须真的有样式（bug ③）==');
// dashboard.css 只有 .badge.err / .tl-bar.error，没有裸 .err —— 本页自己用 class="num err"。
const CSS = src.slice(src.indexOf('<style>'), src.indexOf('</style>'));
ok('本页 CSS 定义了 td.num.err', /\.num\.err|\.v\.err/.test(CSS));
ok('本页 CSS 定义了 td.num.warn', /\.num\.warn|\.v\.warn/.test(CSS));
ok('结论卡容器不是 <dl class="kv"', !/<dl class="kv"/.test(src));

// ---------------------------------------------------------------- 内存热层面板（⑦）
console.log('\n== 内存热层：把 traceStore 里存的东西摊开 ==');
{
  let n = renderAll(LIVE);
  let t = textOf(n.hotLayer.innerHTML);
  ok('热层：trace 数与容量都渲染出来', t.indexOf('1,000 / 1,000') >= 0, t);
  ok('热层：段数与 span 数都渲染出来', t.indexOf('2,381 段') >= 0 && t.indexOf('10,442 span') >= 0, t);
  ok('热层：覆盖跨度 22.5 分钟', t.indexOf('22.5 分钟') >= 0, t);
  ok('热层：估算占用渲染成人类可读', /MB|KB|B/.test(t), t);
  ok('热层：口径写明时间取自 spans[i].startTime',
     t.indexOf('spans[i].startTime') >= 0, t);
  ok('热层：口径写明与 H2 时间口径不同', t.indexOf('不是同一个口径') >= 0, t);
  ok('热层：写明残缺 trace 无法识别', t.indexOf('残缺') >= 0, t);
  ok('热层：写明未改动历史实现', t.indexOf('未改动它') >= 0, t);

  // 窗口只有 H2 的 ~47% → 必须说清"这是预期结果，不是故障"
  ok('热层窗口远短于 H2 时判「窗口短得多」并说明是 max_log_size 的预期结果',
     t.indexOf('窗口短得多') >= 0 && t.indexOf('预期结果') >= 0
     && t.indexOf('不是故障') >= 0, t);
  ok('热层与 H2 的两个跨度都出现在对比行里',
     t.indexOf('热层覆盖 22.5 分钟') >= 0 && t.indexOf('H2 覆盖 48.0 分钟') >= 0, t);

  n = renderAll(HOT_WIDE);
  t = textOf(n.hotLayer.innerHTML);
  ok('热层窗口与 H2 同量级时判「量级相当」而非报警',
     t.indexOf('量级相当') >= 0 && t.indexOf('窗口短得多') < 0, t);

  n = renderAll(HOT_EMPTY);
  t = textOf(n.hotLayer.innerHTML);
  ok('热层无 span：时间显示 —，不显示 1970',
     t.indexOf('—') >= 0 && t.indexOf('1970') < 0, t);
  ok('热层无 span：跨度显示 — 而非 0 秒', t.indexOf('0.00 s') < 0, t);
  // 逐格断言：面板里恰好三处时间（最早 / 最新 / 跨度），都必须是 —。
  // 只断言"出现过 —"太松，`fmtTime(x || 0)` 把 0 当缺省就会渲染出 1970 而仍带着别处的 —。
  ok('热层无 span：三处时间档全是 —（最早/最新/跨度）',
     (t.match(/最早 span 的时间 — 最新 span 的时间 — 热层覆盖跨度 —/g) || []).length === 1, t);

  n = renderAll(HOT_OFF);
  t = textOf(n.hotLayer.innerHTML);
  ok('热层未初始化：显示「未初始化」', t.indexOf('未初始化') >= 0, t);
  ok('热层未初始化：不做跨层对比（不出现窗口短得多）', t.indexOf('窗口短得多') < 0, t);

  // hotLayer 整个字段缺失（旧插件 jar / 读口未升级）不能把页面打空
  const NO_HOT = JSON.parse(JSON.stringify(LIVE));
  delete NO_HOT.hotLayer;
  n = renderAll(NO_HOT);
  t = textOf(n.hotLayer.innerHTML);
  ok('读口没有 hotLayer 字段时降级为「未初始化」而不是抛错',
     t.indexOf('未初始化') >= 0 && !/NaN|undefined/.test(t), t);

  // 上面最后一次 renderAll 是 NO_HOT，所以下面这两条要重新渲染 LIVE 再断言
  n = renderAll(LIVE);
  ok('结论卡也带一行热层覆盖', textOf(nodes.verdictKv.innerHTML).indexOf('热层覆盖') >= 0,
     textOf(nodes.verdictKv.innerHTML));
  ok('chips 里有「热层覆盖」', textOf(nodes.chips.innerHTML).indexOf('热层覆盖') >= 0,
     textOf(nodes.chips.innerHTML));
}

// ---------------------------------------------------------------- load() 端到端（走 fetch → 渲染）
// `absent` 不在这份清单里：它是"未挂载"提示条，正常情况下只改 className（隐藏），
// 不填内容 —— 对它单独断言 className。
const RENDER_IDS = ['verdictText', 'verdictWhy', 'verdictKv', 'chips', 'carrier', 'pipeline',
  'h2', 'backlog', 'capped', 'heap', 'hotLayer', 'stamp', 'pollState'];
RENDER_IDS.concat(['absent']).forEach((id) => { nodes[id] = stubNode(); });

global.fetch = () => Promise.resolve({ json: () => Promise.resolve(LIVE) });

M.load();

setTimeout(() => {
  console.log('\n== load() 端到端：一次 fetch 之后各段都被填上 ==');
  const missing = RENDER_IDS.filter((id) => !nodes[id] || (!nodes[id].innerHTML && !nodes[id].textContent));
  ok('load() 填满了所有挂载点', missing.length === 0, '空: ' + missing.join(','));
  ok('load() 隐藏了「未挂载插件」提示条（插件在时不该出现）',
     String(nodes.absent.className).indexOf('hidden') >= 0, nodes.absent.className);
  ok('load() 渲染出的堆面板同样是 3.0 小时',
     textOf(nodes.heap.innerHTML).indexOf('3.0 小时') >= 0, textOf(nodes.heap.innerHTML));
  ok('load() 渲染无 NaN/undefined',
     !/NaN|undefined/.test(RENDER_IDS.map((id) => nodes[id].innerHTML).join('')));

  try { fs.unlinkSync(tmp); } catch (e) { /* 临时文件清理失败无关紧要 */ }
  console.log(fails ? '\n共失败 ' + fails + ' 条' : '\n全通过');
  process.exit(fails ? 1 : 0);
}, 30);