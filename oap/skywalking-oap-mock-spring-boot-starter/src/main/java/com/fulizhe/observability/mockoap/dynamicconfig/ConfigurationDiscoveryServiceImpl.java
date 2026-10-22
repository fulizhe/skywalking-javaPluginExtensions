package com.fulizhe.observability.mockoap.dynamicconfig;

import java.util.concurrent.atomic.AtomicLong;

import org.apache.skywalking.apm.dependencies.io.grpc.stub.StreamObserver;
import org.apache.skywalking.apm.network.common.v3.Commands;
import org.apache.skywalking.apm.network.language.agent.v3.ConfigurationDiscoveryServiceGrpc;
import org.apache.skywalking.apm.network.language.agent.v3.ConfigurationSyncRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SkyWalking 原生协议 {@code ConfigurationDiscoveryService.fetchConfigurations} 的最小实现。
 *
 * <p>mock-OAP 不提供动态配置，因此返回空 {@link Commands}，表示当前没有配置变更。
 */
public class ConfigurationDiscoveryServiceImpl
		extends ConfigurationDiscoveryServiceGrpc.ConfigurationDiscoveryServiceImplBase {

	private static final Logger log = LoggerFactory.getLogger(ConfigurationDiscoveryServiceImpl.class);
	private static final long LOG_EVERY = 10L;

	private final AtomicLong requestCount = new AtomicLong();

	@Override
	public void fetchConfigurations(ConfigurationSyncRequest request, StreamObserver<Commands> responseObserver) {
		long count = requestCount.incrementAndGet();
		if (count == 1 || count % LOG_EVERY == 0) {
			log.info("### [mock-oap] configuration discovery request received: count={}, service={}, uuid={}; "
					+ "no dynamic configuration, returning empty commands",
					count, request.getService(), request.getUuid());
		}
		responseObserver.onNext(Commands.getDefaultInstance());
		responseObserver.onCompleted();
	}
}
