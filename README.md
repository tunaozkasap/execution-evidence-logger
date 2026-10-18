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
    <groupId>io.github.tunaozkasap</groupId>
    <artifactId>execution-evidence-logger-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

`ScopedEvidence` is the only class you need. `s()` gives the evidence of the current thread:

```
import static com.github.tunaozkasap.execlog.ScopedEvidence.s;
...
public class UserManagementService {
    ...
    public void createUser(){
        s().f("userValidation").kv("status", "success").kv("userId", 232323);
        ...
        s().logNow();
    }
}
```
`logNow()` writes the evidence and clears it from the current thread, so pooled threads (e.g. request threads) start the next execution with an empty evidence. With only the core module on the classpath the evidence JSON is printed to the console.

If you don't want to log the evidence, `s().toString()` gives you the evidence as JSON string and `s().toObjectNode()` as JSON ObjectNode. In that case call `ScopedEvidence.clear()` at the end of the execution yourself.

You can also send the evidence anywhere by setting a writer explicitly:

```
EvidenceWriters.setWriter(evidence -> myOutput.send(evidence.toString()));
```

# Scope facts to the call stack

You can open a fact as the *parent* for everything written further down the call stack. Methods write through `s()` without knowing which fact they belong to; parents opened inside a parent become its sub-facts, so the evidence follows the call tree.

> ⚠️ **Always open a parent with try-with-resources.** The parent is then closed when the block ends, also when an exception is thrown. A parent that is never closed keeps collecting everything written afterwards in that execution.

```
import static com.github.tunaozkasap.execlog.ScopedEvidence.s;
...
public class OrderController {
    public void placeOrder(Order order) {
        s().kv("orderId", order.getId());              // no parent open -> evidence root

        try (var payment = s().parent("payment")) {    // "payment" is the root inside this block
            paymentService.charge(order);
        }                                              // closed here, even if charge() throws

        s().kv("status", "placed");                    // evidence root again
        s().logNow();
    }
}

public class PaymentService {
    public void charge(Order order) {
        s().kv("amount", order.getTotal());            // -> payment.amount

        try (var fraudCheck = s().parent("fraudCheck")) {
            s().kv("score", fraudClient.score(order)); // -> payment.fraudCheck.score
        }
    }
}
```

Logged evidence:

```
{"orderId":42,"payment":{"amount":99.5,"fraudCheck":{"score":0.12}},"status":"placed"}
```

The same method writes wherever it is called:

```
auditService.record();                      // s().kv("audited", true) -> {"audited":true}

try (var refund = s().parent("refund")) {
    auditService.record();                  // -> {"refund":{"audited":true}}
}
```

To write to the evidence root while a parent is open, use `root()`:

```
try (var payment = s().parent("payment")) {
    s().kv("amount", 10);                   // -> payment.amount
    s().root().kv("userId", 7);             // -> userId
}
```

Rules:
- Opening a parent with a key that already exists under the current parent reuses that fact, it never creates a list.
- `s().f("card")` creates a fact under the current parent without making it a parent.
- After `logNow()` or `ScopedEvidence.clear()` the next execution starts with an empty evidence and no parent open, so a forgotten parent never leaks into the next request on a pooled thread.
- Evidence and parents are per thread.

If try-with-resources can't be used, `pushParent`/`popParent` do the same job, but the pop must be in a `finally` block:

```
s().pushParent("payment");
try {
    paymentService.charge(order);
} finally {
    s().popParent("payment");
}
```

Closing an outer parent also closes inner parents that were left open, with a warning on `System.err`.

# Use the logback extension

Add the logback module next to logback-classic:

```
<dependency>
    <groupId>io.github.tunaozkasap</groupId>
    <artifactId>execution-evidence-logger-logback</artifactId>
    <version>1.0.0</version>
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

> ⚠️ Upgrading from the single module version:
> - `ExecutionEvidenceLogger.e()` is replaced by `ScopedEvidence.s()`; `e().kv(...)` inside an open parent becomes `s().root().kv(...)`.
> - `ExecutionEvidenceLogger.clear()` is now `ScopedEvidence.clear()`, and `setWriter(...)` is on `EvidenceWriters`.
> - `LogbackEvidenceLayout` moved to the package `com.github.tunaozkasap.execlog.logback`, update the layout class in your logback configuration. The logger name `com.github.tunaozkasap.execlog.ExecutionEvidenceLogger` is unchanged.

# Writing an extension

1. Create a module depending on `execution-evidence-logger-core`.
2. Implement `com.github.tunaozkasap.execlog.EvidenceWriter`. Return a `priority()` higher than 0 so it is preferred when several writers are on the classpath (the logback writer uses 100).
3. Register it in `src/main/resources/META-INF/services/com.github.tunaozkasap.execlog.EvidenceWriter`.

When no writer is registered, or every registered writer fails to load, the console writer is used.

# Build

```
mvn verify
```

## Release to Maven Central

Prerequisites: a `central` server with your Central Portal user token in `~/.m2/settings.xml`, and a GPG key whose public key is on a key server.

1. Set a release version (no `-SNAPSHOT`) in all POMs.
2. Run `mvn -P ci-cd clean deploy`. It signs the artifacts, uploads them to the Central Portal and waits for validation.
3. Press **Publish** on the deployment in the Central Portal (central.sonatype.com).

> ⚠️ This project requires Java 21 (LTS) to build and run. Make sure your JAVA_HOME or toolchain is set to a JDK 21 distribution.
