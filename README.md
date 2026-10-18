# Execution Evidence Logger

Purpose of this library is twofold;
1. To gather facts (in memory) that will create an evidence object and to log that evidence as a JSON as a log line.
2. Facts are key-value pairs that can be easily used to analyze log data, as a result this library aims to reduce text logging which is hard to analyze.

# Modules

| Module | Purpose |
| --- | --- |
| `execution-evidence-logger-core` | Gathers the evidence and writes it. Without any extension the JSON is printed to the console with `System.out.println`. No logging framework dependency. |
| `execution-evidence-logger-logback` | Writes the evidence through logback, using `LogbackEvidenceLayout`. |

More extensions for other frameworks can be added the same way, see [Writing an extension](#writing-an-extension).

# Use the core module

```
<dependency>
    <groupId>com.github.tunaozkasap</groupId>
    <artifactId>execution-evidence-logger-core</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

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
`logNow()` writes the evidence and clears it from the current thread, so pooled threads (e.g. request threads) start the next execution with an empty evidence. With only the core module on the classpath the evidence JSON is printed to the console.

If you don't want to log the evidence, `e().toString()` gives you the evidence as JSON string and `e().toObjectNode()` as JSON ObjectNode. In that case call `ExecutionEvidenceLogger.clear()` at the end of the execution yourself.

You can also send the evidence anywhere by setting a writer explicitly:

```
ExecutionEvidenceLogger.setWriter(evidence -> myOutput.send(evidence.toString()));
```

# Use the logback extension

Add the logback module next to logback-classic:

```
<dependency>
    <groupId>com.github.tunaozkasap</groupId>
    <artifactId>execution-evidence-logger-logback</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

Having the module on the classpath is enough, it is picked up automatically. Evidence is logged through the logger named "com.github.tunaozkasap.execlog.ExecutionEvidenceLogger".

## Without logback configuration

When you did not configure a logger named "com.github.tunaozkasap.execlog.ExecutionEvidenceLogger" in your logback configuration then the extension will;
1.  Create a logger named "com.github.tunaozkasap.execlog.ExecutionEvidenceLogger"
2.  Register ConsoleAppender to this logger using layout LogbackEvidenceLayout

As a consequence, whenever you call logNow() JSON output will be written to console.

## With logback configuration

When you configured a logger named "com.github.tunaozkasap.execlog.ExecutionEvidenceLogger" then the configured logger in your configuration will be used. Sample configuration is depicted below;

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
	         <layout class="com.github.tunaozkasap.execlog.logback.LogbackEvidenceLayout">
	             <includeThreadName>true</includeThreadName>
	         </layout>
	    </encoder>
    </appender>
    
    <logger name="com.github.tunaozkasap.execlog.ExecutionEvidenceLogger" level="INFO" additivity="false" > 
        <appender-ref ref="FILE" />
    </logger>

</configuration>
```

In consequence, when you call logNow() then the evidence JSON will be output into the configured appenders.

> ⚠️ Upgrading from the single module version: `LogbackEvidenceLayout` moved to the package `com.github.tunaozkasap.execlog.logback`, update the layout class in your logback configuration.

# Writing an extension

1. Create a module depending on `execution-evidence-logger-core`.
2. Implement `com.github.tunaozkasap.execlog.EvidenceWriter`. Return a `priority()` higher than 0 so it is preferred when several writers are on the classpath (the logback writer uses 100).
3. Register it in `src/main/resources/META-INF/services/com.github.tunaozkasap.execlog.EvidenceWriter`.

When no writer is registered, or every registered writer fails to load, the console writer is used.

# Build

```
mvn verify
```

> ⚠️ This project requires Java 21 (LTS) to build and run. Make sure your JAVA_HOME or toolchain is set to a JDK 21 distribution.
