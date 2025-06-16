cd E:\gitRepository\_skywalking-javaPluginExtensions\agent
mvn clean package '-Dmaven.test.skip=true' -T 2C -pl logfile-reporter-plugin -am
cp ./logfile-reporter-plugin/target/logfile-reporter-plugin-1.0.0.jar D:\apps\apache-skywalking-java-agent-8.16.0\plugins\logfile-reporter-plugin-1.0.0.jar
ls D:\apps\apache-skywalking-java-agent-8.16.0\plugins\ | findstr logfile-reporter-plugin-