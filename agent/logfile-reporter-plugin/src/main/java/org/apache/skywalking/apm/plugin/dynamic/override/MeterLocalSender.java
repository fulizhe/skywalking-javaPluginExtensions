package org.apache.skywalking.apm.plugin.dynamic.override;

import java.util.ArrayList;
import java.util.HashMap;
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
import org.apache.skywalking.apm.agent.core.reporter.logfile.LogFileReporterPluginConfig;
import org.apache.skywalking.apm.network.language.agent.v3.Label;
import org.apache.skywalking.apm.network.language.agent.v3.MeterBucketValue;
import org.apache.skywalking.apm.network.language.agent.v3.MeterData;
import org.apache.skywalking.apm.network.language.agent.v3.MeterHistogram;
import org.apache.skywalking.apm.network.language.agent.v3.MeterSingleValue;
import org.apache.skywalking.apm.toolkit.CircularBlockingQueue;
import org.apache.skywalking.apm.agent.core.conf.Config;
/**
 * <p>
 *
 * <p>
 * <p>
 * @link <a href="https://github.com/apache/skywalking-java/blob/main/apm-sniffer/optional-reporter-plugins/kafka-reporter-plugin/src/main/java/org/apache/skywalking/apm/agent/core/kafka/KafkaMeterSender.java">KafkaMeterSender</a>
 * <p>
 *
 *  <p> <a href="https://skywalking.apache.org/docs/skywalking-java/v9.4.0/en/setup/service-agent/java-agent/application-toolkit-meter/">...</a> 【application-toolkit-meter组件】</p>
 */
@OverrideImplementor(MeterSender.class)
public class MeterLocalSender extends MeterSender {
    private static final ILog LOGGER = LogManager.getLogger(MeterLocalSender.class);

    // 借鉴自Druid的JdbcDataSourceStat
    private CircularBlockingQueue<Map<String, Object>> meterDataCache;

    @Override
    public void prepare() {
        Integer configured = LogFileReporterPluginConfig.Plugin.MeterLocal.MAX_METER_DATA_SIZE;
        final Integer maxMeterDataSize = (configured != null && configured > 0) ? configured : 1000;
        this.meterDataCache = new CircularBlockingQueue<>(maxMeterDataSize);

        // 本agent脱离OAP, 所以不需要监听GRPC
        //super.prepare();
    }

    @Override
    public void boot() {
    }

    public List<Map<String, Object>> getMeterDatas() {
        // 将Collection转换为List，便于前端或调用方处理
        return new ArrayList<>(meterDataCache);
    }

    @Override
    public void send(Map<MeterId, BaseMeter> meterMap, MeterService meterService) {
        // 以下注释内容借鉴自: KafkaMeterSender.java
        // MeterDataCollection.Builder builder = MeterDataCollection.newBuilder();
        transform(meterMap, meterData -> {
            if (LOGGER.isDebugEnable()) {
                LOGGER.debug("Meter data reporting, instance: {}", meterData.getServiceInstance());
            }
            // 将MeterData转换为Map并存入meterDataCache
            meterDataCache.add(buildMeterDataMap(meterData));

            //builder.addMeterData(meterData);
        });

        //List<MeterData> meterDatas = builder.build().getMeterDataList();
    }

    private Map<String, Object> buildMeterDataMap(MeterData meterData) {
        final Map<String, Object> result = new HashMap<>();
        // 存储服务和实例信息
        result.put("service", Config.Agent.SERVICE_NAME);
        result.put("serviceInstance", Config.Agent.INSTANCE_NAME);
        result.put("timestamp", System.currentTimeMillis());

        // 存储指标类型
        result.put("type", meterData.getMetricCase().name());
        // 存储指标详细数据
        switch (meterData.getMetricCase()) {
        case SINGLEVALUE:
            result.put("singleValue", buildSingleValueMap(meterData.getSingleValue()));
            break;
        case HISTOGRAM:
            result.put("histogram", buildHistogramMap(meterData.getHistogram()));
            break;
        case METRIC_NOT_SET:
        default:
            // 其他类型暂不处理        	
        	//LOGGER.info("### Meter type: " + meterData.getMetricCase());
            break;
        }
        return result;
    }

    private Map<String, Object> buildSingleValueMap(MeterSingleValue singleValue) {
        Map<String, Object> result = new HashMap<>();
        result.put("name", singleValue.getName());
        result.put("value", singleValue.getValue());
        // 将labelsList转换为Map，便于前端处理
        result.put("labels", buildLabelsMap(singleValue.getLabelsList()));
        return result;
    }

    private Map<String, Object> buildHistogramMap(MeterHistogram histogram) {
        Map<String, Object> result = new HashMap<>();
        result.put("name", histogram.getName());
        // 将labelsList转换为Map，便于前端处理
        result.put("labels", buildLabelsMap(histogram.getLabelsList()));
        // 将buckets（valuesList）转换为List<Map>，每个bucket包含value和count
        result.put("buckets", buildBucketsList(histogram.getValuesList()));
        return result;
    }

    private Map<String, String> buildLabelsMap(List<Label> labels) {
        Map<String, String> labelsMap = new HashMap<>();
        for (Label label : labels) {
            labelsMap.put(label.getName(), label.getValue());
        }
        return labelsMap;
    }

    private List<Map<String, Object>> buildBucketsList(List<MeterBucketValue> buckets) {
        List<Map<String, Object>> bucketMaps = new ArrayList<>(buckets.size());
        for (MeterBucketValue bucket : buckets) {
            Map<String, Object> bucketMap = new HashMap<>();
            bucketMap.put("bucket", bucket.getBucket());
            bucketMap.put("count", bucket.getCount());
            bucketMap.put("isNegativeInfinity", bucket.getIsNegativeInfinity());
            bucketMaps.add(bucketMap);
        }
        return bucketMaps;
    }

    @Override
    public void onComplete() {

    }

    @Override
    public void shutdown() {

    }

    @Override
    public void statusChanged(GRPCChannelStatus status) {
        LOGGER.warn("### GRPC Disabled. Current GRPCChannelStatus is [ {} ]", status);
    }
}
