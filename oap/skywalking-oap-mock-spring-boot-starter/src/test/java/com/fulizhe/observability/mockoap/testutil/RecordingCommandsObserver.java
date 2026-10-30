package com.fulizhe.observability.mockoap.testutil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.skywalking.apm.dependencies.io.grpc.stub.StreamObserver;
import org.apache.skywalking.apm.network.common.v3.Commands;

/** 记录服务端 {@link Commands} 响应的测试辅助类。 */
public final class RecordingCommandsObserver implements StreamObserver<Commands> {

	private Commands response;
	private boolean completed;
	private Throwable error;

	@Override
	public void onNext(Commands commands) {
		this.response = commands;
	}

	@Override
	public void onError(Throwable throwable) {
		this.error = throwable;
	}

	@Override
	public void onCompleted() {
		this.completed = true;
	}

	public Commands response() {
		return response;
	}

	public boolean completed() {
		return completed;
	}

	public Throwable error() {
		return error;
	}

	public void assertAcknowledged() {
		assertNotNull(response, "response");
		assertEquals(Commands.getDefaultInstance(), response);
		assertEquals(0, response.getCommandsCount());
		assertTrue(completed);
		assertNull(error);
	}
}
