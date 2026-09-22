package org.apache.skywalking.apm.agent.core.reporter.logfile.storage;

import java.util.List;
import java.util.Map;

import org.apache.skywalking.apm.agent.core.context.trace.TraceSegment;

/**
 * Trace 持久层存储接口（Agent 内部，不越 Business 边界）。
 * <p>
 * Phase 1：H2 内存模式影子写入，与旧 {@code KeyedLocalStore} 路径并行双跑，
 * 旧路径与一切对外输出零变化。
 * </p>
 * <p>
 * {@code accept} 返回 {@code void}（告警下沉留后期设计）；
 * 比对与未来读门面都走只读的 {@link #snapshot()} / {@link #size()}。
 * </p>
 */
public interface TraceSegmentStorage {

    /**
     * 写入：与 {@code consume} 入参一致，接收 agent 采集到的原始 segment。
     * 实现内部异常全部捕获（计数 + 限速日志），绝不外抛。
     */
    void accept(List<TraceSegment> segments);

    /**
     * 读取：已组装 trace 快照，与旧 {@code data[traceId].logs} 视图同构，
     * 供交叉比对。
     */
    Map<String, Map<String, Object>> snapshot();

    /**
     * 当前存储的 distinct trace 数量。
     */
    int size();
}
