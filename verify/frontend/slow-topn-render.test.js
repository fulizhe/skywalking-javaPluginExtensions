/*
 * 慢调用 TopN 榜 —— 渲染冒烟测试（纯 Node，无依赖）。
 *
 * 把页面里的排序/名次/渲染逻辑**原样切出来**跑，不手抄一份：页面改了测试会跟着改。
 *
 * 为什么需要它：`node --check` 只查语法，查不出**运行时** ReferenceError。
 * 2026-10-01 就这么漏过一次 —— 榜的行模板里用了 `rank` 而声明已被删，首屏直接抛
 * "rank is not defined"，而它在 refresh() 里排在渲染主表之前，一抛错整条 .then() 链断掉，
 * KPI 行、Endpoint 表、榜**全部空白**。只测纯逻辑的断言也不会碰模板字符串里的变量引用。
 *
 * 跑法（仓库根）：
 *   node verify/frontend/slow-topn-render.test.js
 * 退出码 0 = 全通过。
 */
'use strict';

const fs = require('fs');
const path = require('path');

const PAGE = process.argv[2] || path.resolve(
  __dirname, '../../agent/demo-app/src/main/resources/static/dashboards/slow-topn.html');

/** 按标记把页面里的代码片段原样切出来。 */
function slice(src, from, to) {
  const a = src.indexOf(from);
  if (a < 0) throw new Error('extract failed (start not found): ' + from);
  const b = src.indexOf(to, a);
  if (b < 0) throw new Error('extract failed (end not found): ' + to);
  return src.slice(a, b);
}

// ---------------------------------------------------------------- 最小 DOM 桩
function stubNode() {
  return {
    // style 必须有：页面里写 `empty.style.display = "none"`，桩缺了会抛
    // "Cannot set properties of undefined" —— 那是桩的问题，不是页面的，别搞混。
    style: {},
    innerHTML: '', textContent: '', handlers: [],
    addEventListener(type, fn) { this.handlers.push([type, fn]); },
    querySelectorAll() { return []; },
  };
}
const nodes = {};
['hint', 'note', 'empty', 'scope-chip', 'rows'].forEach((id) => { nodes[id] = stubNode(); });

// range.js 必须在设置 global.window **之前** require：它是浏览器 IIFE，
// 检测到 window 就把 SWRanges 挂到 window 上（Node 里则挂到 module.exports），
// 顺序反了会拿到空对象 —— 而它提供时间窗→桶范围的口径，缺了整页都算不出窗口。
const RANGE_MOD = require(path.resolve(
  __dirname, '../../agent/demo-app/src/main/resources/static/dashboards/range.js'));
global.SWRanges = RANGE_MOD.SWRanges;
global.window = { open() {} };
global.document = {
  getElementById: (id) => nodes[id] || null,
  querySelectorAll() { return []; },
};

const src = fs.readFileSync(PAGE, 'utf8');
const body = [
  slice(src, 'var currentRange = SWRanges.DEFAULT_RANGE;', 'function load(){'),  // 状态/SORTS/格式化/ranked/delta/render
  slice(src, 'function load(){', '/** 演示模式结论卡'),                          // load
  'function presenterConclusion(){}',                                          // 演示结论不参与本测试
  'module.exports = {',
  '  render: render, ranked: ranked, delta: delta, load: load,',
  '  setSort: function(k){ sortKey = k; }, setN: function(n){ topN = n; },',
  '  setRange: function(r){ currentRange = r; },',
  '  setTruncated: function(v){ aggTruncated = v; },',
  '  resetRanks: function(){ lastRanks = null; }',
  '};',
].join('\n');

// 包一层 CommonJS 外壳再 require：被测片段用的是页面里的裸 var/function，不是 module 作用域。
const tmp = path.join(require('os').tmpdir(), 'slow-topn-under-test-' + process.pid + '.js');
fs.writeFileSync(tmp,
  'var module_ = { exports: {} };\n(function(module, exports){\n' + body
  + '\n})(module_, module_.exports);\nmodule.exports = module_.exports;\n');
process.on('exit', () => { try { fs.unlinkSync(tmp); } catch (e) { /* 清理失败不影响结论 */ } });
// eslint-disable-next-line import/no-dynamic-require
const M = require(tmp);

console.log('== 共享时间窗口径（SWRanges）==');
ok('RANGES 档位齐全', Object.keys(RANGE_MOD.SWRanges.RANGES).join(',') === '1h,6h,24h,7d,30d',
   Object.keys(RANGE_MOD.SWRanges.RANGES).join(','));
(function(){
  const now = Date.parse('2026-10-01T10:00:00Z');
  const to = Math.floor(now / 60000);
  const b1h = RANGE_MOD.SWRanges.buckets('1h', now);
  const b24h = RANGE_MOD.SWRanges.buckets('24h', now);
  const b7d = RANGE_MOD.SWRanges.buckets('7d', now);
  ok('1h → 60 个分钟桶', b1h.to - b1h.from === 59, JSON.stringify(b1h));
  ok('24h → 分钟分辨率（正好卡在分界上）', b24h.res === 'minute', b24h.res);
  ok('7d → 小时分辨率', b7d.res === 'hour', b7d.res);
  ok('to 是当前分钟桶', b1h.to === to);
  ok('不认识/缺省的档位回落到 24h', RANGE_MOD.SWRanges.buckets('nope', now).range === '24h');
}());


// ---------------------------------------------------------------- 断言
let fails = 0;
function ok(label, cond, extra) {
  if (cond) { console.log('  ok   ' + label); }
  else { fails++; console.log('  FAIL ' + label + (extra ? '\n         ' + extra : '')); }
}
function has(label, needle) {
  ok(label, nodes.rows.innerHTML.indexOf(needle) >= 0, 'got: ' + nodes.rows.innerHTML.slice(0, 300));
}
function hasNot(label, needle) {
  ok(label, nodes.rows.innerHTML.indexOf(needle) < 0, 'got: ' + nodes.rows.innerHTML.slice(0, 300));
}

/** 与服务端 MetricsRow.toMap() 同构（camelCase）。 */
const ROWS = [
  { endpoint: 'GET:/api/order/1', requestCount: 40, errorCount: 1, slowCount: 40,
    errorRate: 0.025, slowRate: 1.0, avgLatency: 8100, maxLatency: 8600,
    p50: 8000, p90: 8400, p95: 8500, p99: 8600,
    worstP50: 8100, worstP95: 8600, worstP99: 8600, bucketCount: 12 },
  { endpoint: 'GET:/api/export/report', requestCount: 12, errorCount: 0, slowCount: 11,
    errorRate: 0, slowRate: 0.9167, avgLatency: 5200, maxLatency: 7900,
    p50: 5100, p90: 5300, p95: 5400, p99: 5500,
    worstP50: 7800, worstP95: 7900, worstP99: 7900, bucketCount: 9 },
  { endpoint: 'GET:/queryDbByJdbc', requestCount: 900, errorCount: 9, slowCount: 27,
    errorRate: 0.01, slowRate: 0.03, avgLatency: 11, maxLatency: 180,
    p50: 8, p90: 15, p95: 22, p99: 40,
    worstP50: 120, worstP95: 180, worstP99: 200, bucketCount: 60 },
];

console.log('== 首次渲染：不抛，且名次/量级/证据链都进 HTML ==');
let threw = null;
try { M.render(ROWS); } catch (e) { threw = e; }
ok('render 不抛异常（模板里未定义变量会在这里暴露）', !threw, threw && threw.message);
if (threw) { console.log('\n渲染抛异常，终止'); process.exit(1); }

has('P95 最慢的端点排第 1', 'data-href=\'trace-slow.html?endpoint=GET%3A%2Fapi%2Forder%2F1');
has('名次 1 渲染出来', '<span class="no">1</span>');
has('请求数带千分位', '900');
has('P95 格式化成秒', '8.50 s');
has('最差 P95 单独一列', '8.60 s');
has('错误率百分比', '2.50%');
has('慢率百分比', '100.00%');
has('桶数（判断样本够不够）', '<td class="num" title="该端点在窗口内落到几个桶">12</td>');
has('证据链用该端点自己的 P95 作阈值（8500ms）', 'minLatencyMs=8500');
has('首次渲染无基准 → 全部 —', '<span class="flat">—</span>');
hasNot('首次渲染不该出现「新」（那等于说每个端点都是新进的）', 'class="fresh"');
ok('hint 写明时间窗与排序依据',
  nodes.hint.textContent.indexOf('近 24h') >= 0 && nodes.hint.textContent.indexOf('P95 降序') >= 0,
  nodes.hint.textContent);
ok('首次渲染 hint 不写「名次变化 vs」（没有基准可比）',
  nodes.hint.textContent.indexOf('名次变化 vs') < 0, nodes.hint.textContent);
ok('note 写明「不是慢调用次数榜」', nodes.note.innerHTML.indexOf('慢调用次数榜') >= 0);

console.log('== 名次变化只在换档时出现（同档轮询不抖）==');
M.render(ROWS);
hasNot('同档再刷不产生 ▲', 'class="up"');
hasNot('同档再刷不产生 ▼', 'class="down"');
hasNot('同档再刷不产生「新」', 'class="fresh"');

const SHIFTED = ROWS.map((r) => (r.endpoint === 'GET:/api/export/report'
  ? Object.assign({}, r, { p95: 9000 }) : r));
M.setRange('1h');
M.render(SHIFTED);
ok('切时间窗后 hint 写明 vs 的是哪一档',
  nodes.hint.textContent.indexOf('名次变化 vs 近 24h') >= 0, nodes.hint.textContent);
has('export/report 前进一名', 'class="up">▲1<');
has('order/1 后退一名', 'class="down">▼1<');
has('queryDbByJdbc 名次不变仍是 3', '<span class="no">3</span>');

console.log('== 换排序依据也算出档 ==');
M.setSort('requestCount');
M.render(SHIFTED);
ok('按请求数降序 → 量最大的 queryDbByJdbc 第一',
  nodes.rows.innerHTML.indexOf('endpoint=GET%3A%2FqueryDbByJdbc')
  < nodes.rows.innerHTML.indexOf('endpoint=GET%3A%2Fapi%2Forder%2F1'));
ok('hint 跟着更新为「请求数降序」', nodes.hint.textContent.indexOf('请求数降序') >= 0, nodes.hint.textContent);

console.log('== 前 N 截断 ==');
M.resetRanks(); M.setSort('p95'); M.setN(2);
M.render(ROWS);
ok('只渲染前 2 行', (nodes.rows.innerHTML.match(/<tr data-href=/g) || []).length === 2,
   'rows=' + (nodes.rows.innerHTML.match(/<tr data-href=/g) || []).length);
ok('hint 写明 前 2 / 共 3', nodes.hint.textContent.indexOf('前 2 / 共 3 个') >= 0, nodes.hint.textContent);

console.log('== 边界 ==');
M.resetRanks(); M.setN(10); M.setRange('24h');   // 先复位窗口：下面断言写的是近 24h
M.render([]);
ok('空数据切到空态（不显示残留行）', nodes.empty.innerHTML.indexOf('还没有 endpoint 指标') >= 0);
ok('空数据时 hint 说明是哪个窗口', nodes.hint.textContent.indexOf('近 24h') >= 0, nodes.hint.textContent);
M.render(ROWS);
M.setTruncated(true);
M.render(ROWS);
ok('服务端截断时如实提示（不静默隐藏）', nodes.note.innerHTML.indexOf('已截断') >= 0);
M.setTruncated(false);
M.resetRanks(); M.setSort('worstP95');
M.render([{ endpoint: 'E', requestCount: 5, p95: 2, worstP95: null }]);
ok('最差 P95 缺失的行不进榜（按该依据排序时无值可排）',
  nodes.empty.innerHTML.indexOf('还没有 endpoint 指标') >= 0);
M.resetRanks(); M.setSort('p95');
M.render([{ endpoint: 'E', requestCount: 5, p95: 2, worstP95: null }]);
ok('worstP95 为 null 的行照常进榜，但该列显示 - 而不是 0（0 会被读成「分位是 0」）',
  nodes.rows.innerHTML.indexOf('<td class="num">-</td>') >= 0
  && nodes.rows.innerHTML.indexOf('>0<') < 0);

console.log(fails ? '\n断言失败 ' + fails + ' 条' : '\n全部通过');
process.exit(fails ? 1 : 0);
