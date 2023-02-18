package com.github.tunaozkasap.execlog;

import static com.github.tunaozkasap.execlog.ExecutionEvidenceLogger.e;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;

public class ExecutionEvidenceLoggerTest {
	
	private static ObjectMapper objectMapper = new ObjectMapper();
	private ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
	
	public void setupLogbackWithEvidenceLayout() {
		LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
		
		LogbackEvidenceLayout evidenceLayout = new LogbackEvidenceLayout();
		evidenceLayout.setIncludeTimestamp(false);
		
		LayoutWrappingEncoder<ILoggingEvent> encoder = new LayoutWrappingEncoder<>();
		encoder.setLayout(evidenceLayout);
		
        OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        appender.setOutputStream(byteArrayOutputStream);
        appender.setContext(lc);
        appender.setEncoder(encoder);
        
        Logger evidenceLogger = (Logger)LoggerFactory.getLogger(ExecutionEvidenceLogger.class);
        evidenceLogger.detachAndStopAllAppenders();
        evidenceLogger.addAppender(appender);
        evidenceLogger.setLevel(Level.INFO);
        evidenceLogger.setAdditive(false);
        
        appender.start();
	}
	
	public String getLogOutput() {
		return new String(byteArrayOutputStream.toByteArray());
	}
	
	@Test
	public void test_output_format() {
		setupLogbackWithEvidenceLayout();
		
		e().kv("userLoggedIn", true);
		e().kv("userId", 23234l);
		e().kv("userName", "Tuna");
		e().kv("userAddress", new LinkedHashMap<String, Object>() {{put("streetName", "Norrebrogade 23");put("postCode", 232l);}});
		e().kv("userDetails", objectMapper.createObjectNode().put("userAccount", "234234"));
		
		e().logNow();
		Assertions.assertEquals("{\"thread\":\"main\",\"logger\":\"com.github.tunaozkasap.execlog.ExecutionEvidenceLogger\",\"evidence\":{\"userLoggedIn\":true,\"userId\":23234,\"userName\":\"Tuna\",\"userAddress\":{\"streetName\":\"Norrebrogade 23\",\"postCode\":232},\"userDetails\":{\"userAccount\":\"234234\"}},\"context\":\"default\"}", this.getLogOutput());
	}
	
}
