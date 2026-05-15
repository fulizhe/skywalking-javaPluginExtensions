/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.skywalking.apm.toolkit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class CircularBlockingQueueTest {

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    /** 容量为 0 时应拒绝创建并抛出 IllegalArgumentException。 */
    @Test
    public void testConstructorRejectsNonPositiveCapacity() {
        thrown.expect(IllegalArgumentException.class);
        thrown.expectMessage("Capacity must be positive");
        new CircularBlockingQueue<>(0);
    }

    /** 容量为负数时应拒绝创建并抛出 IllegalArgumentException。 */
    @Test
    public void testConstructorRejectsNegativeCapacity() {
        thrown.expect(IllegalArgumentException.class);
        thrown.expectMessage("Capacity must be positive");
        new CircularBlockingQueue<>(-1);
    }

    /** 未满时 offer/add/put 均应成功，且 size 与 remainingCapacity 状态正确。 */
    @Test
    public void testInsertMethodsAlwaysSucceedWhenNotFull() throws Exception {
        CircularBlockingQueue<String> queue = new CircularBlockingQueue<>(3);

        assertTrue(queue.offer("a"));
        assertTrue(queue.add("b"));
        queue.put("c");

        assertEquals(3, queue.size());
        assertEquals(0, queue.remainingCapacity());
        assertFalse(queue.isEmpty());
    }

    /** 已满时带超时的 offer 仍返回 true，并触发队首淘汰。 */
    @Test
    public void testOfferWithTimeoutAlwaysReturnsTrue() {
        CircularBlockingQueue<String> queue = new CircularBlockingQueue<>(2);
        queue.add("a");
        queue.add("b");

        assertTrue(queue.offer("c", 1, TimeUnit.MILLISECONDS));
        assertEquals(2, queue.size());
        assertEquals("b", queue.peek());
    }

    /** 未满时元素按 FIFO 顺序通过 poll/take/remove 出队。 */
    @Test
    public void testFifoOrderWhenNotFull() throws Exception {
        CircularBlockingQueue<Integer> queue = new CircularBlockingQueue<>(5);
        queue.add(1);
        queue.add(2);
        queue.add(3);

        assertEquals(Integer.valueOf(1), queue.poll());
        assertEquals(Integer.valueOf(2), queue.take());
        assertEquals(Integer.valueOf(3), queue.remove());
    }

    /** 队列满后再插入应淘汰最早元素，保留最新三个。 */
    @Test
    public void testEvictsOldestElementWhenFull() {
        CircularBlockingQueue<String> queue = new CircularBlockingQueue<>(3);
        queue.add("first");
        queue.add("second");
        queue.add("third");

        queue.add("fourth");

        assertEquals(3, queue.size());
        assertEquals("second", queue.poll());
        assertEquals("third", queue.poll());
        assertEquals("fourth", queue.poll());
        assertTrue(queue.isEmpty());
    }

    /** 连续超容量插入时，最终只保留最后 capacity 个元素。 */
    @Test
    public void testRepeatedEvictionKeepsLatestElements() {
        CircularBlockingQueue<Integer> queue = new CircularBlockingQueue<>(2);
        for (int i = 1; i <= 5; i++) {
            queue.add(i);
        }

        assertEquals(2, queue.size());
        assertEquals(Integer.valueOf(4), queue.poll());
        assertEquals(Integer.valueOf(5), queue.poll());
    }

    /** 插入 null 元素应抛出 NullPointerException。 */
    @Test
    public void testNullElementRejectedOnInsert() {
        CircularBlockingQueue<String> queue = new CircularBlockingQueue<>(2);

        thrown.expect(NullPointerException.class);
        queue.add(null);
    }

    /** addAll(null) 应抛出 NullPointerException。 */
    @Test
    public void testNullCollectionRejectedOnAddAll() {
        CircularBlockingQueue<String> queue = new CircularBlockingQueue<>(2);

        thrown.expect(NullPointerException.class);
        queue.addAll(null);
    }

    /** 空队列 peek 返回 null；有元素时 peek/element 均返回队首且不改 size。 */
    @Test
    public void testPeekAndElement() {
        CircularBlockingQueue<String> queue = new CircularBlockingQueue<>(2);
        assertNull(queue.peek());

        queue.add("head");
        assertEquals("head", queue.peek());
        assertEquals("head", queue.element());
        assertEquals(1, queue.size());
    }

    /** 空队列在超时内 poll 应返回 null。 */
    @Test
    public void testPollWithTimeoutReturnsNullWhenEmpty() throws Exception {
        CircularBlockingQueue<String> queue = new CircularBlockingQueue<>(2);
        assertNull(queue.poll(10, TimeUnit.MILLISECONDS));
    }

    /** 有元素时 poll(timeout) 应在超时前返回队首元素。 */
    @Test
    public void testPollWithTimeoutReturnsElementWhenAvailable() throws Exception {
        CircularBlockingQueue<String> queue = new CircularBlockingQueue<>(2);
        queue.add("value");

        assertEquals("value", queue.poll(100, TimeUnit.MILLISECONDS));
    }

    /** contains 能正确判断元素是否存在；remove(Object) 可移除指定元素。 */
    @Test
    public void testContainsAndRemoveObject() {
        CircularBlockingQueue<String> queue = new CircularBlockingQueue<>(3);
        queue.add("a");
        queue.add("b");

        assertTrue(queue.contains("a"));
        assertFalse(queue.contains("c"));
        assertTrue(queue.remove("a"));
        assertFalse(queue.contains("a"));
        assertEquals(1, queue.size());
    }

    /** addAll 对每个元素独立应用环形淘汰逻辑。 */
    @Test
    public void testAddAllAppliesEvictionPerElement() {
        CircularBlockingQueue<String> queue = new CircularBlockingQueue<>(3);
        queue.add("old");

        boolean modified = queue.addAll(Arrays.asList("n1", "n2", "n3", "n4"));
        assertTrue(modified);
        assertEquals(3, queue.size());
        assertEquals("n2", queue.poll());
        assertEquals("n3", queue.poll());
        assertEquals("n4", queue.poll());
    }

    /** 对空集合 addAll 应返回 false 且不改变队列。 */
    @Test
    public void testAddAllOnEmptyCollectionReturnsFalse() {
        CircularBlockingQueue<String> queue = new CircularBlockingQueue<>(3);
        assertFalse(queue.addAll(new ArrayList<String>()));
    }

    /** clear 后队列为空且 remainingCapacity 恢复为满容量。 */
    @Test
    public void testClear() {
        CircularBlockingQueue<String> queue = new CircularBlockingQueue<>(3);
        queue.add("a");
        queue.add("b");
        queue.clear();

        assertTrue(queue.isEmpty());
        assertEquals(3, queue.remainingCapacity());
    }

    /** drainTo 将全部元素转移到目标集合并清空队列。 */
    @Test
    public void testDrainTo() {
        CircularBlockingQueue<String> queue = new CircularBlockingQueue<>(3);
        queue.add("a");
        queue.add("b");

        List<String> target = new ArrayList<String>();
        assertEquals(2, queue.drainTo(target));
        assertEquals(Arrays.asList("a", "b"), target);
        assertTrue(queue.isEmpty());
    }

    /** drainTo(c, max) 最多转移 max 个元素，其余保留在队列中。 */
    @Test
    public void testDrainToWithMaxElements() {
        CircularBlockingQueue<String> queue = new CircularBlockingQueue<>(3);
        queue.add("a");
        queue.add("b");
        queue.add("c");

        List<String> target = new ArrayList<String>();
        assertEquals(2, queue.drainTo(target, 2));
        assertEquals(Arrays.asList("a", "b"), target);
        assertEquals(1, queue.size());
        assertEquals("c", queue.poll());
    }

    /** toArray 及 toArray(T[]) 均按队列顺序返回元素快照。 */
    @Test
    public void testToArray() {
        CircularBlockingQueue<String> queue = new CircularBlockingQueue<>(3);
        queue.add("x");
        queue.add("y");

        assertArrayEquals(new String[] {"x", "y"}, queue.toArray());
        assertArrayEquals(new String[] {"x", "y"}, queue.toArray(new String[0]));
    }

    /** iterator 按 FIFO 顺序遍历当前队列中的元素。 */
    @Test
    public void testIteratorSnapshot() {
        CircularBlockingQueue<String> queue = new CircularBlockingQueue<>(3);
        queue.add("a");
        queue.add("b");

        Iterator<String> iterator = queue.iterator();
        List<String> seen = new ArrayList<String>();
        while (iterator.hasNext()) {
            seen.add(iterator.next());
        }
        assertEquals(Arrays.asList("a", "b"), seen);
    }

    /** removeAll 移除匹配元素；retainAll 无变更时返回 false。 */
    @Test
    public void testRemoveAllAndRetainAll() {
        CircularBlockingQueue<String> queue = new CircularBlockingQueue<>(4);
        queue.add("a");
        queue.add("b");
        queue.add("c");

        assertTrue(queue.removeAll(Arrays.asList("a", "c")));
        assertEquals(1, queue.size());
        assertEquals("b", queue.peek());

        assertFalse(queue.retainAll(Arrays.asList("b", "x")));
        assertEquals(1, queue.size());
        assertEquals("b", queue.peek());
    }

    /** containsAll 判断队列是否包含给定集合的全部元素。 */
    @Test
    public void testContainsAll() {
        CircularBlockingQueue<String> queue = new CircularBlockingQueue<>(3);
        queue.add("a");
        queue.add("b");

        assertTrue(queue.containsAll(Arrays.asList("a", "b")));
        assertFalse(queue.containsAll(Arrays.asList("a", "c")));
    }

    /** 并发插入与取出时，队列大小不超过容量，生产结束后可被消费至空。 */
    @Test
    public void testConcurrentInsertAndTake() throws Exception {
        final int capacity = 4;
        final CircularBlockingQueue<Integer> queue = new CircularBlockingQueue<>(capacity);
        final int itemsToProduce = 500;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch producerDone = new CountDownLatch(1);
        final AtomicInteger maxObservedSize = new AtomicInteger();

        Thread producer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    startLatch.await();
                    for (int i = 0; i < itemsToProduce; i++) {
                        queue.add(i);
                        int currentSize = queue.size();
                        int observedMax;
                        do {
                            observedMax = maxObservedSize.get();
                            if (currentSize <= observedMax) {
                                break;
                            }
                        } while (!maxObservedSize.compareAndSet(observedMax, currentSize));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    producerDone.countDown();
                }
            }
        });

        Thread consumer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    startLatch.await();
                    while (!producerDone.await(10, TimeUnit.MILLISECONDS) || !queue.isEmpty()) {
                        queue.poll(10, TimeUnit.MILLISECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        producer.start();
        consumer.start();
        startLatch.countDown();

        producer.join(10_000);
        consumer.join(10_000);
        assertFalse("producer should finish in time", producer.isAlive());
        assertFalse("consumer should finish in time", consumer.isAlive());
        assertTrue(queue.isEmpty());
        assertTrue(maxObservedSize.get() <= capacity);
        assertTrue(maxObservedSize.get() > 0);
    }
}
