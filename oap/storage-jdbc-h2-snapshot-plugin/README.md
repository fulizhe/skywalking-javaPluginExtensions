

# 相关配置
```yaml

# application.yaml
core:
  default:    
    dataKeeperExecutePeriod: ${SW_CORE_DATA_KEEPER_EXECUTE_PERIOD:5} # How often the data keeper executor runs periodically, unit is minute  ;; 执行过期数据回收的周期, 在 DataTTLKeeperTimer 中使用
    recordDataTTL: ${SW_CORE_RECORD_DATA_TTL:3} # Unit is day   # 数据保存周期, 默认保存3天内的数据   
    

```

# 打包
```
cd E:\gitRepository\_skywalking-javaPluginExtensions\oap\storage-jdbc-h2-snapshot-plugin
mvn clean package -T 2C '-Dmaven.test.skip=true'

cp ./target/storage-jdbc-h2-snapshot-plugin-8.4.0.jar D:\apps\apache-skywalking-apm-8.8.1\oap-libs\storage-jdbc-h2-snapshot-plugin-8.4.0.jar
ls D:\apps\apache-skywalking-apm-8.8.1\oap-libs\ | findstr storage-jdbc-h2-snapshot
```

# 拷贝到对应目录下
cp  .\target\storage-jdbc-h2-snapshot-plugin-8.4.0.jar D:\apps\apache-skywalking-apm-8.8.1\oap-libs\storage-jdbc-h2-snapshot-plugin-8.4.0.jar


# 配置项
D:\apps\apache-skywalking-apm-8.8.1\config\application.yml
```yaml
storage:
  selector: ${SW_STORAGE:h2-napshot}
  h2-napshot:
    driver: ${SW_STORAGE_H2_DRIVER:org.h2.jdbcx.JdbcDataSource}
    url: ${SW_STORAGE_H2_URL:jdbc:h2:mem:skywalking-oap-db;DB_CLOSE_DELAY=-1}
    user: ${SW_STORAGE_H2_USER:sa}
    metadataQueryMaxSize: ${SW_STORAGE_H2_QUERY_MAX_SIZE:5000}
    maxSizeOfArrayColumn: ${SW_STORAGE_MAX_SIZE_OF_ARRAY_COLUMN:20}
    numOfSearchableValuesPerTag: ${SW_STORAGE_NUM_OF_SEARCHABLE_VALUES_PER_TAG:2}
    maxSizeOfBatchSql: ${SW_STORAGE_MAX_SIZE_OF_BATCH_SQL:100}
    asyncBatchPersistentPoolSize: ${SW_STORAGE_ASYNC_BATCH_PERSISTENT_POOL_SIZE:1}
    # 自定义配置项, 保存多长时间范围内的数据, 默认为6分钟. 因为负责过期数据回收的定时器, 其执行周期为五分钟一次
    recordDataTTL: 6    # custom config. unit is minute. because the 'dataKeeperExecutePeriod' is 5.
```