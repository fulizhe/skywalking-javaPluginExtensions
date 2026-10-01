/*
 * 趋势判定（trendVerdict / trendMovers）的纯逻辑测试。
 *
 * 这段逻辑决定"能不能拿去汇报" —— 把"其实没变慢"说成"在恶化"比不画图严重得多，
 * 所以四种判定（同向恶化 / 两种背离 / 未恶化）与缺样本都要钉住。
 *
 * 跑法（仓库根）：node verify/frontend/slow-topn-trend.test.js
 */
'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');

const ROOT = path.resolve(__dirname, '../../agent/demo-app/src/main/resources/static/dashboards');
global.SWRanges = require(path.resolve(ROOT, 'range.js')).SWRanges;
const html = fs.readFileSync(path.resolve(ROOT, 'slow-topn.html'), 'utf8');

function slice(from, to) {
  const a = html.indexOf(from);
  const b = html.indexOf(to, a);
  if (a < 0 || b < 0) throw new Error('extract failed: ' + from);
  return html.slice(a, b);
}

const body = [
  "var currentRange = '24h'; var sortKey = 'p95'; var trendCache = null;",
  slice('var TREND_RANGES = [', 'function renderTrend()'),
  'module.exports = {',
  '  trendVerdict: trendVerdict, trendMovers: trendMovers, trendEndpointPick: trendEndpointPick,',
  '  __set: function(byRange){ trendCache = { at: 0, sortKey: sortKey, byRange: byRange }; }',
  '};',
].join('\n');

const tmp = path.join(os.tmpdir(), 'slow-topn-trend-' + process.pid + '.js');
fs.writeFileSync(tmp, 'var module_ = { exports: {} };\n(function(module, exports){\n' + body
  + '\n})(module_, module_.exports);\nmodule.exports = module_.exports;\n');
process.on('exit', () => { try { fs.unlinkSync(tmp); } catch (e) { /* 清理失败不影响结论 */ } });
// eslint-disable-next-line import/no-dynamic-require
const M = require(tmp);

let fails = 0;
function ok(label, cond, extra) {
  if (cond) { console.log('  ok   ' + label); }
  else { fails++; console.log('  FAIL ' + label + (extra ? '\n         ' + extra : '')); }
}
function eq(label, got, want) {
  ok(label, JSON.stringify(got) === JSON.stringify(want), 'got ' + JSON.stringify(got));
}
let LAST = null;
function cache(byRange) { LAST = byRange; M.__set(byRange); }

console.log('== 同向恶化：名次升 且 P95 也升 → 唯一可作为论据的那类 ==');
cache({
  '1h': { ranks: { slow: 5, flat: 1 }, p95: { slow: 200, flat: 900 } },
  '24h': { ranks: { slow: 2, flat: 1 }, p95: { slow: 2000, flat: 900 } }
});
eq('slow 判为同向恶化', M.trendVerdict('slow').cls, 'worse');
eq('文案是「同向恶化」', M.trendVerdict('slow').text, '同向恶化');
eq('flat 判为未恶化', M.trendVerdict('flat').cls, 'flat');
// 关键：只有**真的动了**的端点才进 movers。`flat` 名次与 P95 都没动 → 不画它，
// 否则图上又是一条静止的线（"全都不动就不画图"是同一件事）。
eq('只有恶化的进 movers，没动的不画', M.trendMovers(LAST).map(function (x) { return x.ep; }), ['slow']);

console.log('== 背离 A：名次升但 P95 没升 → 不能当它变慢的证据 ==');
cache({
  '1h': { ranks: { a: 4 }, p95: { a: 3000 } },
  '24h': { ranks: { a: 1 }, p95: { a: 2900 } }
});
eq('判为背离', M.trendVerdict('a').cls, 'split');
eq('背离方向标对', M.trendVerdict('a').text, '背离·名次升但 P95 未升');
ok('背离仍进 movers（要画出来让人看见，而不是藏起来）', M.trendMovers(LAST).length === 1);

console.log('== 背离 B：P95 升但名次没升 ==');
cache({
  '1h': { ranks: { b: 1 }, p95: { b: 1000 } },
  '24h': { ranks: { b: 1 }, p95: { b: 4000 } }
});
eq('判为背离', M.trendVerdict('b').cls, 'split');
eq('背离方向标对', M.trendVerdict('b').text, '背离·P95 升但名次未升');

console.log('== 缺样本：那个窗口没这个端点 → 不下结论 ==');
cache({
  '1h': { ranks: { c: 1 }, p95: { c: 500 } },
  '24h': { ranks: {}, p95: {} }
});
ok('样本不足返回 null（不当成「未恶化」）', M.trendVerdict('c') === null);
ok('样本不足不进 movers', M.trendMovers(LAST).length === 0);

console.log('== 全都不动 → 不画图（一张重合的直线说明不了任何事）==');
cache({
  '1h': { ranks: { d: 1, e: 2 }, p95: { d: 100, e: 200 } },
  '24h': { ranks: { d: 1, e: 2 }, p95: { d: 100, e: 200 } }
});
eq('movers 为空', M.trendMovers(LAST).length, 0);
ok('但头部端点仍选得出来（用于提示「头部 N 个都没动」）', M.trendEndpointPick(LAST).length === 2);

console.log(fails ? '\n断言失败 ' + fails + ' 条' : '\n全部通过');
process.exit(fails ? 1 : 0);
