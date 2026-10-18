package com.github.tunaozkasap.execlog.logback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tunaozkasap.execlog.EvidenceWriter;
import com.github.tunaozkasap.execlog.ExecutionEvidenceLogger;

/**
 * Writes the evidence through the logback logger named {@code com.github.tunaozkasap.execlog.ExecutionEvidenceLogger}.
 * When that logger has no appender configured, a console appender using {@link LogbackEvidenceLayout} is registered,
 * see {@link LogbackHook}. Registered via {@code META-INF/services}, so having this module on the classpath is enough.
 */
public class LogbackEvidenceWriter implements EvidenceWriter {

	public static final int PRIORITY = 100;

	// Logger name kept as the core class name, so existing logback configurations keep working
	private static final Logger log = LoggerFactory.getLogger(ExecutionEvidenceLogger.class);

	@Override
	public void write(ObjectNode evidence) {
		LogbackHook.hook();
		// LogbackEvidenceLayout takes the evidence from the argument; other layouts show it in the message
		log.info("{}", evidence);
	}

	@Override
	public int priority() {
		return PRIORITY;
	}
}
