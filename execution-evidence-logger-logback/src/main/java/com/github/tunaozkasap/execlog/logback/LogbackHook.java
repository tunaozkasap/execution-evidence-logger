package com.github.tunaozkasap.execlog.logback;

import java.util.Iterator;

import org.slf4j.LoggerFactory;

import com.github.tunaozkasap.execlog.ExecutionEvidenceLogger;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;

public class LogbackHook {
	
	private static volatile boolean alreadyHooked = false;

	public static void hook() {
		if(alreadyHooked) {
			return;
		}
		synchronized(LogbackHook.class) {
			if(alreadyHooked) {
				return;
			}
			// SLF4J may be bound to another framework even though logback is on the classpath
			if(!(LoggerFactory.getLogger(ExecutionEvidenceLogger.class) instanceof Logger evidenceLogger)) {
				alreadyHooked = true;
				return;
			}
			evidenceLogger.setAdditive(false);
			Iterator<Appender<ILoggingEvent>> iteratorForAppenders = evidenceLogger.iteratorForAppenders();
			if(!iteratorForAppenders.hasNext()) {
				LoggerContext context = evidenceLogger.getLoggerContext();

				LogbackEvidenceLayout evidenceLayout = new LogbackEvidenceLayout();
				evidenceLayout.setIncludeTimestamp(false);
				evidenceLayout.setContext(context);
				evidenceLayout.start();

				LayoutWrappingEncoder<ILoggingEvent> encoder = new LayoutWrappingEncoder<>();
				encoder.setLayout(evidenceLayout);
				encoder.setContext(context);
				encoder.start();

				ConsoleAppender<ILoggingEvent> consoleAppender = new ConsoleAppender<>();
				consoleAppender.setEncoder(encoder);
				consoleAppender.setName("console");
				consoleAppender.setContext(context);
				consoleAppender.start();

				evidenceLogger.addAppender(consoleAppender);
			}
			alreadyHooked = true;
		}
	}
}
