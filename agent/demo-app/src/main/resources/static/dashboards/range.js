/*
 * 时间窗 → 分钟桶范围（**口径单点源**）。
 *
 * 为什么单独抽一个文件：指标大屏与慢调用榜都要按同一套时间窗去查读口，而"查哪些分钟桶"
 * 就是这两个页数字能不能对上的前提。同一个公式抄两份，迟早会漂 —— 而漂了没人查得出来，
 * 只会变成"两张页同一时间窗数字不一样"这种查不清的问题。
 *
 * 引入本文件即可用（无依赖、无样式）：
 *   SWRanges.RANGES            档位 → 分钟数
 *   SWRanges.LABELS            档位 → 中文标签
 *   SWRanges.buckets(range)    → { from, to, res, range }（res = minute | hour）
 *   SWRanges.hours(range)      → 该档位的小时数（讲口径时常用）
 *
 * 公式与原 metrics.html 内联实现逐字一致：to = 当前分钟桶；from = to - (分钟数 - 1)；
 * 跨度 ≤ 1440 分钟走 minute 分辨率，否则走 hour。
 */
(function (global) {
    "use strict";

    var MINUTE_MS = 60000;

    /** 档位 → 分钟数。注意 24h = 1440 分钟正好卡在 minute/hour 的分界上。 */
    var RANGES = { "1h": 60, "6h": 360, "24h": 1440, "7d": 10080, "30d": 43200 };

    var LABELS = { "1h": "近 1h", "6h": "近 6h", "24h": "近 24h", "7d": "近 7d", "30d": "近 30d" };

    var DEFAULT_RANGE = "24h";

    /**
     * @param range 档位键（1h/6h/24h/7d/30d）；不认识或缺省 → 24h
     * @param nowMs 可注入的"现在"（测试用；生产不传取 Date.now）
     */
    function buckets(range, nowMs) {
        var r = RANGES[range] ? range : DEFAULT_RANGE;
        var to = Math.floor((nowMs == null ? Date.now() : nowMs) / MINUTE_MS);
        var from = to - (RANGES[r] - 1);
        // 跨度 ≤ 1440 分钟用分钟分位，再大就得走小时 rollup（否则点数超上限）
        return { from: from, to: to, res: (to - from) <= 1440 ? "minute" : "hour", range: r };
    }

    function hours(range) {
        return RANGES[range] ? RANGES[range] / 60 : RANGES[DEFAULT_RANGE] / 60;
    }

    global.SWRanges = {
        MINUTE_MS: MINUTE_MS,
        RANGES: RANGES,
        LABELS: LABELS,
        DEFAULT_RANGE: DEFAULT_RANGE,
        buckets: buckets,
        hours: hours,
        label: function (range) { return LABELS[range] || LABELS[DEFAULT_RANGE]; }
    };
}(typeof window !== "undefined" ? window : this));
