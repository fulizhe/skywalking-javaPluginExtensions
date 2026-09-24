package org.apache.skywalking.apm.agent.core.reporter.logfile.metrics;

import java.util.List;

/**
 * 指标落库的最小接口：聚合器只依赖它，便于单测注入假实现（不碰 H2）。
 */
public interface MetricsSink {

    void store(List<MetricsRow> rows);
}
