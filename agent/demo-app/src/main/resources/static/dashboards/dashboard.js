/* demo-app 仪表盘渲染(纯原生 JS,无外部库;轮询与 scripts/validate.ps1 同一批 JSON 契约) */
"use strict";

var PAGES = {
    statistic: { title: "Trace 缓存统计", endpoints: ["/statistic"], interval: 3000 },
    jvm:       { title: "JVM 指标",      endpoints: ["/statisticJVM"], interval: 4000 },
    meter:     { title: "Meter 指标",    endpoints: ["/statisticMeter"], interval: 4000 },
    instance:  { title: "实例属性",      endpoints: ["/statisticInstanceProperties"], interval: 6000 },
    alert:     { title: "Trace 告警",    endpoints: ["/statisticTraceAlert", "/inner/sw/trace-alert/recent"], interval: 3000 },
    profile:   { title: "Profile 快照",  endpoints: ["/profileData2"], interval: 5000 }
};

/* ---------------- 工具 ---------------- */

function el(tag, cls, html) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (html !== undefined) e.innerHTML = html;
    return e;
}

function esc(s) {
    if (s === null || s === undefined) return "";
    return String(s).replace(/[&<>"]/g, function (c) {
        return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c];
    });
}

function fmtMB(v) { return (v / 1048576).toFixed(1) + " MB"; }

function fmtTime(ms) {
    if (!ms && ms !== 0) return "-";
    var d = new Date(ms);
    function p(n) { return (n < 10 ? "0" : "") + n; }
    return d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate()) + " " +
        p(d.getHours()) + ":" + p(d.getMinutes()) + ":" + p(d.getSeconds()) + "." + p(d.getMilliseconds());
}

function shortId(id) { return id ? (id.length > 24 ? id.slice(0, 12) + "…" + id.slice(-12) : id) : "-"; }

function isObject(v) { return v !== null && typeof v === "object" && !Array.isArray(v); }

function badge(text, cls) { return '<span class="badge ' + cls + '">' + esc(text) + "</span>"; }

/* 折线图:points=[[t, v], ...], yMin/yMax 可固定(如 CPU 0-100) */
function svgLine(points, yMin, yMax) {
    var W = 560, H = 120, PAD = 4;
    if (!points.length) return '<svg viewBox="0 0 ' + W + " " + H + '"></svg>';
    var minT = points[0][0], maxT = points[points.length - 1][0];
    if (maxT === minT) maxT = minT + 1;
    var lo = yMin, hi = yMax;
    if (lo === undefined) {
        lo = Math.min.apply(null, points.map(function (p) { return p[1]; }));
        hi = Math.max.apply(null, points.map(function (p) { return p[1]; }));
        if (hi === lo) { lo -= 1; hi += 1; }
    }
    var x = function (t) { return PAD + (t - minT) / (maxT - minT) * (W - 2 * PAD); };
    var y = function (v) { return H - PAD - (v - lo) / (hi - lo) * (H - 2 * PAD); };
    var d = points.map(function (p, i) {
        return (i ? "L" : "M") + x(p[0]).toFixed(1) + " " + y(p[1]).toFixed(1);
    }).join(" ");
    var grid = "", gv;
    for (var i = 1; i < 4; i++) {
        gv = lo + (hi - lo) * i / 4;
        grid += '<line x1="' + PAD + '" y1="' + y(gv).toFixed(1) + '" x2="' + (W - PAD) + '" y2="' + y(gv).toFixed(1) + '" stroke="#e9ecef" stroke-width="1"/>';
    }
    return '<svg viewBox="0 0 ' + W + " " + H + '" preserveAspectRatio="none">' + grid +
        '<polyline points="' + d + '" fill="none" stroke="#1f6feb" stroke-width="2"/></svg>';
}

function lastUpdated() {
    document.getElementById("updated").textContent = "最后更新 " + fmtTime(Date.now());
}

/* ---------------- 数据拉取 ---------------- */

function fetchJson(path) {
    return new Promise(function (resolve) {
        var ctrl = new AbortController();
        var timer = setTimeout(function () { ctrl.abort(); }, 10000);
        fetch(path, { signal: ctrl.signal })
            .then(function (r) { return r.json(); })
            .then(function (j) { clearTimeout(timer); resolve({ ok: true, data: j }); })
            .catch(function (e) { clearTimeout(timer); resolve({ ok: false, err: String(e) }); });
    });
}

/* 无插件提示 map 恒含 plugin 键(与验证脚本同一契约) */
function isPluginHint(v) {
    return isObject(v) && typeof v.plugin === "string";
}

/* ---------------- 各页渲染 ---------------- */

var RENDER = {};

/* ---- 01 统计:缓存表 + span 时间线 ---- */
RENDER.statistic = function (results, box) {
    var s = results[0];
    if (isPluginHint(s)) { showHint(s, box); return; }
    box.innerHTML = "";
    var entries = Object.entries(s.data || {}).sort(function (a, b) {
        return maxEnd(b[1]) - maxEnd(a[1]);
    });
    var totalSpans = 0, totalSegs = 0, errTraces = 0;
    entries.forEach(function (e) {
        var logs = e[1].logs || [];
        totalSegs += logs.length;
        logs.forEach(function (l) { totalSpans += (l.spans || []).length; });
        if (logs.some(function (l) { return (l.spans || []).some(function (sp) { return sp.isError; }); })) errTraces++;
    });

    var chips = el("div", "chips");
    chips.appendChild(chip("运行时报告", s.enableLogfileReporter ? badge("开启", "ok") : badge("关闭", "err")));
    chips.appendChild(chip("trace 数", entries.length));
    chips.appendChild(chip("segment 数", totalSegs));
    chips.appendChild(chip("span 数", totalSpans));
    chips.appendChild(chip("含错误 span 的 trace", errTraces));
    chips.appendChild(chip("缓存上限", s.maxLogSize + " 条 trace"));
    box.appendChild(chips);

    var panel = el("div", "panel");
    panel.appendChild(el("h3", null, "trace 缓存表(点击行展开 span 时间线)"));
    var table = el("table");
    var thead = el("thead");
    thead.innerHTML = "<tr><th>traceId</th><th>segment</th><th>span</th><th>错误</th><th>操作</th><th>开始</th><th>结束</th></tr>";
    table.appendChild(thead);
    var tbody = el("tbody");
    entries.slice(0, 200).forEach(function (e) {
        var logs = e[1].logs || [];
        var spans = [];
        logs.forEach(function (l) { spans = spans.concat(l.spans || []); });
        var errs = spans.filter(function (sp) { return sp.isError; }).length;
        var ops = unique(spans.map(function (sp) { return sp.operationName; }));
        var tr = el("tr", "clickable");
        tr.innerHTML = "<td class='mono' title='" + esc(e[0]) + "'>" + esc(shortId(e[0])) + "</td>" +
            "<td>" + logs.length + "</td><td>" + spans.length + "</td>" +
            "<td>" + (errs ? badge(errs + " 错误", "err") : "-") + "</td>" +
            "<td title='" + esc(ops.join(", ")) + "'>" + esc(ops.slice(0, 3).join(", ")) + (ops.length > 3 ? " …" : "") + "</td>" +
            "<td class='mono'>" + esc(minStart(spans)) + "</td><td class='mono'>" + esc(maxEndReadable(e[1])) + "</td>";
        tr.appendChild(expandRow(e[0], logs));
        tr.addEventListener("click", function () {
            var detail = tr.nextElementSibling;
            detail.style.display = detail.style.display === "none" ? "" : "none";
        });
        tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    panel.appendChild(table);
    box.appendChild(panel);

    if (!entries.length) box.appendChild(el("div", "empty", "暂无 trace 缓存。请先通过验证回路(validate.ps1)或手动请求触发流量。"));
};

function unique(arr) { return Array.from(new Set(arr)); }

function maxEnd(entry) {
    var m = 0;
    (entry.logs || []).forEach(function (l) {
        (l.spans || []).forEach(function (sp) { if (sp.endTime > m) m = sp.endTime; });
    });
    return m;
}

function minStart(entry) {
    var m = Infinity;
    (entry.logs || []).forEach(function (l) {
        (l.spans || []).forEach(function (sp) { if (sp.startTime < m) m = sp.startTime; });
    });
    return m === Infinity ? "-" : fmtTime(m);
}

function maxEndReadable(entry) {
    var m = 0;
    (entry.logs || []).forEach(function (l) {
        (l.spans || []).forEach(function (sp) { if (sp.endTime > m) m = sp.endTime; });
    });
    return m ? fmtTime(m) : "-";
}

function expandRow(traceId, logs) {
    var tr = el("tr");
    tr.style.display = "none";
    var td = el("td");
    td.colSpan = 7;
    var spans = [];
    var spansBySeg = [];
    logs.forEach(function (l) {
        spansBySeg.push({ seg: l.traceSegmentId, spans: l.spans || [] });
        spans = spans.concat(l.spans || []);
    });
    spans.sort(function (a, b) { return a.startTime - b.startTime; });
    var minT = spans.length ? spans[0].startTime : 0;
    var maxT = spans.length ? Math.max.apply(null, spans.map(function (sp) { return sp.endTime; })) : 1;
    var range = (maxT - minT) || 1;

    var timeline = el("div", "timeline");
    spans.forEach(function (sp) {
        var row = el("div", "tl-row");
        var cls = "tl-bar " + esc(sp.spanType || "Local") + (sp.isError ? " error" : "");
        row.innerHTML = "<div class='tl-op' title='" + esc(sp.operationName) + "'>" + esc(sp.operationName) + "</div>" +
            "<div class='tl-track'><div class='" + cls + "' style='left:" +
            ((sp.startTime - minT) / range * 100).toFixed(2) + "%;width:" +
            Math.max(((sp.endTime - sp.startTime) / range * 100), 0.3).toFixed(2) + "%'></div></div>" +
            "<div class='tl-dur'>" + (sp.endTime - sp.startTime) + " ms</div>";
        timeline.appendChild(row);
    });
    td.appendChild(timeline);

    var segInfo = el("div", null, "");
    spansBySeg.forEach(function (sg) {
        segInfo.appendChild(el("div", null, "segment <code>" + esc(sg.seg) + "</code> — " + sg.spans.length + " span"));
    });
    td.appendChild(segInfo);

    var table = el("table");
    var thead = el("thead");
    thead.innerHTML = "<tr><th>operationName</th><th>类型</th><th>层</th><th>组件</th><th>耗时</th><th>错误</th><th>标签</th></tr>";
    table.appendChild(thead);
    var tbody = el("tbody");
    spans.forEach(function (sp) {
        var tags = (sp.tagList || []).map(function (t) { return esc(t["tag-key"]) + "=" + esc(t["tag-value"]); }).join(" ");
        tbody.appendChild(el("tr", null,
            "<td class='mono'>" + esc(sp.operationName) + "</td>" +
            "<td>" + badge(sp.spanType || "-", "info") + "</td>" +
            "<td>" + esc(sp.spanLayer || "-") + "</td>" +
            "<td>" + esc(sp.componentName || sp.componentId || "-") + "</td>" +
            "<td class='mono'>" + (sp.endTime - sp.startTime) + " ms</td>" +
            "<td>" + (sp.isError ? badge("是", "err") : "否") + "</td>" +
            "<td class='mono'>" + esc(tags) + "</td>"));
    });
    table.appendChild(tbody);
    td.appendChild(table);
    tr.appendChild(td);
    return tr;
}

/* ---- 02 JVM ---- */
RENDER.jvm = function (results, box) {
    var data = results[0];
    if (isPluginHint(data)) { showHint(data, box); return; }
    box.innerHTML = "";
    if (!Array.isArray(data) || !data.length) { emptyBox(box); return; }
    var coll = data[data.length - 1];
    /* JVM 采集为批次上报(每批含 1~2 个指标点),跨批次合并为完整时间序列 */
    var series = [];
    data.forEach(function (c) { (c.metrics || []).forEach(function (m) { series.push(m); }); });
    series.sort(function (a, b) { return a.time - b.time; });
    var last = series[series.length - 1];
    if (!last) { emptyBox(box); return; }

    var meta = el("div", "chips");
    meta.appendChild(chip("service", coll.service));
    meta.appendChild(chip("instance", coll.serviceInstance));
    meta.appendChild(chip("采集批次", data.length));
    meta.appendChild(chip("采样点数", series.length));
    box.appendChild(meta);

    var window = series.slice(-120);
    var grid = el("div", "chart-grid");
    grid.appendChild(chartBox("CPU 使用率(%)", svgLine(window.map(function (m) { return [m.time, m.cpu]; }), 0, 100)));
    grid.appendChild(chartBox("堆内存 used(MB)", svgLine(window.map(function (m) {
        var heap = (m.memory || []).find(function (mm) { return mm.isHeap; });
        return [m.time, heap ? heap.used / 1048576 : 0];
    }))));
    grid.appendChild(chartBox("活跃线程", svgLine(window.map(function (m) {
        return [m.time, (m.thread && m.thread.live) || 0];
    }))));
    box.appendChild(grid);

    var panel = el("div", "panel");
    panel.appendChild(el("h3", null, "最新采样(" + fmtTime(last.time) + ")"));
    var kv = el("div", "chips");
    kv.appendChild(chip("CPU", last.cpu.toFixed(2) + "%"));
    kv.appendChild(chip("线程 live/daemon", (last.thread ? last.thread.live + " / " + last.thread.daemon : "-")));
    kv.appendChild(chip("类加载", last.clazz ? last.clazz.loaded + " (累计 " + last.clazz.total_loaded + ")" : "-"));
    panel.appendChild(kv);

    var cols = el("div", "chart-grid");
    var memTable = simpleTable("内存", ["区", "used", "committed", "max"], (last.memory || []).map(function (m) {
        return [m.isHeap ? "堆" : "非堆", fmtMB(m.used), fmtMB(m.committed), m.max > 0 ? fmtMB(m.max) : "无上限"];
    }));
    var poolTable = simpleTable("内存池", ["type", "used", "committed", "max"], (last.memoryPool || []).map(function (m) {
        return [m.type, fmtMB(m.used), fmtMB(m.committed), m.max > 0 ? fmtMB(m.max) : "无上限"];
    }));
    var gcTable = simpleTable("GC", ["阶段", "次数", "耗时 ms"], (last.gc || []).map(function (g) {
        return [g.phrase, g.count, g.time];
    }));
    var threadTable = simpleTable("线程状态", ["状态", "数量"], Object.entries(last.thread || {}).map(function (e) {
        return [e[0], e[1]];
    }));
    cols.appendChild(el("div", "chart-box", null)).appendChild(memTable);
    cols.appendChild(el("div", "chart-box", null)).appendChild(poolTable);
    cols.appendChild(el("div", "chart-box", null)).appendChild(gcTable);
    cols.appendChild(el("div", "chart-box", null)).appendChild(threadTable);
    panel.appendChild(cols);
    box.appendChild(panel);
};

/* ---- 03 Meter ---- */
RENDER.meter = function (results, box) {
    var data = results[0];
    if (isPluginHint(data)) { showHint(data, box); return; }
    box.innerHTML = "";
    var keys = Object.keys(data || {}).sort();
    if (!keys.length) { emptyBox(box); return; }
    var latest = data[keys[keys.length - 1]];
    var meters = latest.meters || [];
    var state = { filter: "" };

    var row = el("div", "search-row");
    var input = el("input");
    input.placeholder = "按指标名/标签过滤…";
    input.addEventListener("input", function () {
        state.filter = input.value.trim().toLowerCase();
        drawCards();
    });
    row.appendChild(input);
    row.appendChild(el("span", null, "采集时间 <code>" + esc(latest.humanTime || fmtTime(latest.time)) + "</code>,共 " + meters.length + " 项"));
    box.appendChild(row);

    var gridEl = el("div", "meter-grid");
    box.appendChild(gridEl);

    function drawCards() {
        gridEl.innerHTML = "";
        meters.forEach(function (m) {
            var sv = m.singleValue, hv = m.histogram;
            var name = sv ? sv.name : (hv && hv.name) || "-";
            var labels = sv ? sv.labels : (hv && hv.labels) || {};
            var hay = (name + " " + JSON.stringify(labels)).toLowerCase();
            if (state.filter && hay.indexOf(state.filter) < 0) return;
            var value = sv ? fmtNum(sv.value) : "histogram";
            var card = el("div", "meter-card");
            card.innerHTML = badge(m.type === "HISTOGRAM" ? "hist" : "single", "info") +
                "<div class='m-name'>" + esc(name) + "</div>" +
                "<div class='m-labels'>" + esc(Object.entries(labels).map(function (e) { return e[0] + "=" + e[1]; }).join(" ")) + "</div>" +
                "<div class='m-value'>" + esc(value) + "</div>";
            gridEl.appendChild(card);
        });
        if (!gridEl.children.length) gridEl.appendChild(el("div", "empty", "无匹配指标"));
    }
    drawCards();
};

function fmtNum(v) {
    if (v === null || v === undefined) return "-";
    return Math.abs(v) >= 1e6 || (Math.abs(v) > 0 && Math.abs(v) < 1e-4) ? v.toExponential(3) : Number(v.toFixed(4)).toString();
}

/* ---- 04 实例属性 ---- */
RENDER.instance = function (results, box) {
    var data = results[0];
    if (isPluginHint(data)) { showHint(data, box); return; }
    box.innerHTML = "";
    var entries = Object.entries(data || {}).sort(function (a, b) { return a[0] < b[0] ? -1 : 1; });
    if (!entries.length) { emptyBox(box); return; }
    var panel = el("div", "panel");
    panel.appendChild(el("h3", null, "心跳携带的实例属性(" + entries.length + " 项)"));
    var table = el("table");
    table.appendChild(el("thead", null, "<tr><th style='width:220px'>属性</th><th>值</th></tr>"));
    var tbody = el("tbody");
    entries.forEach(function (e) {
        tbody.appendChild(el("tr", null,
            "<td class='mono'>" + esc(e[0]) + "</td><td class='mono'>" + esc(typeof e[1] === "string" ? e[1] : JSON.stringify(e[1])) + "</td>"));
    });
    table.appendChild(tbody);
    panel.appendChild(table);
    box.appendChild(panel);
};

/* ---- 05 告警 ---- */
RENDER.alert = function (results, box) {
    var stat = results[0], recent = results[1];
    if (isPluginHint(stat)) { showHint(stat, box); return; }
    box.innerHTML = "";
    var cfg = stat.config || {}, disp = stat.dispatcher || {}, hook = stat.httpWebhook || {};

    var chips = el("div", "chips");
    chips.appendChild(chip("启用", cfg.enabled ? badge("开启", "ok") : badge("关闭", "err")));
    chips.appendChild(chip("webhook 目标", "<code>" + esc(cfg.webhookResolvedUrl || "-") + "</code>"));
    chips.appendChild(chip("默认慢阈值", cfg.defaultSlowThresholdMs + " ms"));
    chips.appendChild(chip("HTTP 错误阈值", ">= " + cfg.httpErrorStatusMin));
    box.appendChild(chips);

    var chips2 = el("div", "chips");
    chips2.appendChild(chip("分发 submitted", disp.dispatchSubmitted));
    chips2.appendChild(chip("慢", disp.dispatchSlowCount));
    chips2.appendChild(chip("错误", disp.dispatchErrorCount));
    chips2.appendChild(chip("拒绝", disp.dispatchRejected));
    chips2.appendChild(chip("去重跳过", disp.dispatchSkippedDuplicate));
    chips2.appendChild(chip("webhook 尝试/成功", hook.totalAttempts + " / " + hook.successCount));
    chips2.appendChild(chip("失败率", hook.successRatePercent + "%"));
    box.appendChild(chips2);

    var panel = el("div", "panel");
    panel.appendChild(el("h3", null, "规则与命中(" + (stat.rules || []).length + ")"));
    var table = el("table");
    table.appendChild(el("thead", null, "<tr><th>类型</th><th>规则</th><th>语法</th><th>命中</th></tr>"));
    var tbody = el("tbody");
    (stat.rules || []).forEach(function (r) {
        tbody.appendChild(el("tr", null,
            "<td>" + (r.type === "SLOW" ? badge("SLOW", "warn") : badge(r.type, "ok")) + "</td>" +
            "<td class='mono'>" + esc(r.rule) + "</td>" +
            "<td>" + esc(r.syntax) + "</td><td>" + r.hitCount + "</td>"));
    });
    table.appendChild(tbody);
    panel.appendChild(table);
    box.appendChild(panel);

    var evPanel = el("div", "panel");
    evPanel.appendChild(el("h3", null, "webhook 收讫事件(" + (recent && recent.count != null ? recent.count : 0) + ")"));
    var evTable = el("table");
    evTable.appendChild(el("thead", null, "<tr><th>收讫时间</th><th>traceId</th><th>类型</th><th>url</th><th>耗时</th><th>错误 span</th></tr>"));
    var evBody = el("tbody");
    ((recent && recent.events) || []).forEach(function (ev) {
        var types = (ev.alertTypes || []).map(function (t) { return badge(t, t === "ERROR" ? "err" : "warn"); }).join(" ");
        evBody.appendChild(el("tr", null,
            "<td class='mono'>" + esc(ev._receivedAt || "-") + "</td>" +
            "<td class='mono' title='" + esc(ev.traceId) + "'>" + esc(shortId(ev.traceId)) + "</td>" +
            "<td>" + types + "</td>" +
            "<td class='mono'>" + esc(ev.url || "-") + "</td>" +
            "<td class='mono'>" + (ev.durationMs != null ? ev.durationMs + " ms" : "-") + "</td>" +
            "<td>" + (ev.errorSpanCount != null ? ev.errorSpanCount : "-") + "</td>"));
    });
    evTable.appendChild(evBody);
    evPanel.appendChild(evTable);
    if (!(recent && recent.events && recent.events.length)) {
        evPanel.appendChild(el("div", "empty", "暂无收讫事件。触发方法:GET /api/trace-alert-demo/slow?ms=4000(慢)、/api/trace-alert-demo/error(错误)。"));
    }
    box.appendChild(evPanel);
};

/* ---- 06 Profile ---- */
RENDER.profile = function (results, box) {
    var data = results[0];
    if (isPluginHint(data)) { showHint(data, box); return; }
    box.innerHTML = "";
    var snaps = Array.isArray(data) ? data : [];
    var msg = el("span", "note");

    var row = el("div", "btn-row");
    var startBtn = el("button", null, "开始采样(profile)");
    startBtn.addEventListener("click", function () {
        var body = { endpointName: "GET:/longTimeTask", minDurationThreshold: 1, maxSamplingCount: 5, dumpPeriod: 1000, duration: 1 };
        fetch("/profile", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) })
            .then(function () { msg.textContent = "采样已启动(约 5 秒后采集)。请持续点击 [压测负载] 制造采样流量…"; })
            .catch(function () { msg.textContent = "采样启动失败:请确认以 agent 模式运行。"; });
    });
    var loadBtn = el("button", "secondary", "压测负载 ×1");
    loadBtn.addEventListener("click", function () { fetch("/longTimeTask"); });
    var loadBtn5 = el("button", "secondary", "压测负载 ×5");
    loadBtn5.addEventListener("click", function () {
        for (var i = 0; i < 5; i++) fetch("/longTimeTask");
    });
    row.appendChild(startBtn);
    row.appendChild(loadBtn);
    row.appendChild(loadBtn5);
    row.appendChild(msg);
    box.appendChild(row);

    if (!snaps.length) {
        box.appendChild(el("div", "empty", "暂无 profile 快照。点击 [开始采样] 后持续 [压测负载],数秒后本页自动出现快照。"));
        return;
    }
    var panel = el("div", "panel");
    panel.appendChild(el("h3", null, "线程快照(" + snaps.length + ",最新在前)"));
    var table = el("table");
    table.appendChild(el("thead", null, "<tr><th>采样时间</th><th>taskId</th><th>seq</th><th>traceSegmentId</th><th>栈深度</th></tr>"));
    var tbody = el("tbody");
    snaps.slice().sort(function (a, b) { return (b.time || 0) - (a.time || 0); }).forEach(function (snap) {
        var sigs = (snap.stack && snap.stack.codeSignatures) || [];
        var tr = el("tr", "clickable");
        tr.innerHTML = "<td class='mono'>" + esc(fmtTime(snap.time)) + "</td>" +
            "<td class='mono'>" + esc(snap.taskId || "-") + "</td>" +
            "<td>" + (snap.sequence != null ? snap.sequence : "-") + "</td>" +
            "<td class='mono'>" + esc(shortId(snap.traceSegmentId)) + "</td>" +
            "<td>" + sigs.length + "</td>";
        var detail = el("tr");
        detail.style.display = "none";
        var td = el("td");
        td.colSpan = 5;
        var boxEl = el("div", "stack-box");
        sigs.forEach(function (s, i) {
            boxEl.appendChild(el("div", null, (i + 1).toString().padStart(2, "0") + "  " + esc(s)));
        });
        td.appendChild(boxEl);
        detail.appendChild(td);
        tr.addEventListener("click", function () {
            detail.style.display = detail.style.display === "none" ? "" : "none";
        });
        tbody.appendChild(tr);
        tbody.appendChild(detail);
    });
    table.appendChild(tbody);
    panel.appendChild(table);
    box.appendChild(panel);
};

/* ---------------- 通用 ---------------- */

function chip(label, valueHtml) {
    var c = el("div", "chip");
    c.appendChild(el("div", "label", esc(label)));
    var v = el("div", "value");
    v.innerHTML = valueHtml;
    c.appendChild(v);
    return c;
}

function chartBox(title, svgHtml) {
    var b = el("div", "chart-box");
    b.appendChild(el("div", "chart-title", esc(title)));
    var wrap = el("div");
    wrap.innerHTML = svgHtml;
    b.appendChild(wrap);
    return b;
}

function simpleTable(title, headers, rows) {
    var wrap = el("div");
    wrap.appendChild(el("div", "chart-title", esc(title)));
    var t = el("table");
    t.appendChild(el("thead", null, "<tr>" + headers.map(function (h) { return "<th>" + esc(h) + "</th>"; }).join("") + "</tr>"));
    var tbody = el("tbody");
    rows.forEach(function (r) {
        tbody.appendChild(el("tr", null, r.map(function (c) { return "<td class='mono'>" + esc(c) + "</td>"; }).join("")));
    });
    t.appendChild(tbody);
    wrap.appendChild(t);
    return wrap;
}

function emptyBox(box) {
    box.appendChild(el("div", "empty", "暂无数据。"));
}

function showHint(data, box) {
    box.innerHTML = "";
    box.appendChild(el("div", "empty", esc(data.hint || "插件未挂载或数据为空。")));
}

/* ---------------- 启动 ---------------- */

function boot() {
    var p = new URLSearchParams(location.search).get("p") || "statistic";
    var page = PAGES[p];
    if (!page) { document.getElementById("content").innerHTML = "未知页面: " + esc(p); return; }

    document.title = page.title + " — demo-app 仪表盘";
    document.getElementById("page-title").textContent = page.title;
    document.getElementById("poll-state").textContent = "自动刷新 " + (page.interval / 1000) + "s";

    var nav = document.getElementById("page-nav");
    Object.entries(PAGES).forEach(function (kv) {
        var a = el("a", kv[0] === p ? "active" : "", kv[1].title);
        a.href = "dashboard.html?p=" + kv[0];
        nav.appendChild(a);
    });

    var hintBox = document.getElementById("hint");
    var contentBox = document.getElementById("content");
    var refreshBtn = document.getElementById("refresh-btn");
    refreshBtn.addEventListener("click", refresh);

    function refresh() {
        var url = "/" + location.pathname.split("/").pop();
        Promise.all(page.endpoints.map(fetchJson)).then(function (results) {
            var anyFail = results.some(function (r) { return !r.ok; });
            hintBox.classList.add("hidden");
            if (anyFail) {
                hintBox.textContent = "接口请求失败: " + results.filter(function (r) { return !r.ok; }).map(function (r) { return r.err; }).join("; ");
                hintBox.classList.remove("hidden");
            }
            RENDER[p](results.map(function (r) { return r.data; }), contentBox);
            lastUpdated();
        });
    }
    refresh();
    setInterval(refresh, page.interval);
}

document.addEventListener("DOMContentLoaded", boot);
