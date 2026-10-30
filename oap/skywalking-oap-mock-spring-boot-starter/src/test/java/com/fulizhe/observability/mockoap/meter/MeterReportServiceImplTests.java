package com.fulizhe.observability.mockoap.meter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.apache.skywalking.apm.dependencies.io.grpc.Status;
import org.apache.skywalking.apm.dependencies.io.grpc.stub.StreamObserver;
import org.apache.skywalking.apm.network.language.agent.v3.MeterData;
import org.apache.skywalking.apm.network.language.agent.v3.MeterDataCollection;
import org.junit.jupiter.api.Test;

import com.fulizhe.observability.mockoap.testutil.RecordingCommandsObserver;

public class MeterReportServiceImplTests {

	@Test
	public void acknowledgesMeterBatchStreamWithEmptyCommands() {
		RecordingCommandsObserver observer = new RecordingCommandsObserver();
		StreamObserver<MeterDataCollection> request = new MeterReportServiceImpl().collectBatch(observer);

		request.onNext(MeterDataCollection.getDefaultInstance());
		request.onCompleted();

		observer.assertAcknowledged();
	}

	@Test
	public void acknowledgesSingleMeterStreamWithEmptyCommands() {
		RecordingCommandsObserver observer = new RecordingCommandsObserver();
		StreamObserver<MeterData> request = new MeterReportServiceImpl().collect(observer);

		request.onNext(MeterData.getDefaultInstance());
		request.onCompleted();

		observer.assertAcknowledged();
	}

	@Test
	public void doesNotAcknowledgeWhenClientStreamFails() {
		RecordingCommandsObserver observer = new RecordingCommandsObserver();
		StreamObserver<MeterDataCollection> request = new MeterReportServiceImpl().collectBatch(observer);

		request.onError(new IllegalStateException("client went away"));

		assertNull(observer.response());
		assertFalse(observer.completed());
		assertNull(observer.error());
	}

	@Test
	public void keepsCancelledStreamErrorsQuiet() {
		RecordingCommandsObserver observer = new RecordingCommandsObserver();
		StreamObserver<MeterDataCollection> request = new MeterReportServiceImpl().collectBatch(observer);

		request.onError(Status.CANCELLED.asRuntimeException());

		assertNull(observer.response());
		assertFalse(observer.completed());
		assertNull(observer.error());
	}
}
