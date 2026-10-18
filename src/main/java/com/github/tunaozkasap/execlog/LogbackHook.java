package com.github.tunaozkasap.execlog;

import java.util.Iterator;

import org.slf4j.LoggerFactory;

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
			Logger evidenceLogger = (Logger)LoggerFactory.getLogger(ExecutionEvidenceLogger.class);
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
