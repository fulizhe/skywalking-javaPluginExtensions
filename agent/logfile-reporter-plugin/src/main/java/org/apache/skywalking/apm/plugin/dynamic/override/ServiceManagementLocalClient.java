package org.apache.skywalking.apm.plugin.dynamic.override;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.skywalking.apm.agent.core.boot.BootService;
import org.apache.skywalking.apm.agent.core.boot.OverrideImplementor;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.jvm.LoadedLibraryCollector;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.os.OSUtil;
import org.apache.skywalking.apm.agent.core.remote.GRPCChannelStatus;
import org.apache.skywalking.apm.agent.core.remote.ServiceManagementClient;
import org.apache.skywalking.apm.agent.core.util.InstanceJsonPropertiesUtil;
import org.apache.skywalking.apm.network.common.v3.KeyStringValuePair;
import org.apache.skywalking.apm.network.management.v3.InstanceProperties;

/**
 * <p>
 *
 * <p>
 * <p>
 * Refer To {@code DisableJVMService}
 * {@link  <a href="https://github.com/apache/skywalking-java/blob/main/apm-sniffer/optional-reporter-plugins/kafka-reporter-plugin/src/main/java/org/apache/skywalking/apm/agent/core/kafka/KafkaServiceManagementServiceClient.java">...</a>}
 * <p>
 */
@OverrideImplementor(ServiceManagementClient.class)
public class ServiceManagementLocalClient extends ServiceManagementClient implements BootService {
    private static final ILog LOGGER = LogManager.getLogger(ServiceManagementLocalClient.class);

    private static List<KeyStringValuePair> SERVICE_INSTANCE_PROPERTIES_2;

    @Override
    public void statusChanged(GRPCChannelStatus status) {
        LOGGER.info("### ServiceManagementLocalClient status changed, status: {}.", status);
        //super.statusChanged(status);
    }

    @Override
    public void prepare() {

        SERVICE_INSTANCE_PROPERTIES_2 = InstanceJsonPropertiesUtil.parseProperties();

        LOGGER.debug("ServiceManagementLocalClient running. do not super 【prepare】 method");
        // super.prepare();
    }

    @Override
    public void boot() {
        LOGGER.debug("ServiceManagementLocalClient running. do not super 【boot】 method");
        //super.boot();
    }

    public void onComplete() {
        LOGGER.debug("ServiceManagementLocalClient running. do not super 【onComplete】 method");
        //super.onComplete();
    }

    @Override
    public void shutdown() {
        LOGGER.debug("ServiceManagementLocalClient running. do not super 【shutdown】 method");
        // super.shutdown();
    }


    public Object getInstanceProperties() {
        InstanceProperties instanceProperties = InstanceProperties.newBuilder().setService(Config.Agent.SERVICE_NAME) //
                .setServiceInstance(Config.Agent.INSTANCE_NAME)//
                .addAllProperties(OSUtil.buildOSInfo(Config.OsInfo.IPV4_LIST_SIZE))//
                .addAllProperties(SERVICE_INSTANCE_PROPERTIES_2)//
                .addAllProperties(LoadedLibraryCollector.buildJVMInfo()) //
                .build();

        // 将 instanceProperties 中的典型键值对（properties 字段）转换为 Map<String, String>
        // 这样可以更方便地进行后续处理，而不是使用自定义类型
        Map<String, String> propertiesMap = new HashMap<>();
        for (KeyStringValuePair pair : instanceProperties.getPropertiesList()) {
            propertiesMap.put(pair.getKey(), pair.getValue());
        }

        propertiesMap.put("_service", instanceProperties.getService());
        propertiesMap.put("_serviceInstance", instanceProperties.getServiceInstance());
        return propertiesMap;
    }

    @Override
    public void run() {
        LOGGER.debug("ServiceManagementLocalClient running. do not super 【run】 method");
    }


}