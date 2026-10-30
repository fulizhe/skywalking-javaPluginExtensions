package com.fulizhe.observability.mockoap.meter;

import org.apache.skywalking.apm.dependencies.io.grpc.Status;
import org.apache.skywalking.apm.dependencies.io.grpc.stub.StreamObserver;
import org.apache.skywalking.apm.network.common.v3.Commands;
import org.apache.skywalking.apm.network.language.agent.v3.MeterData;
import org.apache.skywalking.apm.network.language.agent.v3.MeterDataCollection;
import org.apache.skywalking.apm.network.language.agent.v3.MeterReportServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SkyWalking 原生协议 {@code MeterReportService} 的最小实现。
 *
 * <p>skywalking-go 等 Agent 会通过 {@code collectBatch} 上报运行时指标。mock-OAP 不保存 meter 数据，
 * 只把客户端流读完并返回空 {@link Commands}，避免 Agent 持续收到 {@code UNIMPLEMENTED}。
 */
public class MeterReportServiceImpl extends MeterReportServiceGrpc.MeterReportServiceImplBase {

	private static final Logger log = LoggerFactory.getLogger(MeterReportServiceImpl.class);

	@Override
	public StreamObserver<MeterData> collect(StreamObserver<Commands> responseObserver) {
		return new AckObserver<MeterData>("collect", responseObserver);
	}

	@Override
	public StreamObserver<MeterDataCollection> collectBatch(StreamObserver<Commands> responseObserver) {
		return new AckObserver<MeterDataCollection>("collectBatch", responseObserver);
	}

	/** 两个 meter 流共用的确认逻辑：丢弃数据，在流结束后回空 {@link Commands}。 */
	private static final class AckObserver<T> implements StreamObserver<T> {

		private final String method;
		private final StreamObserver<Commands> responseObserver;

		private AckObserver(String method, StreamObserver<Commands> responseObserver) {
			this.method = method;
			this.responseObserver = responseObserver;
		}

		@Override
		public void onNext(T value) {
			// mock-OAP 不保存 meter 数据
		}

		@Override
		public void onError(Throwable throwable) {
			Status status = Status.fromThrowable(throwable);
			if (status.getCode() == Status.Code.CANCELLED) {
				if (log.isDebugEnabled()) {
					log.debug("meter {} stream cancelled: {}", method, throwable.getMessage());
				}
				return;
			}
			log.warn("meter {} stream closed with error: {}", method, throwable.toString());
		}

		@Override
		public void onCompleted() {
			responseObserver.onNext(Commands.getDefaultInstance());
			responseObserver.onCompleted();
		}
	}
}
