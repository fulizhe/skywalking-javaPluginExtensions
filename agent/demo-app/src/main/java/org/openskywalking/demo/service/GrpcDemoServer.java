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

package org.openskywalking.demo.service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import javax.annotation.PreDestroy;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCallHandler;
import io.grpc.ServerServiceDefinition;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 依赖拓扑的 **RPC 样例**：进程内起一个最小 gRPC server，客户端从同进程的另一个通道调它。
 *
 * <h3>为什么需要它</h3>
 * <p>
 * 依赖拓扑此前只有 Database / Cache / MQ / Http 四类依赖，缺 RPC —— 因为 demo-app 的 classpath
 * 里**没有任何 RPC 客户端依赖**，agent 的 {@code apm-grpc-1.x} 插件无从挂载，图上永远出不来
 * gRPC 节点。本类把这条缺口补上，且**不引任何外部中间件**（不起 compose 服务、不占镜像体积）。
 *
 * <h3>为什么手工造 MethodDescriptor 而不写 .proto</h3>
 * <p>
 * 一个 echo 方法不值得引入 protoc、protobuf 生成代码与 .proto 维护成本。
 * {@link IdentityMarshaller} 直接收发 {@code byte[]}，依赖只有 {@code grpc-netty-shaded} + {@code grpc-stub}。
 *
 * <h3>为什么暴露三个方法、且各自耗时不同</h3>
 * <p>
 * 「RPC 调用排行」页按<b>方法</b>排行。单个方法只有一行、看不出名次竞争，故在同一个 service 下挂三个
 * 语义相仿但**耗时档位不同**的方法：{@code Echo}（快）/ {@code BatchSync}（中）/ {@code StreamData}（慢）。
 * 服务端回显前按各方法自己的区间随机睡一会儿（见 {@link #simulateLatency(int[])}），
 * 让客户端出口 span 的耗时有分布 —— 排行页的分位梯与条形才有形状。可用
 * {@code deps.grpc.simulateLatency=false} 关掉（退化为即时回显）。
 *
 * <h3>为什么用真实端口而不是 in-process transport</h3>
 * <p>
 * agent 的插件既挂在 {@code ClientCalls}（调用入口）也挂在 Netty stream（真实网络流）。
 * in-process transport 不走 Netty，只会被前者命中；用真实端口则两条路径都覆盖，
 * 语义上也更接近"真的调了一个 RPC 服务"。
 *
 * <p>端口默认 9099，可用 {@code -DDEP.grpc.port} / 环境变量 {@code DEPS_GRPC_PORT} 改；
 * server 起不来（端口被占等）**只记日志不抛**，避免把整个演示应用拖挂 —— 那时 RPC 样例退化为红边。
 */
@Component
public class GrpcDemoServer {

    private static final Logger log = LoggerFactory.getLogger(GrpcDemoServer.class);

    private static final String SERVICE_NAME = "demo.DepsDemo";

    /** 客户端调用的硬上限：超时就当这次 RPC 失败（超时会不会置 isError 见页面 caveats 的第三条）。 */
    private static final long CLIENT_DEADLINE_MS = 2000L;

    // 三个方法的服务端模拟处理耗时区间（毫秒，闭区间内均匀随机）：档位拉开，排行页才看得出名次。
    // 上限（500ms）刻意远低于客户端 2s deadline。
    private static final int[] ECHO_DELAY_MS = { 5, 40 };      // 快
    private static final int[] BATCH_DELAY_MS = { 50, 200 };   // 中
    private static final int[] STREAM_DELAY_MS = { 150, 500 }; // 慢

    private static final MethodDescriptor<byte[], byte[]> ECHO_METHOD = method("Echo");
    private static final MethodDescriptor<byte[], byte[]> BATCH_METHOD = method("BatchSync");
    private static final MethodDescriptor<byte[], byte[]> STREAM_METHOD = method("StreamData");

    @Value("${deps.grpc.port:9099}")
    private int port;

    /** 是否给各方法加模拟处理耗时（动态分布）——演示「RPC 调用排行」页用；false 时退化为即时回显。 */
    @Value("${deps.grpc.simulateLatency:true}")
    private boolean simulateLatency;

    private Server server;
    private volatile ManagedChannel channel;

    private static MethodDescriptor<byte[], byte[]> method(final String name) {
        return MethodDescriptor.<byte[], byte[]>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, name))
                .setRequestMarshaller(IdentityMarshaller.INSTANCE)
                .setResponseMarshaller(IdentityMarshaller.INSTANCE)
                .build();
    }

    /** gRPC server 懒启动（构造时端口配置未必已注入，且不想拖慢应用启动）。 */
    private synchronized void ensureServer() {
        if (server != null) {
            return;
        }
        final ServerServiceDefinition service = ServerServiceDefinition.builder(SERVICE_NAME)
                .addMethod(ECHO_METHOD, unaryEcho(ECHO_DELAY_MS))
                .addMethod(BATCH_METHOD, unaryEcho(BATCH_DELAY_MS))
                .addMethod(STREAM_METHOD, unaryEcho(STREAM_DELAY_MS))
                .build();
        try {
            server = NettyServerBuilder.forPort(port).addService(service).build().start();
            log.info("### [deps-demo] gRPC server up on 127.0.0.1:{} (service {}: Echo/BatchSync/StreamData)",
                    port, SERVICE_NAME);
        } catch (Exception e) {
            log.warn("### [deps-demo] gRPC server 未能启动 on port {}: {} —— RPC 样例会表现为失败边",
                    port, e.toString());
        }
    }

    private synchronized ManagedChannel channel() {
        if (channel == null || channel.isShutdown()) {
            channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
        }
        return channel;
    }

    /** 统一 unary handler：回显前按该方法的区间随机睡一会儿（服务端处理耗时，会被客户端 span 覆盖）。 */
    private ServerCallHandler<byte[], byte[]> unaryEcho(final int[] delayRangeMs) {
        return ServerCalls.asyncUnaryCall((request, observer) -> {
            simulateLatency(delayRangeMs);
            observer.onNext(request);
            observer.onCompleted();
        });
    }

    /** 快方法：调一次 echo。异常一律抛给调用方吞掉（由 {@code DepsDemoService} 统一处理）。 */
    public byte[] echo(final byte[] payload) throws Exception {
        return call(ECHO_METHOD, payload);
    }

    /** 中方法：调一次 batchSync。 */
    public byte[] batchSync(final byte[] payload) throws Exception {
        return call(BATCH_METHOD, payload);
    }

    /** 慢方法：调一次 streamData。 */
    public byte[] streamData(final byte[] payload) throws Exception {
        return call(STREAM_METHOD, payload);
    }

    private byte[] call(final MethodDescriptor<byte[], byte[]> method, final byte[] payload) throws Exception {
        ensureServer();
        return ClientCalls.blockingUnaryCall(channel(), method,
                io.grpc.CallOptions.DEFAULT.withDeadlineAfter(CLIENT_DEADLINE_MS, TimeUnit.MILLISECONDS), payload);
    }

    /**
     * 服务端按给定区间随机睡一会儿（闭区间毫秒），让<b>客户端出口 span 的耗时</b>有分布，排行页的分位与
     * 条形才有形状。睡在服务端处理里（而非客户端调用之外），才会被客户端 span 覆盖到。
     */
    private void simulateLatency(final int[] delayRangeMs) {
        if (!simulateLatency) {
            return;
        }
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(delayRangeMs[0], delayRangeMs[1] + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void shutdown() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    /** byte[] 直通编解码：demo 只回显，不做真实序列化。 */
    private static final class IdentityMarshaller implements MethodDescriptor.Marshaller<byte[]> {

        private static final IdentityMarshaller INSTANCE = new IdentityMarshaller();

        @Override
        public InputStream stream(final byte[] value) {
            return new ByteArrayInputStream(value);
        }

        @Override
        public byte[] parse(final InputStream stream) {
            try {
                final int available = stream.available();
                final byte[] out = new byte[available];
                int read = 0;
                while (read < available) {
                    final int n = stream.read(out, read, available - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
                return out;
            } catch (Exception e) {
                throw new IllegalStateException("echo 编解码失败(不该发生:byte[] 直通)", e);
            }
        }
    }
}
