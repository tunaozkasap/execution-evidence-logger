package com.github.tunaozkasap.execlog.logback;

import static com.github.tunaozkasap.execlog.ExecutionEvidenceLogger.e;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tunaozkasap.execlog.ExecutionEvidenceLogger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;

public class LogbackEvidenceWriterTest {

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

	@Test
	public void test_evidence_is_cleared_after_log_now() {
		setupLogbackWithEvidenceLayout();

		e().kv("firstExecution", true);
		e().logNow();
		Assertions.assertEquals("{}", e().toString());

		byteArrayOutputStream.reset();
		e().kv("secondExecution", true);
		e().logNow();
		Assertions.assertEquals("{\"thread\":\"main\",\"logger\":\"com.github.tunaozkasap.execlog.ExecutionEvidenceLogger\",\"evidence\":{\"secondExecution\":true},\"context\":\"default\"}", this.getLogOutput());
	}

	@Test
	public void test_non_evidence_event_is_logged_with_message() {
		setupLogbackWithEvidenceLayout();

		LoggerFactory.getLogger(ExecutionEvidenceLogger.class).error("Failed calling logback hook", new IllegalStateException("boom"));

		String output = this.getLogOutput();
		Assertions.assertTrue(output.startsWith("{\"thread\":\"main\",\"logger\":\"com.github.tunaozkasap.execlog.ExecutionEvidenceLogger\",\"message\":\"Failed calling logback hook\",\"context\":\"default\",\"exception\":\"java.lang.IllegalStateException: boom"), output);
		Assertions.assertFalse(output.contains("\"evidence\""), output);
	}

	@Test
	public void test_pooled_thread_starts_with_empty_evidence_after_log_now() throws Exception {
		setupLogbackWithEvidenceLayout();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			String firstTaskThread = executor.submit(() -> {
				e().kv("firstTask", "secret");
				e().logNow();
				return Thread.currentThread().getName();
			}).get();

			byteArrayOutputStream.reset();
			String[] secondTask = executor.submit(() -> {
				String evidenceAtStart = e().toString();
				e().kv("secondTask", true);
				e().logNow();
				return new String[] {Thread.currentThread().getName(), evidenceAtStart};
			}).get();

			Assertions.assertEquals(firstTaskThread, secondTask[0], "both tasks must run on the same pooled thread");
			Assertions.assertEquals("{}", secondTask[1]);
			String output = this.getLogOutput();
			Assertions.assertTrue(output.contains("\"evidence\":{\"secondTask\":true}"), output);
			Assertions.assertFalse(output.contains("firstTask"), output);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	public void test_concurrent_hook_registers_one_appender() throws Exception {
		int threadCount = 16;
		Logger evidenceLogger = (Logger)LoggerFactory.getLogger(ExecutionEvidenceLogger.class);
		Field alreadyHooked = LogbackHook.class.getDeclaredField("alreadyHooked");
		alreadyHooked.setAccessible(true);
		CyclicBarrier startTogether = new CyclicBarrier(threadCount);
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		try {
			// Repeat to give a race a fair chance to show up
			for (int round = 0; round < 50; round++) {
				evidenceLogger.detachAndStopAllAppenders();
				alreadyHooked.set(null, false);

				List<Future<?>> hooks = new ArrayList<>();
				for (int i = 0; i < threadCount; i++) {
					hooks.add(executor.submit(() -> {
						startTogether.await();
						LogbackHook.hook();
						return null;
					}));
				}
				for (Future<?> hook : hooks) {
					hook.get();
				}

				int appenderCount = 0;
				for (Iterator<Appender<ILoggingEvent>> it = evidenceLogger.iteratorForAppenders(); it.hasNext(); it.next()) {
					appenderCount++;
				}
				Assertions.assertEquals(1, appenderCount, "round " + round);
			}
		} finally {
			executor.shutdownNow();
			evidenceLogger.detachAndStopAllAppenders();
		}
	}
}
