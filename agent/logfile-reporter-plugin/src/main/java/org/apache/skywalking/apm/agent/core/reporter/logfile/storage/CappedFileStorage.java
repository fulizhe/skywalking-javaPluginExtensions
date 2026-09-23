package org.apache.skywalking.apm.agent.core.reporter.logfile.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 环形封顶载荷文件（最小形态 + GZIP）。
 *
 * <p>
 * 单文件、<b>固定大小</b>；块格式 {@code <len:8B><gzip(payload)>}；块可跨文件尾分段写/读；
 * 写满后覆盖最旧块；被覆盖的逻辑 id 读回 {@code null}（过期）。读写共用一把锁。
 * </p>
 *
 * <p>
 * 设计来源（本类为其<b>最小形态移植</b>，去掉 resize / isInTheFuture / 压缩率统计，载荷改为每块独立 GZIP）：
 * <a href="https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/agent/embedded/src/main/java/org/glowroot/agent/embedded/util/CappedDatabase.java">Glowroot CappedDatabase.java</a>
 * </p>
 *
 * <p>
 * 文件布局：文件头 16B = {@code currIndex(long 8B)} + {@code sizeBytes(long 8B)}；
 * 数据区 = {@code [16, sizeBytes)}；逻辑 index 到物理位置的映射为 {@code 16 + (index % dataLen)}。
 * </p>
 */
class CappedFileStorage implements Closeable {

    /** 文件头：currIndex + sizeBytes */
    private static final int HEADER_SKIP_BYTES = 16;
    /** 块头：payload 长度 */
    private static final int BLOCK_HEADER_BYTES = 8;

    /** 每写满 N 次触发一次 fsync */
    private static final long FSYNC_EVERY_WRITES = 100L;
    /** 距上次 fsync 超过 T 毫秒也触发一次 */
    private static final long FSYNC_INTERVAL_MS = 1000L;

    private final File file;
    private final long sizeBytes;
    private final long dataLen;
    private final Object lock = new Object();

    private RandomAccessFile raf;
    /** 只增不减的逻辑写游标（跨多轮覆盖也不回绕） */
    private long currIndex;
    private boolean dirty;
    private long writesSinceFsync;
    private long lastFsyncTime;

    public CappedFileStorage(final File file, final long sizeBytes) throws IOException {
        this.file = file;
        this.sizeBytes = Math.max(sizeBytes, HEADER_SKIP_BYTES + BLOCK_HEADER_BYTES + 1L);
        this.dataLen = this.sizeBytes - HEADER_SKIP_BYTES;
        openAndInit();
    }

    private void openAndInit() throws IOException {
        final boolean existing = file.exists() && file.length() == sizeBytes;
        raf = new RandomAccessFile(file, "rw");
        if (existing) {
            raf.seek(0);
            currIndex = raf.readLong();
            raf.readLong(); // sizeBytes 字段；以文件长度为准，读后忽略
        } else {
            raf.setLength(sizeBytes);
            currIndex = 0;
            writeHeader();
        }
        this.lastFsyncTime = System.currentTimeMillis();
    }

    private void writeHeader() throws IOException {
        raf.seek(0);
        raf.writeLong(currIndex);
        raf.writeLong(sizeBytes);
    }

    /** 逻辑 index → 物理位置（数据区内） */
    private long pos(final long index) {
        long m = index % dataLen;
        if (m < 0) {
            m += dataLen;
        }
        return HEADER_SKIP_BYTES + m;
    }

    /**
     * 写入一个载荷块（内部 GZIP 压缩），返回块起始逻辑 id。
     *
     * @throws IOException 载荷压缩后仍超过文件可容纳的单块大小时
     */
    public long writeMessage(final byte[] payload) throws IOException {
        if (payload == null) {
            return -1L;
        }
        final byte[] compressed = gzip(payload);
        synchronized (lock) {
            if ((long) compressed.length + BLOCK_HEADER_BYTES > dataLen) {
                throw new IOException("payload too large for capped file: compressed=" + compressed.length
                        + ", max=" + (dataLen - BLOCK_HEADER_BYTES));
            }
            final long blockStart = currIndex;
            writeAt(blockStart, longToBytes(compressed.length));
            writeAt(blockStart + BLOCK_HEADER_BYTES, compressed);
            currIndex = blockStart + BLOCK_HEADER_BYTES + compressed.length;
            writeHeader();
            dirty = true;
            maybeFsync();
            return blockStart;
        }
    }

    /**
     * 按逻辑 id 读回块（内部 GZIP 解压）。
     *
     * @return 原始载荷；id 尚未写入或被覆盖（过期）时返回 {@code null}
     */
    public byte[] readMessage(final long id) throws IOException {
        synchronized (lock) {
            if (id < 0 || id >= currIndex) {
                return null;
            }
            final long smallestNonOverwrittenId = Math.max(0L, currIndex - dataLen);
            if (id < smallestNonOverwrittenId) {
                return null; // 已被覆盖
            }
            final byte[] lenBytes = readAt(id, BLOCK_HEADER_BYTES);
            if (lenBytes == null) {
                return null;
            }
            final long len = bytesToLong(lenBytes);
            if (len <= 0 || len + BLOCK_HEADER_BYTES > dataLen) {
                return null;
            }
            final byte[] compressed = readAt(id + BLOCK_HEADER_BYTES, (int) len);
            if (compressed == null) {
                return null;
            }
            return gunzip(compressed);
        }
    }

    /** 当前写游标（只增）。 */
    public long getCurrIndex() {
        synchronized (lock) {
            return currIndex;
        }
    }

    /** 指定逻辑 id 是否已被覆盖（过期）。 */
    public boolean isOverwritten(final long id) {
        synchronized (lock) {
            if (id < 0 || id >= currIndex) {
                return false;
            }
            return id < Math.max(0L, currIndex - dataLen);
        }
    }

    /** 固定文件大小。 */
    public long getSizeBytes() {
        return sizeBytes;
    }

    // ============================ low-level wrap-aware IO

    private void writeAt(final long index, final byte[] bytes) throws IOException {
        long p = pos(index);
        int offset = 0;
        int remaining = bytes.length;
        while (remaining > 0) {
            final int chunk = (int) Math.min((long) remaining, sizeBytes - p);
            raf.seek(p);
            raf.write(bytes, offset, chunk);
            offset += chunk;
            remaining -= chunk;
            p = HEADER_SKIP_BYTES; // 回绕到数据区起点
        }
    }

    private byte[] readAt(final long index, final int len) throws IOException {
        final byte[] out = new byte[len];
        long p = pos(index);
        int offset = 0;
        int remaining = len;
        while (remaining > 0) {
            final int chunk = (int) Math.min((long) remaining, sizeBytes - p);
            raf.seek(p);
            raf.readFully(out, offset, chunk);
            offset += chunk;
            remaining -= chunk;
            p = HEADER_SKIP_BYTES;
        }
        return out;
    }

    private void maybeFsync() throws IOException {
        writesSinceFsync++;
        final long now = System.currentTimeMillis();
        if (writesSinceFsync >= FSYNC_EVERY_WRITES || now - lastFsyncTime >= FSYNC_INTERVAL_MS) {
            force();
        }
    }

    private void force() throws IOException {
        if (dirty) {
            raf.getChannel().force(false);
            dirty = false;
        }
        writesSinceFsync = 0;
        lastFsyncTime = System.currentTimeMillis();
    }

    @Override
    public void close() throws IOException {
        synchronized (lock) {
            if (raf != null) {
                force();
                raf.close();
                raf = null;
            }
        }
    }

    // ============================ helpers

    private static byte[] longToBytes(final long v) {
        final byte[] b = new byte[8];
        b[0] = (byte) (v >>> 56);
        b[1] = (byte) (v >>> 48);
        b[2] = (byte) (v >>> 40);
        b[3] = (byte) (v >>> 32);
        b[4] = (byte) (v >>> 24);
        b[5] = (byte) (v >>> 16);
        b[6] = (byte) (v >>> 8);
        b[7] = (byte) v;
        return b;
    }

    private static long bytesToLong(final byte[] b) {
        long v = 0L;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[i] & 0xFFL);
        }
        return v;
    }

    private static byte[] gzip(final byte[] data) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(32, data.length / 2));
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(data);
        }
        return bos.toByteArray();
    }

    private static byte[] gunzip(final byte[] data) throws IOException {
        final ByteArrayInputStream bis = new ByteArrayInputStream(data);
        try (GZIPInputStream gz = new GZIPInputStream(bis)) {
            final ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(32, data.length * 2));
            final byte[] buf = new byte[4096];
            int r;
            while ((r = gz.read(buf)) != -1) {
                bos.write(buf, 0, r);
            }
            return bos.toByteArray();
        }
    }
}
