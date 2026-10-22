package com.fulizhe.observability.mockoap.otlp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fulizhe.observability.mockoap.MockOapProperties;
import com.fulizhe.observability.mockoap.trace.MockOapSpan;
import com.fulizhe.observability.mockoap.trace.MockOapTraceRecord;

import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;

/** Converts OTLP ResourceSpans into trace records queryable by the mock-OAP views. */
public class OtlpTraceConverter {
	private final MockOapProperties properties;

	public OtlpTraceConverter(MockOapProperties properties) {
		this.properties = properties;
	}

	public OtlpTraceConverter() {
		this(new MockOapProperties());
	}

	public boolean isValidSpan(Span span) {
		boolean valid = span.getTraceId().size() == 16 && span.getSpanId().size() == 8
				&& !allZero(span.getTraceId().toByteArray()) && !allZero(span.getSpanId().toByteArray())
				&& (span.getParentSpanId().isEmpty() || (span.getParentSpanId().size() == 8
						&& !allZero(span.getParentSpanId().toByteArray())))
				&& span.getAttributesCount() <= Math.max(0, properties.getMaxOtlpAttributesPerSpan())
				&& span.getEventsList().stream().allMatch(event -> event.getAttributesCount()
						<= Math.max(0, properties.getMaxOtlpAttributesPerSpan()))
				&& span.getLinksList().stream().allMatch(link -> link.getAttributesCount()
						<= Math.max(0, properties.getMaxOtlpAttributesPerSpan()))
				&& span.getEventsCount() <= Math.max(0, properties.getMaxOtlpEventsPerSpan())
				&& span.getLinksCount() <= Math.max(0, properties.getMaxOtlpLinksPerSpan())
				&& withinStringLimit(span);
		return valid;
	}

	public boolean isWithinStringLimit(Span span) {
		return withinStringLimit(span);
	}

	public boolean isWithinStringLimit(ResourceSpans resourceSpans) {
		return withinStringLimit(resourceSpans);
	}

	public boolean hasUsableServiceName(ResourceSpans resourceSpans) {
		return serviceName(resourceSpans.getResource().getAttributesList()) != null;
	}

	private boolean withinStringLimit(Span span) {
		int max = Math.max(0, properties.getMaxOtlpStringLength());
		if (span.getName().length() > max || span.getTraceState().length() > max) return false;
		if (span.hasStatus() && span.getStatus().getMessage().length() > max) return false;
		for (KeyValue value : span.getAttributesList()) if (!withinStringLimit(value, max)) return false;
		for (Span.Event event : span.getEventsList()) {
			if (event.getName().length() > max) return false;
			for (KeyValue value : event.getAttributesList()) if (!withinStringLimit(value, max)) return false;
		}
		for (Span.Link link : span.getLinksList()) {
			if (link.getTraceId().size() != 16 || link.getSpanId().size() != 8
					|| allZero(link.getTraceId().toByteArray()) || allZero(link.getSpanId().toByteArray())
					|| link.getTraceState().length() > max) return false;
			for (KeyValue value : link.getAttributesList()) if (!withinStringLimit(value, max)) return false;
		}
		return true;
	}

	private boolean withinStringLimit(ResourceSpans resourceSpans) {
		int max = Math.max(0, properties.getMaxOtlpStringLength());
		if (resourceSpans.getSchemaUrl().length() > max) return false;
		for (KeyValue value : resourceSpans.getResource().getAttributesList()) {
			if (!withinStringLimit(value, max)) return false;
			if ("service.name".equals(value.getKey()) || "faas.name".equals(value.getKey())
					|| "k8s.deployment.name".equals(value.getKey()) || "process.executable.name".equals(value.getKey())) {
				if (!value.getValue().hasStringValue() || value.getValue().getStringValue().trim().isEmpty()) {
					continue;
				}
			}
		}
		for (ScopeSpans scope : resourceSpans.getScopeSpansList()) {
			if (scope.getSchemaUrl().length() > max) return false;
			if (scope.hasScope() && (scope.getScope().getName().length() > max
					|| scope.getScope().getVersion().length() > max)) return false;
			if (scope.hasScope()) {
				for (KeyValue value : scope.getScope().getAttributesList()) {
					if (!withinStringLimit(value, max)) return false;
				}
			}
		}
		return true;
	}

	private boolean withinStringLimit(KeyValue value, int max) {
		return value.getKey().length() <= max && withinStringLimit(value.getValue(), max);
	}

	private boolean withinStringLimit(AnyValue value, int max) {
		switch (value.getValueCase()) {
		case STRING_VALUE: return value.getStringValue().length() <= max;
		case ARRAY_VALUE:
			for (AnyValue item : value.getArrayValue().getValuesList()) if (!withinStringLimit(item, max)) return false;
			return true;
		case KVLIST_VALUE:
			for (KeyValue item : value.getKvlistValue().getValuesList()) if (!withinStringLimit(item, max)) return false;
			return true;
		default: return true;
		}
	}

	public MockOapSpan toSpanForExport(Span span, ScopeSpans scopeSpans) {
		if (!isValidSpan(span)) {
			throw new IllegalArgumentException("Invalid OTLP trace or span identifier");
		}
		return toSpan(span, hex(span.getSpanId().toByteArray()),
				span.getParentSpanId().isEmpty() ? null : hex(span.getParentSpanId().toByteArray()), scopeSpans);
	}

	public List<MockOapTraceRecord> toRecords(ResourceSpans resourceSpans) {
		Map<String, MockOapTraceRecord> records = new LinkedHashMap<String, MockOapTraceRecord>();
		if (!withinStringLimit(resourceSpans)) return recordsAsList(records);
		List<KeyValue> resourceAttributes = resourceSpans.getResource().getAttributesList();
		String service = serviceName(resourceAttributes);
		if (service == null) {
			return recordsAsList(records);
		}
		String instance = stringAttribute(resourceAttributes, "service.instance.id");
		String resourceIdentity = resourceIdentity(resourceAttributes);
		for (ScopeSpans scopeSpans : resourceSpans.getScopeSpansList()) {
			for (Span span : scopeSpans.getSpansList()) {
				if (!isValidSpan(span)) continue;
				String traceId = hex(span.getTraceId().toByteArray());
				// A C++ bridge span keeps the original SkyWalking trace ID so it can join
				// native Java/Go/Node records; the OTel trace_id remains the transport ID.
				String logicalTraceId = stringAttribute(span.getAttributesList(), "sw8.trace.id");
				if (logicalTraceId == null || logicalTraceId.isEmpty()) logicalTraceId = traceId;
				String spanId = hex(span.getSpanId().toByteArray());
				String parentId = span.getParentSpanId().isEmpty() ? null : hex(span.getParentSpanId().toByteArray());
				String recordKey = "OTLP:" + resourceIdentity + ":" + logicalTraceId;
				MockOapTraceRecord record = records.get(recordKey);
				if (record == null) {
					record = new MockOapTraceRecord();
					record.setSource(MockOapTraceRecord.SOURCE_OTLP);
					record.setRecordId(recordKey);
					record.setTraceId(logicalTraceId);
					record.setService(service);
					record.setServiceInstance(instance);
					record.setResourceAttributes(attributes(resourceSpans.getResource().getAttributesList()));
					record.setResourceSchemaUrl(resourceSpans.getSchemaUrl());
					record.setTraceSegmentId(null);
					record.setReceivedAt(System.currentTimeMillis());
					records.put(recordKey, record);
				}
				MockOapSpan converted = toSpan(span, spanId, parentId, scopeSpans);
				record.getSpans().add(converted);
			}
		}
		for (MockOapTraceRecord record : records.values()) {
			completeSummary(record);
		}
		return recordsAsList(records);
	}

	private static List<MockOapTraceRecord> recordsAsList(Map<String, MockOapTraceRecord> records) {
		return new ArrayList<MockOapTraceRecord>(records.values());
	}

	private MockOapSpan toSpan(Span span, String spanId, String parentId, ScopeSpans scopeSpans) {
		MockOapSpan converted = new MockOapSpan();
		converted.setSpanIdString(spanId);
		converted.setParentSpanIdString(parentId);
		converted.setSpanId(0);
		converted.setParentSpanId(-1);
		converted.setOperationName(span.getName());
		converted.setSpanType(span.getKind().name());
		converted.setSpanLayer("Unknown");
		converted.setStartTime(span.getStartTimeUnixNano() / 1_000_000L);
		converted.setEndTime(span.getEndTimeUnixNano() / 1_000_000L);
		converted.setError(span.hasStatus()
				&& span.getStatus().getCode() == io.opentelemetry.proto.trace.v1.Status.StatusCode.STATUS_CODE_ERROR);
		converted.setOtlpTraceState(span.getTraceState());
		converted.setOtlpFlags(span.getFlags());
		converted.setOtlpDroppedAttributesCount(span.getDroppedAttributesCount());
		converted.setOtlpDroppedEventsCount(span.getDroppedEventsCount());
		converted.setOtlpDroppedLinksCount(span.getDroppedLinksCount());
		converted.setOtlpStatusCode(span.hasStatus() ? span.getStatus().getCode().name() : "STATUS_CODE_UNSET");
		converted.setOtlpStatusMessage(span.hasStatus() ? span.getStatus().getMessage() : "");
		if (scopeSpans.hasScope()) {
			converted.setOtlpScopeName(scopeSpans.getScope().getName());
			converted.setOtlpScopeVersion(scopeSpans.getScope().getVersion());
			converted.setOtlpScopeSchemaUrl(scopeSpans.getSchemaUrl());
			converted.setOtlpScopeAttributes(attributes(scopeSpans.getScope().getAttributesList()));
		}
		converted.setOtlpAttributes(attributes(span.getAttributesList()));
		for (KeyValue attribute : span.getAttributesList()) {
			String scalar = scalar(attribute.getValue());
			if (scalar != null) {
				converted.getTags().put(attribute.getKey(), scalar);
			}
		}
		for (Span.Event event : span.getEventsList()) {
			Map<String, Object> item = new LinkedHashMap<String, Object>();
			item.put("timeUnixNano", Long.toUnsignedString(event.getTimeUnixNano()));
			item.put("name", event.getName());
			item.put("attributes", attributes(event.getAttributesList()));
			item.put("droppedAttributesCount", event.getDroppedAttributesCount());
			converted.getOtlpEvents().add(item);
		}
		for (Span.Link link : span.getLinksList()) {
			Map<String, Object> item = new LinkedHashMap<String, Object>();
			item.put("traceId", hex(link.getTraceId().toByteArray()));
			item.put("spanId", hex(link.getSpanId().toByteArray()));
			item.put("traceState", link.getTraceState());
			item.put("flags", link.getFlags());
			item.put("attributes", attributes(link.getAttributesList()));
			item.put("droppedAttributesCount", link.getDroppedAttributesCount());
			converted.getOtlpLinks().add(item);
		}
		return converted;
	}

	private static Map<String, Object> attributes(List<KeyValue> values) {
		Map<String, Object> out = new TreeMap<String, Object>();
		for (KeyValue value : values) {
			out.put(value.getKey(), anyValue(value.getValue()));
		}
		return out;
	}

	private static Object anyValue(AnyValue value) {
		switch (value.getValueCase()) {
		case STRING_VALUE: return value.getStringValue();
		case BOOL_VALUE: return value.getBoolValue();
		case INT_VALUE: return value.getIntValue();
		case DOUBLE_VALUE: return value.getDoubleValue();
		case ARRAY_VALUE:
			List<Object> array = new ArrayList<Object>();
			for (AnyValue item : value.getArrayValue().getValuesList()) array.add(anyValue(item));
			return array;
		case KVLIST_VALUE: return attributes(value.getKvlistValue().getValuesList());
		case BYTES_VALUE: return java.util.Base64.getEncoder().encodeToString(value.getBytesValue().toByteArray());
		default: return null;
		}
	}

	private static void completeSummary(MockOapTraceRecord record) {
		long start = Long.MAX_VALUE;
		long end = Long.MIN_VALUE;
		boolean error = false;
		for (MockOapSpan span : record.getSpans()) {
			start = Math.min(start, span.getStartTime());
			end = Math.max(end, span.getEndTime());
			error |= span.isError();
			if (record.getEntryOperation() == null
					&& (span.getSpanType().endsWith("SERVER") || span.getSpanType().endsWith("CONSUMER"))) {
				record.setEntryOperation(span.getOperationName());
			}
		}
		record.setSpanCount(record.getSpans().size());
		record.setStartTime(start == Long.MAX_VALUE ? 0 : start);
		record.setEndTime(end == Long.MIN_VALUE ? 0 : end);
		record.setDurationMs(end > start ? end - start : 0);
		record.setError(error);
	}

	private static String serviceName(List<KeyValue> resourceAttributes) {
		String[] candidates = { "service.name", "faas.name", "k8s.deployment.name", "process.executable.name" };
		for (String key : candidates) {
			for (KeyValue attribute : resourceAttributes) {
				if (key.equals(attribute.getKey()) && attribute.getValue().hasStringValue()) {
					String value = attribute.getValue().getStringValue();
					if (!value.trim().isEmpty()) return value.trim();
				}
			}
		}
		return null;
	}

	private static String stringAttribute(List<KeyValue> attributes, String key) {
		for (KeyValue attribute : attributes) {
			if (key.equals(attribute.getKey()) && attribute.getValue().hasStringValue()) {
				String value = attribute.getValue().getStringValue();
				return value.trim().isEmpty() ? null : value.trim();
			}
		}
		return null;
	}

	private static String resourceIdentity(List<KeyValue> resourceAttributes) {
		List<String> attributes = new ArrayList<String>();
		for (KeyValue attribute : resourceAttributes) attributes.add(canonical(attribute));
		Collections.sort(attributes);
		StringBuilder identity = new StringBuilder();
		for (String attribute : attributes) append(identity, attribute);
		return identity.toString();
	}

	private static String canonical(KeyValue value) {
		StringBuilder result = new StringBuilder();
		append(result, value.getKey());
		append(result, canonical(value.getValue()));
		return result.toString();
	}

	private static String canonical(AnyValue value) {
		StringBuilder result = new StringBuilder();
		result.append(value.getValueCase().name()).append(':');
		switch (value.getValueCase()) {
		case STRING_VALUE:
			append(result, value.getStringValue());
			break;
		case BOOL_VALUE:
			result.append(value.getBoolValue());
			break;
		case INT_VALUE:
			result.append(value.getIntValue());
			break;
		case DOUBLE_VALUE:
			result.append(Long.toHexString(Double.doubleToLongBits(value.getDoubleValue())));
			break;
		case BYTES_VALUE:
			result.append(hex(value.getBytesValue().toByteArray()));
			break;
		case ARRAY_VALUE:
			for (AnyValue item : value.getArrayValue().getValuesList()) append(result, canonical(item));
			break;
		case KVLIST_VALUE:
			List<String> entries = new ArrayList<String>();
			for (KeyValue item : value.getKvlistValue().getValuesList()) entries.add(canonical(item));
			Collections.sort(entries);
			for (String entry : entries) append(result, entry);
			break;
		default:
			break;
		}
		return result.toString();
	}

	private static void append(StringBuilder target, String value) {
		String safe = value == null ? "" : value;
		target.append(safe.length()).append(':').append(safe);
	}

	private static boolean allZero(byte[] bytes) {
		for (byte value : bytes) if (value != 0) return false;
		return true;
	}

	private static String scalar(AnyValue value) {
		switch (value.getValueCase()) {
		case STRING_VALUE:
			return value.getStringValue();
		case BOOL_VALUE:
			return String.valueOf(value.getBoolValue());
		case INT_VALUE:
			return String.valueOf(value.getIntValue());
		case DOUBLE_VALUE:
			return String.valueOf(value.getDoubleValue());
		default:
			return null;
		}
	}

	static String hex(byte[] bytes) {
		StringBuilder out = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			out.append(Character.forDigit((b >>> 4) & 0xf, 16));
			out.append(Character.forDigit(b & 0xf, 16));
		}
		return out.toString();
	}
}
