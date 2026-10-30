package com.fulizhe.observability.mockoap.management;

import org.apache.skywalking.apm.dependencies.io.grpc.stub.StreamObserver;
import org.apache.skywalking.apm.network.common.v3.Commands;
import org.apache.skywalking.apm.network.management.v3.InstancePingPkg;
import org.apache.skywalking.apm.network.management.v3.InstanceProperties;
import org.apache.skywalking.apm.network.management.v3.ManagementServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SkyWalking 原生协议 {@code ManagementService} 的最小实现。
 *
 * <p>Agent 的注册（{@code reportInstanceProperties}）和心跳（{@code keepAlive}）都是 unary RPC。
 * mock-OAP 不维护实例注册表，只确认收到并返回空 {@link Commands}，表示没有命令下发；
 * 这样未安装 logfile-reporter-plugin 的 Agent 不会每 30 秒因 {@code UNIMPLEMENTED} 打印失败日志。
 */
public class ManagementServiceImpl extends ManagementServiceGrpc.ManagementServiceImplBase {

	private static final Logger log = LoggerFactory.getLogger(ManagementServiceImpl.class);

	@Override
	public void reportInstanceProperties(InstanceProperties request, StreamObserver<Commands> responseObserver) {
		if (log.isDebugEnabled()) {
			log.debug("instance properties received: service={}, instance={}, properties={}",
					request.getService(), request.getServiceInstance(), request.getPropertiesCount());
		}
		responseObserver.onNext(Commands.getDefaultInstance());
		responseObserver.onCompleted();
	}

	@Override
	public void keepAlive(InstancePingPkg request, StreamObserver<Commands> responseObserver) {
		if (log.isTraceEnabled()) {
			log.trace("keep alive received: service={}, instance={}",
					request.getService(), request.getServiceInstance());
		}
		responseObserver.onNext(Commands.getDefaultInstance());
		responseObserver.onCompleted();
	}
}
