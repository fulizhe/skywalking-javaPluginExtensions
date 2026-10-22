package com.fulizhe.observability.mockoap.ingest;

/** Outcome of offering one message to an ingress queue. */
public enum IngestSubmitResult {
	ACCEPTED,
	ACCEPTED_AFTER_DROP,
	REJECTED_INVALID,
	REJECTED_OVERSIZE,
	REJECTED_STOPPED;

	/** Shared gRPC status text for native and OTLP ingress rejection. */
	public static final String REJECTION_DESCRIPTION =
			"ingest rejected: message is invalid/oversized or the queue is full/stopped";

	public boolean isRejected() {
		return this != ACCEPTED && this != ACCEPTED_AFTER_DROP;
	}
}
