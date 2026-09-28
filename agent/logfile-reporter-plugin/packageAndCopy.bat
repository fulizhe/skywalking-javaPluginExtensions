cd E:\gitRepository\_skywalking-javaPluginExtensions\agent
call mvn clean package -Dmaven.test.skip=true -T 2C -pl logfile-reporter-plugin -am
if errorlevel 1 exit /b 1
del /f /q D:\apps\apache-skywalking-java-agent-9.4.0\plugins\logfile-reporter*.jar
for %%f in (.\logfile-reporter-plugin\target\logfile-reporter-plugin-*.jar) do copy /y "%%~ff" D:\apps\apache-skywalking-java-agent-9.4.0\plugins\
dir D:\apps\apache-skywalking-java-agent-9.4.0\plugins\ | findstr logfile-reporter-plugin-
