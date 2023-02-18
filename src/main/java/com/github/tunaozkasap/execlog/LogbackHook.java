package com.github.tunaozkasap.execlog;

import java.util.Iterator;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;

public class LogbackHook {
	
	private static boolean alreadyHooked = false;
	
	public static void hook() {
		if(!alreadyHooked) {
			Logger evidenceLogger = (Logger)LoggerFactory.getLogger(ExecutionEvidenceLogger.class);
			evidenceLogger.setAdditive(false);
			Iterator<Appender<ILoggingEvent>> iteratorForAppenders = evidenceLogger.iteratorForAppenders();
			if(!iteratorForAppenders.hasNext()) {
				LogbackEvidenceLayout evidenceLayout = new LogbackEvidenceLayout();
				evidenceLayout.setIncludeTimestamp(false);
				
				LayoutWrappingEncoder<ILoggingEvent> encoder = new LayoutWrappingEncoder<>();
				encoder.setLayout(evidenceLayout);
				
				ConsoleAppender<ILoggingEvent> consoleAppender = new ConsoleAppender<>();
				consoleAppender.setEncoder(encoder);
				consoleAppender.setName("console");
				consoleAppender.start();
				
				evidenceLogger.addAppender(consoleAppender);
			}
			alreadyHooked = true;
		}
	}
}
