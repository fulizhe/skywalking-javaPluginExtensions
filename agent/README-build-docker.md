
# 简介

使用容器化环境进行jar编译，目的是为了灵活设置使用JDK版本。


# 实现
```shell

# 1. 编译
cd D:\gitRepository\skywalking-javaPluginExtensions\agent

docker run --rm -v //C/Users/lqzkc/.m2/repository:/usr/maven/repository -v ${PWD}:/app  -v ${PWD}/settings.xml:/root/.m2/settings.xml -w /app maven:3.8.6-jdk-8 mvn  -s /root/.m2/settings.xml "-Dmaven.repo.local=/usr/maven/repository/" clean package -pl logfile-reporter-plugin -am -DskipTests


# 2. 拷贝
cp ./logfile-reporter-plugin/target/logfile-reporter-plugin-1.0.0.jar D:\apps\apache-skywalking-java-agent-9.4.0\plugins\logfile-reporter-plugin-1.0.0.jar
ls D:\apps\apache-skywalking-java-agent-9.4.0\plugins\ | findstr logfile-reporter-plugin-

```