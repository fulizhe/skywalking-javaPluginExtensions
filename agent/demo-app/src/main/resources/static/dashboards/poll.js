/**
 * 刷新节奏统一控件 —— 所有会轮询的仪表盘页共用（指标大屏 / 排障页 / 依赖拓扑 / dashboard 七子页）。
 *
 * <p><b>要解决的问题</b>：这些页面各自维护一套 setInterval（3s~6s 不等），频率写死在代码里，
 * 用户只能"看着它刷"、没法调；而开着标签页不管时（比如去开会），后台仍在按秒级轮询读口 ——
 * 监控反过来打扰了被监控的业务系统。
 *
 * <p><b>提供三件事</b>：
 * <ol>
 *   <li><b>间隔可选 + 暂停/继续</b>：浮在左下角的一个控件，状态存 localStorage；</li>
 *   <li><b>页面不可见时自动暂停</b>（不用用户做任何事，也关不掉 —— 它只会省事）；</li>
 *   <li><b>演示模式自动冻结</b>：进入演示态停轮询、退出恢复。讲解时数字不跳、行内展开的
 *       详情不抖，截图也稳。</li>
 * </ol>
 *
 * <p><b>刻意不接管页面的定时器</b>：本页只持有"策略"（间隔多长、现在该不该刷）与控件 UI，
 * 定时器仍归各页 —— {@code start}/{@code stop} 由页面实现，页面在 {@code start} 里用
 * {@link intervalMs} 取间隔。这样每页改动只有三行，也不会因为本页出错就让整页刷新逻辑瘫掉。
 *
 * <p>用法：
 * <pre>
 *   &lt;script src="poll.js"&gt;&lt;/script&gt;            // 1) 引入（自带样式，无需再链 CSS）
 *   setInterval(refresh, SWPoll ? SWPoll.intervalMs("metrics", 5000) : 5000);   // 2) 间隔不写死
 *   SWPoll.attach({ key: "metrics", defaultMs: 5000, start: startPolling, stop: stopPolling,
 *                   onState: function (s) { updateMyButtonLabel(s); } });        // 3) 挂一次
 * </pre>
 */
(function (global) {
    "use strict";

    var STORAGE = "sw.poll.";
    /** 可选间隔。刻意给档位而不是自由输入框：手输能把 5000 敲成 500，轮询直接 10 倍。 */
    var CHOICES = [3000, 5000, 10000, 30000, 60000];
    var CSS = ".poll-box{position:fixed;left:14px;bottom:14px;z-index:60;display:flex;align-items:center;gap:6px;"
        + "padding:5px 8px;background:rgba(18,22,28,.92);border:1px solid #3d4650;border-radius:8px;"
        + "box-shadow:0 6px 20px rgba(0,0,0,.4);font:12px/1 -apple-system,'Segoe UI',Roboto,'Microsoft YaHei',sans-serif;color:#eaeaea}"
        + ".poll-dot{width:8px;height:8px;border-radius:50%;background:#86efac;flex:none}"
        + ".poll-dot.is-paused{background:#fde047}"
        + ".poll-sel{background:#2b313a;color:#eaeaea;border:1px solid #3d4650;border-radius:4px;padding:4px 6px;font:inherit}"
        + ".poll-btn{background:#2b313a;color:#eaeaea;border:1px solid #3d4650;border-radius:4px;padding:4px 9px;cursor:pointer;font:inherit}"
        + ".poll-btn:hover{filter:brightness(1.15)}"
        // 演示态是"成品画面"，刷新控件属于运维件，藏起来（presenter.css 未加载时无副作用）
        + "body.presenter-on .poll-box{display:none}";

    var state = {};
    /** 已挂载页面的 sync 引用：让页面自带的暂停按钮能驱动同一份状态。 */
    var attached = {};

    function read(key, defaultMs) {
        if (!state[key]) {
            var raw = null;
            try {
                raw = global.localStorage.getItem(STORAGE + key);
            } catch (e) {
                /* 隐私模式禁写：只影响本次会话的跨页保持，不该报错 */
            }
            var saved = null;
            if (raw) {
                try {
                    saved = JSON.parse(raw);
                } catch (e) {
                    saved = null;
                }
            }
            var ms = Number(saved && saved.ms);
            state[key] = {
                ms: CHOICES.indexOf(ms) >= 0 ? ms : defaultMs,
                userPaused: !!(saved && saved.userPaused),
                hidden: false,
                presenter: false
            };
        }
        return state[key];
    }

    function write(key) {
        try {
            global.localStorage.setItem(STORAGE + key, JSON.stringify(state[key]));
        } catch (e) {
            /* 同上 */
        }
    }

    /** 该页当前应使用的间隔毫秒；页面在自己的 start() 里用它，别再写死数字。 */
    function intervalMs(key, defaultMs) {
        return read(key, defaultMs).ms;
    }

    /** 此刻是否应当停止轮询（用户暂停 / 页面不可见 / 演示态，三者任一成立即为停）。 */
    function isPaused(key, defaultMs) {
        var s = read(key, defaultMs);
        return s.userPaused || s.hidden || s.presenter;
    }

    function reason(s) {
        if (s.presenter) {
            return "演示模式已冻结刷新（退出演示即恢复）";
        }
        if (s.hidden) {
            return "页面不在前台，已自动暂停（切回即恢复）";
        }
        return "已手动暂停";
    }

    function attach(cfg) {
        var key = cfg.key;
        var s = read(key, cfg.defaultMs);
        var ui = null;

        function sync() {
            var paused = s.userPaused || s.hidden || s.presenter;
            // 页面自己管定时器，这里只下"该跑/该停"的指令；stop() 必须可重复调用。
            if (paused) {
                cfg.stop();
            } else {
                cfg.start();
            }
            if (ui) {
                ui.dot.className = "poll-dot" + (paused ? " is-paused" : "");
                ui.btn.textContent = paused ? "继续刷新" : "暂停刷新";
                ui.box.title = paused ? reason(s) : ("每 " + (s.ms / 1000) + "s 自动刷新一次");
            }
            if (cfg.onState) {
                cfg.onState({ paused: paused, ms: s.ms });
            }
        }

        var style = global.document.createElement("style");
        style.textContent = CSS;
        global.document.head.appendChild(style);

        var box = global.document.createElement("div");
        box.className = "poll-box";
        var dot = global.document.createElement("span");
        dot.className = "poll-dot";
        var sel = global.document.createElement("select");
        sel.className = "poll-sel";
        sel.title = "刷新间隔";
        CHOICES.forEach(function (ms) {
            var opt = global.document.createElement("option");
            opt.value = String(ms);
            opt.textContent = ms / 1000 + "s";
            if (ms === s.ms) {
                opt.selected = true;
            }
            sel.appendChild(opt);
        });
        var btn = global.document.createElement("button");
        btn.className = "poll-btn";
        btn.type = "button";
        box.appendChild(dot);
        box.appendChild(sel);
        if (cfg.once) {
            // 手动刷一次。页面顶栏那些"刷新/停止刷新"按钮已被本控件取代（避免两处控制打架），
            // 手动刷一次的能力不能跟着一起没，所以在这里补回来。
            var onceBtn = global.document.createElement("button");
            onceBtn.className = "poll-btn";
            onceBtn.type = "button";
            onceBtn.textContent = "立即刷新";
            onceBtn.title = "立刻取一次数据（不影响自动刷新节奏）";
            onceBtn.addEventListener("click", function () {
                cfg.once();
            });
            box.appendChild(onceBtn);
        }
        box.appendChild(btn);
        global.document.body.appendChild(box);
        ui = { box: box, dot: dot, sel: sel, btn: btn };

        sel.addEventListener("change", function () {
            s.ms = Number(sel.value);
            write(key);
            // 换档要立刻生效：停一次再起，页面会用新的 intervalMs() 重建定时器
            cfg.stop();
            sync();
        });

        btn.addEventListener("click", function () {
            // 点的是"用户意图"：想停 → userPaused=true；想跑 → 清掉用户暂停。
            // 若此刻是被自动条件（切后台/演示态）停着的，按钮仍显示"继续刷新"，因为那个条件
            // 我们不代替用户解除 —— 免得出现"点了继续却还在停"的假动作。
            s.userPaused = !s.userPaused;
            write(key);
            sync();
        });

        global.document.addEventListener("visibilitychange", function () {
            s.hidden = global.document.hidden;
            sync();
        });

        // 演示态进出：只看 body 上的 class。两边互不调用 —— 谁先加载都能工作。
        if (global.MutationObserver) {
            new global.MutationObserver(function () {
                var on = global.document.body.classList.contains("presenter-on");
                if (on !== s.presenter) {
                    s.presenter = on;
                    sync();
                }
            }).observe(global.document.body, { attributes: true, attributeFilter: ["class"] });
        }

        sync();
        attached[key] = sync;
    }

    /** 页面自带的暂停按钮走这里，避免"页面按钮"与"统一控件"各改各的状态。 */
    function setPaused(key, on, defaultMs) {
        var s = read(key, defaultMs);
        s.userPaused = !!on;
        write(key);
        if (attached[key]) {
            attached[key]();
        }
    }

    /** 页面自带的按钮直接调它：翻转"用户暂停"意图并立刻生效。 */
    function toggle(key, defaultMs) {
        setPaused(key, !isPaused(key, defaultMs), defaultMs);
    }

    global.SWPoll = {
        attach: attach,
        intervalMs: intervalMs,
        isPaused: isPaused,
        setPaused: setPaused,
        toggle: toggle,
        CHOICES: CHOICES
    };
}(window));
