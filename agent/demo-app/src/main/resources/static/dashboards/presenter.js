/**
 * 演示模式（Presenter Mode）外壳 —— 指标大屏 / 依赖拓扑 / 告警面板 三页共用。
 *
 * <p>解决的问题：这三页原本每页都带一屏运维控件（时间窗 / 视图切换 / 停止刷新 / 轮询状态），
 * 截图给领导看时像调试页而不是成品。本外壳把"演示态"做成**一个开关**：
 * <ul>
 *   <li>开关打开 → body 加 class，CSS 把运维控件收起来、字号放大、口径按悬停展开；</li>
 *   <li>三页之间用「上一页 / 下一页」串起来，汇报时不用回导航；</li>
 *   <li>顶部结论卡：数字由各页**从读口算好后传进来**，本文件只排版。</li>
 * </ul>
 *
 * <p><b>状态持久化</b>：写 localStorage，跨页跳转后仍是演示态（汇报中途翻页不该掉模式）；
 * 也支持 URL 覆盖 `?presenter=1` / `?presenter=0` —— 截图、投屏链接直接带上它，
 * 不依赖本机 localStorage。
 *
 * <p><b>键盘</b>：`P` 切换演示模式、`←` / `→` 翻页。讲解时手不必离开键盘。
 *
 * <p>用法（各页）：
 * <pre>
 *   SWPresenter.mount({ page: "topology", theme: "dark", slot: "#presenter-slot" });
 *   SWPresenter.conclusion([{ label: "依赖类型", value: "6", sub: "自启动以来" }, ...]);
 * </pre>
 */
(function (global) {
    "use strict";

    var STORAGE_KEY = "sw.presenter.mode";

    /** 汇报动线：指标 → 拓扑 → 告警（首尾相接，翻页就是循环）。 */
    var PAGES = {
        metrics:  { href: "metrics.html",           title: "Trace 指标" },
        topology: { href: "topology.html",          title: "依赖拓扑" },
        alert:    { href: "dashboard.html?p=alert", title: "Trace 告警" }
    };
    var ORDER = ["metrics", "topology", "alert"];

    var shell = null;
    var cardItems = null;
    /** 当前页在动线里的 key（metrics / topology / alert）；翻页要靠它定位前后页。 */
    var currentPage = null;

    function readMode() {
        var q = /[?&]presenter=([01])/.exec(global.location.search);
        if (q) {
            return q[1] === "1";
        }
        try {
            return global.localStorage.getItem(STORAGE_KEY) === "1";
        } catch (e) {
            return false;
        }
    }

    function writeMode(on) {
        try {
            global.localStorage.setItem(STORAGE_KEY, on ? "1" : "0");
        } catch (e) {
            /* 隐私模式/禁写 localStorage：只影响本次会话的跨页保持，不该因此报错 */
        }
    }

    function applyMode(on) {
        document.body.classList.toggle("presenter-on", on);
        var btn = shell.querySelector(".presenter-toggle");
        btn.setAttribute("data-on", on ? "1" : "0");
        btn.textContent = on ? "退出演示" : "演示模式";
        btn.title = on ? "退出演示模式（P）" : "进入演示模式（P）";
    }

    function go(delta) {
        var next = ORDER[(ORDER.indexOf(currentPage) + delta + ORDER.length) % ORDER.length];
        // presenter=1 一起带上：翻页后仍是演示态，即使本机 localStorage 被清过
        global.location.href = PAGES[next].href + "?presenter=1";
    }

    /** 结论卡条目：`[{ label, value, sub, tone }]`。tone: ok / warn / err。 */
    function renderCard(items) {
        // 指标大屏是**不带插槽**挂载的（它的 KPI 行本身就是结论，不另加卡），
        // 所以这里必须容错：没有卡片就什么都不做，别把挂载后的初始化链一起打断
        if (!cardItems) {
            return;
        }
        cardItems.textContent = "";
        if (!items || !items.length) {
            var wait = document.createElement("span");
            wait.className = "presenter-lb";
            wait.textContent = "等待数据…";
            cardItems.appendChild(wait);
            return;
        }
        items.forEach(function (it) {
            var box = document.createElement("div");
            box.className = "presenter-it";
            var lb = document.createElement("div");
            lb.className = "presenter-lb";
            lb.textContent = it.label;
            var v = document.createElement("div");
            v.className = "presenter-v";
            if (it.tone) {
                v.setAttribute("data-tone", it.tone);
            }
            // 用 textContent 而不是 innerHTML：卡片里全是读口来的数字与标签，
            // 走 DOM 就不必在本文件再抄一份转义函数（页面各有一份 esc，别处也没有理由再增加一份）
            v.appendChild(document.createTextNode(String(it.value)));
            if (it.sub) {
                var sub = document.createElement("span");
                sub.className = "presenter-sub";
                sub.textContent = it.sub;
                v.appendChild(sub);
            }
            box.appendChild(lb);
            box.appendChild(v);
            cardItems.appendChild(box);
        });
    }

    function onKey(ev) {
        if (ev.metaKey || ev.ctrlKey || ev.altKey) {
            return;
        }
        var t = ev.target;
        var tag = t && t.tagName ? t.tagName.toLowerCase() : "";
        if (tag === "input" || tag === "textarea" || tag === "select" || (t && t.isContentEditable)) {
            return;
        }
        if (ev.key === "p" || ev.key === "P") {
            var on = !document.body.classList.contains("presenter-on");
            writeMode(on);
            applyMode(on);
            ev.preventDefault();
        } else if (ev.key === "ArrowLeft") {
            go(-1);
            ev.preventDefault();
        } else if (ev.key === "ArrowRight") {
            go(1);
            ev.preventDefault();
        }
    }

    function mount(opts) {
        currentPage = opts.page;
        var theme = opts.theme === "light" ? "light" : "dark";
        var meta = PAGES[currentPage];

        shell = document.createElement("div");
        shell.className = "presenter-shell";
        shell.setAttribute("data-theme", theme);
        shell.innerHTML = '<button class="presenter-nav" type="button" data-nav="-1" title="上一页（←）">‹</button>'
            + '<span class="presenter-page">' + meta.title
            + " <b>" + (ORDER.indexOf(opts.page) + 1) + "/" + ORDER.length + "</b></span>"
            + '<button class="presenter-nav" type="button" data-nav="1" title="下一页（→）">›</button>'
            + '<button class="presenter-toggle" type="button" title="进入演示模式（P）">演示模式</button>';

        shell.querySelector('[data-nav="-1"]').addEventListener("click", function () { go(-1); });
        shell.querySelector('[data-nav="1"]').addEventListener("click", function () { go(1); });
        shell.querySelector(".presenter-toggle").addEventListener("click", function () {
            var on = !document.body.classList.contains("presenter-on");
            writeMode(on);
            applyMode(on);
        });

        // 结论卡插槽：只有需要结论卡的页面才传（指标大屏不传 —— 它的 KPI 行本身就是结论）
        var host = opts.slot ? document.querySelector(opts.slot) : null;
        if (opts.slot && !host) {
            // 到这里说明**页面 HTML 与本文件不是同一版**：挂载点 id 写在 HTML 里、挂载逻辑在本文件里，
            // 而静态资源只带 Last-Modified、没有 Cache-Control，浏览器会启发式缓存 —— 于是两者
            // 各自被缓存成不同版本。旧 JS 找不到新槽位，卡片就**静默消失**，最难查的就是这种。
            // 所以不直接放弃：就地兜一个挂载点继续工作，并把错位这件事说清楚。
            if (window.console && console.warn) {
                console.warn("[presenter] 找不到挂载点 " + opts.slot
                    + " —— 页面 HTML 与 presenter.js 大概不是同一版，硬刷新（Ctrl+F5）即可；已临时兜在页面顶部。");
            }
            host = document.createElement("div");
            var main = document.querySelector("main") || document.body;
            main.insertBefore(host, main.firstChild);
        }
        if (host) {
            var card = document.createElement("section");
            card.className = "presenter-card";
            card.setAttribute("data-theme", theme);
            var k = document.createElement("div");
            k.className = "presenter-card-k";
            k.textContent = "结论 · 数字取自读口 /inner/sw/*，非手写";
            var items = document.createElement("div");
            items.className = "presenter-items";
            card.appendChild(k);
            card.appendChild(items);
            host.appendChild(card);
            cardItems = items;
        }

        document.body.appendChild(shell);
        document.addEventListener("keydown", onKey);
        applyMode(readMode());
        renderCard(null);
    }

    global.SWPresenter = {
        mount: mount,
        conclusion: function (items) {
            if (cardItems) {
                renderCard(items);
            }
        }
    };
}(window));
