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
import java.util.concurrent.TimeUnit;

import javax.annotation.PreDestroy;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
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
    private static final String METHOD_NAME = "Echo";

    /** 客户端调用的硬上限：超时就当这次 RPC 失败（超时会不会置 isError 见页面 caveats 的第三条）。 */
    private static final long CLIENT_DEADLINE_MS = 2000L;

    private static final MethodDescriptor<byte[], byte[]> ECHO_METHOD = MethodDescriptor.<byte[], byte[]>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, METHOD_NAME))
            .setRequestMarshaller(IdentityMarshaller.INSTANCE)
            .setResponseMarshaller(IdentityMarshaller.INSTANCE)
            .build();

    @Value("${deps.grpc.port:9099}")
    private int port;

    private Server server;
    private volatile ManagedChannel channel;

    /** gRPC server 懒启动（构造时端口配置未必已注入，且不想拖慢应用启动）。 */
    private synchronized void ensureServer() {
        if (server != null) {
            return;
        }
        final ServerServiceDefinition service = ServerServiceDefinition.builder(SERVICE_NAME)
                .addMethod(ECHO_METHOD, ServerCalls.asyncUnaryCall((request, observer) -> {
                    observer.onNext(request);
                    observer.onCompleted();
                }))
                .build();
        try {
            server = NettyServerBuilder.forPort(port).addService(service).build().start();
            log.info("### [deps-demo] gRPC server up on 127.0.0.1:{} (service {})", port, SERVICE_NAME);
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

    /**
     * 调一次 echo，返回服务端的回显。**异常一律抛给调用方吞掉**（由 {@code DepsDemoService} 统一处理），
     * 这里不做降级 —— 造数端点要的就是"如实记下成功或失败"。
     */
    public byte[] echo(final byte[] payload) throws Exception {
        ensureServer();
        return ClientCalls.blockingUnaryCall(channel(), ECHO_METHOD,
                io.grpc.CallOptions.DEFAULT.withDeadlineAfter(CLIENT_DEADLINE_MS, TimeUnit.MILLISECONDS), payload);
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