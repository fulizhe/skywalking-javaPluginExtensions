package org.apache.skywalking.apm.agent.core.reporter.logfile.storage;

import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

/**
 * {@link CappedFileStorageStats} 单元测试。
 *
 * <p>
 * 锁三件事：① 派生值（压缩率 / 单次均值）在分母为 0 时给 <b>0 而不是 NaN/Infinity</b>
 * ——读口要的是能直接渲染的数字；② "读不到"按<b>已被环覆盖</b>与<b>从未写入</b>分开计数；
 * ③ {@link CappedFileStorageStats#snapshot()} 只出 JDK 原生类型（它要跨 PluginClassLoader
 * 交给宿主侧 JSON 序列化，混进本类实例会炸）。
 * </p>
 */
public class CappedFileStorageStatsTest {

    @Test
    public void freshStats_derivationsAreZeroNotNaN() {
        final CappedFileStorageStats s = new CappedFileStorageStats();
        Assert.assertEquals(0, s.getCompressionRatio(), 0d);
        Assert.assertEquals(0d, s.getAverageBytesPerWriteBeforeCompression(), 0d);
        Assert.assertEquals(0d, s.getAverageBytesPerWriteAfterCompression(), 0d);
        Assert.assertEquals(0d, s.getAverageMillisPerWrite(), 0d);
        Assert.assertEquals(0d, s.getAverageMillisPerRead(), 0d);
        Assert.assertEquals(0L, s.getWriteCount());
        Assert.assertEquals(0L, s.getMissReadCount());
    }

    @Test
    public void recordWrite_accumulatesBytesAndDerivesRatioAndAverages() {
        final CappedFileStorageStats s = new CappedFileStorageStats();
        s.recordWrite(1000L, 250L, 2_000_000L); // 2ms
        s.recordWrite(500L, 100L, 4_000_000L); // 4ms

        Assert.assertEquals(2L, s.getWriteCount());
        // 压缩率 = (1500 - 350) / 1500
        Assert.assertEquals(1150d / 1500d, s.getCompressionRatio(), 1e-9);
        Assert.assertEquals(750d, s.getAverageBytesPerWriteBeforeCompression(), 1e-9);
        Assert.assertEquals(175d, s.getAverageBytesPerWriteAfterCompression(), 1e-9);
        Assert.assertEquals(3d, s.getAverageMillisPerWrite(), 1e-9);
        Assert.assertEquals(6d, s.getTotalWriteMillis(), 1e-9);
    }

    @Test
    public void recordRead_derivesAverageOnlyWhenReadsHappened() {
        final CappedFileStorageStats s = new CappedFileStorageStats();
        s.recordRead(1_000_000L);
        s.recordRead(3_000_000L);
        Assert.assertEquals(2L, s.getReadCount());
        Assert.assertEquals(2d, s.getAverageMillisPerRead(), 1e-9);
        Assert.assertEquals(4d, s.getTotalReadMillis(), 1e-9);
        // 没有写入时写侧均值仍必须是 0（不能被读侧数据带偏）
        Assert.assertEquals(0d, s.getAverageMillisPerWrite(), 0d);
    }

    @Test
    public void recordMiss_splitsExpiredFromNeverWritten() {
        final CappedFileStorageStats s = new CappedFileStorageStats();
        s.recordMiss(true); // 已被环覆盖
        s.recordMiss(true);
        s.recordMiss(false); // 从未写入 / 长度荒谬

        Assert.assertEquals("两种原因合计", 3L, s.getMissReadCount());
        Assert.assertEquals("只有 2 次是环覆盖", 2L, s.getExpiredReadCount());
    }

    @Test
    public void healthCounters_accumulateIndependently() {
        final CappedFileStorageStats s = new CappedFileStorageStats();
        s.recordFsync();
        s.recordFsync();
        s.recordFsync();
        s.recordOversizedRejected();
        s.recordIoError();

        Assert.assertEquals(3L, s.getFsyncCount());
        Assert.assertEquals(1L, s.getOversizedRejectedCount());
        Assert.assertEquals(1L, s.getIoErrorCount());
    }

    @Test
    public void snapshot_containsOnlyJdkNativeValues() {
        final CappedFileStorageStats s = new CappedFileStorageStats();
        s.recordWrite(1000L, 250L, 2_000_000L);
        s.recordRead(1_000_000L);
        s.recordMiss(true);

        final Map<String, Object> m = s.snapshot();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            final Object v = e.getValue();
            Assert.assertTrue("值必须是 JDK 原生类型（不得外泄自定义类），实际: " + v.getClass(),
                    v instanceof Number || v instanceof String || v instanceof Boolean);
        }
        Assert.assertEquals(Long.valueOf(1L), m.get("writeCount"));
        Assert.assertEquals(Long.valueOf(250L), m.get("totalBytesAfterCompression"));
        Assert.assertEquals(Long.valueOf(1L), m.get("expiredReadCount"));
    }
}
