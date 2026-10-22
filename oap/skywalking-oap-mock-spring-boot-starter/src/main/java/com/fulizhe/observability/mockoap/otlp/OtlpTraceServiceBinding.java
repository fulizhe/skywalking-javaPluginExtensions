package com.fulizhe.observability.mockoap.otlp;

import java.io.InputStream;

import org.apache.skywalking.apm.dependencies.io.grpc.MethodDescriptor;
import org.apache.skywalking.apm.dependencies.io.grpc.ServerCall;
import org.apache.skywalking.apm.dependencies.io.grpc.ServerCallHandler;
import org.apache.skywalking.apm.dependencies.io.grpc.ServerServiceDefinition;
import org.apache.skywalking.apm.dependencies.io.grpc.Status;

import com.fulizhe.observability.mockoap.ingest.IngestSubmitResult;

import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;

/** Binds the standard OTLP protobuf contract to the agent-shaded gRPC server. */
public class OtlpTraceServiceBinding {

	private static final MethodDescriptor<ExportTraceServiceRequest, ExportTraceServiceResponse> EXPORT_METHOD =
			MethodDescriptor.<ExportTraceServiceRequest, ExportTraceServiceResponse>newBuilder()
					.setType(MethodDescriptor.MethodType.UNARY)
					.setFullMethodName("opentelemetry.proto.collector.trace.v1.TraceService/Export")
					.setRequestMarshaller(new ProtoMarshaller<ExportTraceServiceRequest>() {
						@Override
						public ExportTraceServiceRequest parseFrom(byte[] bytes) {
							try {
								return ExportTraceServiceRequest.parseFrom(bytes);
							} catch (com.google.protobuf.InvalidProtocolBufferException e) {
								throw Status.INVALID_ARGUMENT.withDescription("Invalid OTLP trace request")
										.withCause(e).asRuntimeException();
							}
						}
						@Override
						public byte[] toByteArray(ExportTraceServiceRequest message) {
							return message.toByteArray();
						}
					})
					.setResponseMarshaller(new ProtoMarshaller<ExportTraceServiceResponse>() {
						@Override
						public ExportTraceServiceResponse parseFrom(byte[] bytes) {
							try {
								return ExportTraceServiceResponse.parseFrom(bytes);
							} catch (com.google.protobuf.InvalidProtocolBufferException e) {
								throw new IllegalArgumentException("Invalid OTLP trace response", e);
							}
						}
						@Override
						public byte[] toByteArray(ExportTraceServiceResponse message) {
							return message.toByteArray();
						}
					})
					.build();

	private final OtlpTraceServiceImpl service;

	public OtlpTraceServiceBinding(OtlpTraceServiceImpl service) {
		this.service = service;
	}

	public ServerServiceDefinition definition() {
		return ServerServiceDefinition.builder("opentelemetry.proto.collector.trace.v1.TraceService")
				.addMethod(EXPORT_METHOD, new ServerCallHandler<ExportTraceServiceRequest, ExportTraceServiceResponse>() {
					@Override
					public ServerCall.Listener<ExportTraceServiceRequest> startCall(
							final ServerCall<ExportTraceServiceRequest, ExportTraceServiceResponse> call,
							org.apache.skywalking.apm.dependencies.io.grpc.Metadata headers) {
						// Read one extra message so a malformed client cannot silently send a stream to this unary method.
						call.request(2);
						return new ServerCall.Listener<ExportTraceServiceRequest>() {
							private ExportTraceServiceRequest request;
							private boolean multipleRequests;
							@Override
							public void onMessage(ExportTraceServiceRequest message) {
								if (request != null) {
									multipleRequests = true;
									return;
								}
								request = message;
							}
							@Override
							public void onHalfClose() {
								if (multipleRequests) {
									call.close(Status.INVALID_ARGUMENT.withDescription("TraceService/Export accepts one request"),
											new org.apache.skywalking.apm.dependencies.io.grpc.Metadata());
									return;
								}
								if (request == null) {
									call.close(Status.INVALID_ARGUMENT.withDescription("Missing OTLP request"),
											new org.apache.skywalking.apm.dependencies.io.grpc.Metadata());
									return;
								}
								try {
									IngestSubmitResult submitted = service.submit(request);
									if (submitted.isRejected()) {
										call.close(Status.RESOURCE_EXHAUSTED
												.withDescription(IngestSubmitResult.REJECTION_DESCRIPTION),
												new org.apache.skywalking.apm.dependencies.io.grpc.Metadata());
										return;
									}
									call.sendHeaders(new org.apache.skywalking.apm.dependencies.io.grpc.Metadata());
									call.sendMessage(ExportTraceServiceResponse.getDefaultInstance());
									call.close(Status.OK, new org.apache.skywalking.apm.dependencies.io.grpc.Metadata());
								} catch (RuntimeException e) {
									call.close(Status.INTERNAL.withDescription("OTLP trace export failed"),
											new org.apache.skywalking.apm.dependencies.io.grpc.Metadata());
								}
							}
						};
					}
				}).build();
	}

	private interface ProtoMarshaller<T extends com.google.protobuf.MessageLite> extends MethodDescriptor.Marshaller<T> {
		T parseFrom(byte[] bytes);
		byte[] toByteArray(T message);
		@Override
		default InputStream stream(T message) {
			return new java.io.ByteArrayInputStream(toByteArray(message));
		}
		@Override
		default T parse(InputStream stream) {
			try {
				return parseFrom(readAll(stream));
			} catch (java.io.IOException e) {
				throw new IllegalArgumentException("Unable to read OTLP payload", e);
			}
		}
		static byte[] readAll(InputStream in) throws java.io.IOException {
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int count;
			while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
			return out.toByteArray();
		}
	}
}
