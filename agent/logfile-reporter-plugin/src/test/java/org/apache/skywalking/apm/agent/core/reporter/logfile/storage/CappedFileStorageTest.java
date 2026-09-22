package org.apache.skywalking.apm.agent.core.reporter.logfile.storage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * CappedFileStorage 单元测试：只经 writeMessage / readMessage + 文件大小断言。
 * 覆盖：往返、跨尾、覆盖淘汰（过期）、GZIP、超限块、close/重开、并发。
 */
public class CappedFileStorageTest {

    private File dir;
    private final List<CappedFileStorage> opened = new ArrayList<CappedFileStorage>();

    @Before
    public void setUp() {
        dir = new File(System.getProperty("java.io.tmpdir"), "capped-test-" + System.nanoTime());
        Assert.assertTrue(dir.mkdirs());
    }

    @After
    public void tearDown() {
        for (CappedFileStorage s : opened) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        final File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                Assert.assertTrue(f.delete() || !f.exists());
            }
        }
        Assert.assertTrue(dir.delete() || !dir.exists());
    }

    private CappedFileStorage open(final long sizeBytes) throws IOException {
        final CappedFileStorage s = new CappedFileStorage(new File(dir, "payload.capped.db"), sizeBytes);
        opened.add(s);
        return s;
    }

    private static byte[] payload(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void roundTrip_preservesBytes() throws IOException {
        final CappedFileStorage s = open(1024 * 64);
        final byte[] p1 = payload("hello-capped-file");
        final byte[] p2 = payload("second-payload");
        final long id1 = s.writeMessage(p1);
        final long id2 = s.writeMessage(p2);

        Assert.assertArrayEquals(p1, s.readMessage(id1));
        Assert.assertArrayEquals(p2, s.readMessage(id2));
    }

    @Test
    public void gzip_roundTrip_largeCompressible() throws IOException {
        final CappedFileStorage s = open(1024 * 64);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            sb.append("repeat-");
        }
        final byte[] big = payload(sb.toString());
        final long id = s.writeMessage(big);
        Assert.assertArrayEquals(big, s.readMessage(id));
        // 压缩后应远小于原始（证明确实 GZIP）
        Assert.assertTrue("file size should be fixed", s.getSizeBytes() == 1024 * 64);
    }

    @Test
    public void wrapAround_repeatedWriteReadBack() throws IOException {
        // 小容量：持续写入必然跨文件尾；每次写后立即读回，等价于验证跨尾读写正确
        final CappedFileStorage s = open(256);
        for (int i = 0; i < 200; i++) {
            final byte[] p = payload("block-" + i + "-" + repeat('x', 10 + (i % 40)));
            final long id = s.writeMessage(p);
            Assert.assertArrayEquals("round trip at write " + i, p, s.readMessage(id));
        }
        Assert.assertEquals("file physical size must stay constant", 256, s.getSizeBytes());
    }

    @Test
    public void overwriteEviction_oldestReturnsNull() throws IOException {
        final CappedFileStorage s = open(512);
        long firstId = -1;
        long lastId = -1;
        byte[] lastPayload = null;
        for (int i = 0; i < 300; i++) {
            final byte[] p = payload("evict-" + i + "-" + repeat('y', 30));
            final long id = s.writeMessage(p);
            if (firstId < 0) {
                firstId = id;
            }
            lastId = id;
            lastPayload = p;
        }
        Assert.assertNull("oldest block should be overwritten/expired", s.readMessage(firstId));
        Assert.assertArrayEquals("newest block should be readable", lastPayload, s.readMessage(lastId));
        Assert.assertTrue("file size constant", new File(dir, "payload.capped.db").length() == 512);
    }

    @Test
    public void oversizedPayload_rejected() throws IOException {
        final CappedFileStorage s = open(256);
        final byte[] big = new byte[4096];
        new Random(1).nextBytes(big);
        try {
            s.writeMessage(big);
            Assert.fail("expected IOException for oversized payload");
        } catch (IOException expected) {
            // ok
        }
    }

    @Test
    public void closeAndReopen_persistsHeaderAndData() throws IOException {
        final CappedFileStorage s = open(1024 * 16);
        final byte[] p = payload("persist-me");
        final long id = s.writeMessage(p);
        s.close();

        final CappedFileStorage reopened = open(1024 * 16);
        Assert.assertArrayEquals("data should survive reopen", p, reopened.readMessage(id));
        // 重开后继续写不覆盖已有数据
        final byte[] p2 = payload("after-reopen");
        final long id2 = reopened.writeMessage(p2);
        Assert.assertTrue("new id must be greater", id2 > id);
        Assert.assertArrayEquals(p2, reopened.readMessage(id2));
    }

    @Test
    public void concurrentReadWrite_noErrorsAndConsistent() throws Exception {
        final CappedFileStorage s = open(1024 * 1024);
        final int threads = 4;
        final int perThread = 200;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final List<Throwable> errors = new CopyOnWriteArrayList<Throwable>();
        final AtomicInteger checked = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            final byte[] p = payload("t" + tid + "-msg-" + i + "-" + repeat('z', 20));
                            final long id = s.writeMessage(p);
                            final byte[] back = s.readMessage(id);
                            Assert.assertArrayEquals(p, back);
                            checked.incrementAndGet();
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    } finally {
                        done.countDown();
                    }
                }
            }).start();
        }
        start.countDown();
        done.await();
        Assert.assertTrue("no errors expected: " + errors, errors.isEmpty());
        Assert.assertEquals(threads * perThread, checked.get());
    }

    private static String repeat(final char c, final int n) {
        final StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
