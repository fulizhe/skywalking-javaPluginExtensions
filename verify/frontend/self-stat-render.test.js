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
  esc: esc, n: n, f: f, pct: pct, bytes: bytes, dur: dur, since: since,
  row: row, table: table, kvRow: kvRow, bar: bar, badge: badge,
  renderVerdict: renderVerdict, renderChips: renderChips, renderCarrier: renderCarrier,
  renderPipeline: renderPipeline, renderH2: renderH2, renderBacklog: renderBacklog,
  renderCapped: renderCapped, renderHeap: renderHeap, load: load
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
        insertedRows: 87461, evictedRows: 0, errorCount: 0, auditWaterLevel: 1000 },
  backlog: { enabled: true, depth: 3, capacity: 4096, dropped: 0 },
  capped: { enabled: true, file: 'D:\\apps\\trace-payload.capped.db',
            currIndex: 26680489111, sizeBytes: 134217728, dataLenBytes: 134217712,
            usedRatio: 1, wrapCount: 198, oldestLiveIndex: 26546271399,
            stats: { writeCount: 26680489, readCount: 512, missReadCount: 4096,
                     expiredReadCount: 4096, oversizedRejectedCount: 0, fsyncCount: 266804,
                     ioErrorCount: 0, compressionRatio: 0.85,
                     avgBytesPerWriteBefore: 660, avgBytesPerWriteAfter: 99,
                     avgMillisPerWrite: 0.0015, avgMillisPerRead: 1.7, totalWriteMillis: 41000 } },
  jvmHeap: { usedBytes: 353374208, committedBytes: 996432232, maxBytes: 7570000000, usedRatio: 0.0467 },
};

/** 存储未启用（h2.enabled=false）：页面必须显示"未启用"，且**不得**报"有损耗"。 */
const DISABLED = Object.assign({}, LIVE, {
  h2Enabled: false, metricsEnabled: false,
  h2: { enabled: false }, backlog: { enabled: false },
  capped: { enabled: false, file: '' },
});

function renderAll(s) {
  ['verdictText', 'verdictWhy', 'verdictKv', 'chips', 'carrier', 'pipeline', 'h2', 'backlog', 'capped', 'heap', 'absent']
    .forEach((id) => { nodes[id] = stubNode(); });
  M.renderVerdict(s); M.renderChips(s); M.renderCarrier(s); M.renderPipeline(s);
  M.renderH2(s); M.renderBacklog(s); M.renderCapped(s); M.renderHeap(s);
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

// ---------------------------------------------------------------- load() 端到端（走 fetch → 渲染）
// `absent` 不在这份清单里：它是"未挂载"提示条，正常情况下只改 className（隐藏），
// 不填内容 —— 对它单独断言 className。
const RENDER_IDS = ['verdictText', 'verdictWhy', 'verdictKv', 'chips', 'carrier', 'pipeline',
  'h2', 'backlog', 'capped', 'heap', 'stamp', 'pollState'];
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