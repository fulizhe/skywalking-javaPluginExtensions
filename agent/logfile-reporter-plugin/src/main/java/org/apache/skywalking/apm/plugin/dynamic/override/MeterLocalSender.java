package org.apache.skywalking.apm.plugin.dynamic.override;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.skywalking.apm.agent.core.boot.OverrideImplementor;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.meter.BaseMeter;
import org.apache.skywalking.apm.agent.core.meter.MeterId;
import org.apache.skywalking.apm.agent.core.meter.MeterSender;
import org.apache.skywalking.apm.agent.core.meter.MeterService;
import org.apache.skywalking.apm.agent.core.remote.GRPCChannelStatus;
import org.apache.skywalking.apm.network.language.agent.v3.MeterDataCollection;

/**
 * <p>
 * 
 * <p>
 * <p>
 * Refer To {@code KafkaMeterSender}
 * <p>
 */
@OverrideImplementor(MeterSender.class)
public class MeterLocalSender extends MeterSender {
	private static final ILog LOGGER = LogManager.getLogger(MeterLocalSender.class);

// 借鉴自Druid的JdbcDataSourceStat
	private LinkedHashMap<String, Map<String, Object>> meterDataCache;

	@Override
	public void prepare() {
		meterDataCache = new LinkedHashMap<String, Map<String, Object>>(16, 0.75f, false) {
			private static final long serialVersionUID = 1L;

			protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
				return (size() > 1000);

			}
		};
	}

	@Override
	public void boot() {
	}

	public List<Map<String, Object>> getMeterDatas() {
		// 将Collection转换为List，便于前端或调用方处理
		return new java.util.ArrayList<>(meterDataCache.values());
	}

	@Override
	public void send(Map<MeterId, BaseMeter> meterMap2, MeterService meterService) {

		MeterDataCollection.Builder builder = MeterDataCollection.newBuilder();
		transform(meterMap2, meterData -> {
			if (LOGGER.isDebugEnable()) {
				LOGGER.debug("Meter data reporting, instance: {}", meterData.getServiceInstance());
			}
			builder.addMeterData(meterData);
		});

		// 将MeterDataCollection中的数据转换为Map并存入meterDataCache
		List<org.apache.skywalking.apm.network.language.agent.v3.MeterData> meterDataList = builder.build()
				.getMeterDataList();
		for (org.apache.skywalking.apm.network.language.agent.v3.MeterData meterData : meterDataList) {
			Map<String, Object> meterMap = new java.util.HashMap<>();
			// 存储服务和实例信息
			meterMap.put("service", meterData.getService());
			meterMap.put("serviceInstance", meterData.getServiceInstance());
			meterMap.put("timestamp", meterData.getTimestamp());

			// 存储指标类型
			meterMap.put("type", meterData.getMetricCase().name());
			// 存储指标详细数据
			switch (meterData.getMetricCase()) {
			case SINGLEVALUE:
				Map<String, Object> singleValueMap = new java.util.HashMap<>();
				singleValueMap.put("name", meterData.getSingleValue().getName());
				singleValueMap.put("value", meterData.getSingleValue().getValue());
				// 将labelsList转换为Map，便于前端处理
				Map<String, String> labelsMap = new java.util.HashMap<>();
				for (org.apache.skywalking.apm.network.language.agent.v3.Label label : meterData.getSingleValue()
						.getLabelsList()) {
					labelsMap.put(label.getName(), label.getValue());
				}
				singleValueMap.put("labels", labelsMap);
				meterMap.put("singleValue", singleValueMap);
				break;
			case HISTOGRAM:
				Map<String, Object> histogramMap = new java.util.HashMap<>();
				histogramMap.put("name", meterData.getHistogram().getName());
				// 将labelsList转换为Map，便于前端处理
				Map<String, String> histogramLabelsMap = new java.util.HashMap<>();
				for (org.apache.skywalking.apm.network.language.agent.v3.Label label : meterData.getHistogram()
						.getLabelsList()) {
					histogramLabelsMap.put(label.getName(), label.getValue());
				}
				histogramMap.put("labels", histogramLabelsMap);

				// 将buckets（valuesList）转换为List<Map>，每个bucket包含value和count
				java.util.List<Map<String, Object>> bucketsList = new java.util.ArrayList<>();
				List<org.apache.skywalking.apm.network.language.agent.v3.MeterBucketValue> valuesList = meterData
						.getHistogram().getValuesList();

				for (org.apache.skywalking.apm.network.language.agent.v3.MeterBucketValue bucket : valuesList) {
					Map<String, Object> bucketMap = new java.util.HashMap<>();
					bucketMap.put("bucket", bucket.getBucket());
					bucketMap.put("count", bucket.getCount());
					bucketMap.put("isNegativeInfinity", bucket.getIsNegativeInfinity());
					bucketsList.add(bucketMap);
				}
				histogramMap.put("buckets", bucketsList);
				meterMap.put("histogram", histogramMap);
				break;
			case METRIC_NOT_SET:
//					Map<String, Object> minMaxAvgMap = new java.util.HashMap<>();
//					minMaxAvgMap.put("name", meterData.getMinMaxAvg().getName());
//					minMaxAvgMap.put("labels", meterData.getMinMaxAvg().getLabelsMap());
//					minMaxAvgMap.put("min", meterData.getMinMaxAvg().getMin());
//					minMaxAvgMap.put("max", meterData.getMinMaxAvg().getMax());
//					minMaxAvgMap.put("avg", meterData.getMinMaxAvg().getAvg());
//					minMaxAvgMap.put("count", meterData.getMinMaxAvg().getCount());
//					meterMap.put("minMaxAvg", minMaxAvgMap);
				break;
			default:
				// 其他类型暂不处理
				break;
			}
			// 以实例名+时间戳作为key，保证唯一性
			String cacheKey = meterData.getServiceInstance() + "_" + System.currentTimeMillis();
			meterDataCache.put(cacheKey, meterMap);
		}
	}

	@Override
	public void onComplete() {

	}

	@Override
	public void shutdown() {

	}

	@Override
	public void statusChanged(final GRPCChannelStatus status) {
		LOGGER.debug("### statusChanged 【 {} 】", status);
	}
}