# Execution Evidence Logger

Purpose of this library is twofold;
1. To gather facts (in memory) that will create an evidence object and to log that evidence as a JSON as a log line.
2. Facts are key-value pairs that can be easily used to analyze log data, as a result this library aims to reduce text logging which is hard to analyze.

# Use the logger standalone

If you want to use the logger standalone, then you can just to the following.

```
import static com.github.tunaozkasap.execlog.ExecutionEvidenceLogger.e;
...
public class UserManagementService {
    ...
    public void createUser(){
        e().f("userValidation").kv("status", "success").kv("userId", 232323);
        ...
        e().toString();
    }
}
```
Here toString method of ExecutionEvidenceLogger will give you the evidence as JSON string. Alternatively you can call toObjectNode method of ExecutionEvidenceLogger and that will give you the evidence as JSON ObjectNode.

# Use the logger with logback

If you want to use the logger with logback, then you should have logback-classic artifact in your classpath.
There are two ways of making it work with logback

## Have logback-classic artifact in your classpath

When you have logback-classic artifact on your classpath and you did not configure a logger named "com.github.tunaozkasap.execlog.ExecutionEvidenceLogger" in your logback configuration then ExecutionEvidenceLogger will; 
1.  Create a logger named "com.github.tunaozkasap.execlog.ExecutionEvidenceLogger"
2.  Register ConsoleAppender to this logger using layout LogbackEvidenceLayout

As a consequence, whenever you call ExecutionEvidenceLogger.logNow() JSON output will be written to console.

## Have logback-classic artifact in your classpath and configure logback

When you have logback-classic artifact on your classpath and you configured a logger named "com.github.tunaozkasap.execlog.ExecutionEvidenceLogger" then the configured logger in your configuration will be used. Sample configuration is depicted below;

```
<configuration debug="true">
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <root level="debug">
        <appender-ref ref="STDOUT" />
    </root>

    <appender name="FILE" class="ch.qos.logback.core.FileAppender">
        <file>tests.log</file>
        <append>true</append>
        <encoder class="ch.qos.logback.core.encoder.LayoutWrappingEncoder">
	         <layout class="com.github.tunaozkasap.execlog.LogbackEvidenceLayout">
	             <includeThreadName>true</includeThreadName>
	         </layout>
	    </encoder>
    </appender>
    
    <logger name="com.github.tunaozkasap.execlog.ExecutionEvidenceLogger" level="INFO" additivity="false" > 
        <appender-ref ref="FILE" />
    </logger>

</configuration>
```


In consequence, when you call ExecutionEvidenceLogger.logNow() then the evidence JSON will be output into the configured appenders.

```
import static com.github.tunaozkasap.execlog.ExecutionEvidenceLogger.e;
...
public class UserManagementService {
    ...
    public void createUser(){
        e().f("userValidation").kv("status", "success").kv("userId", 232323);
        ...
        e().logNow();
    }
}
```