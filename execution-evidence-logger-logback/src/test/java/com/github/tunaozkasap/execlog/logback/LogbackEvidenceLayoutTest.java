package com.github.tunaozkasap.execlog.logback;

import static com.github.tunaozkasap.execlog.ExecutionEvidenceLogger.e;

import java.io.ByteArrayOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.github.tunaozkasap.execlog.ExecutionEvidenceLogger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;

public class LogbackEvidenceLayoutTest {

	private static final String LOGGER_NAME = "com.github.tunaozkasap.execlog.ExecutionEvidenceLogger";

	private ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

	@AfterEach
	public void cleanUp() {
		MDC.clear();
		ExecutionEvidenceLogger.clear();
	}

	/**
	 * Same as setupLogbackWithEvidenceLayout() in {@link LogbackEvidenceWriterTest} with timestamps off,
	 * the returned layout can be configured further before logging.
	 */
	public LogbackEvidenceLayout setupLogbackWithEvidenceLayout() {
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
		return evidenceLayout;
	}

	public String getLogOutput() {
		return new String(byteArrayOutputStream.toByteArray());
	}

	private String layoutEventAt(LogbackEvidenceLayout layout, long timestamp) {
		Logger evidenceLogger = (Logger)LoggerFactory.getLogger(ExecutionEvidenceLogger.class);
		LoggingEvent event = new LoggingEvent(Logger.class.getName(), evidenceLogger, Level.INFO, "hello", null, null);
		event.setTimeStamp(timestamp);
		return layout.doLayout(event);
	}

	@Test
	public void test_timestamp_without_format_is_epoch_millis() {
		LogbackEvidenceLayout layout = new LogbackEvidenceLayout();
		layout.setIncludeThreadName(false);

		Assertions.assertEquals("{\"timestamp\":\"1700000000123\",\"logger\":\"" + LOGGER_NAME + "\",\"message\":\"hello\",\"context\":\"default\"}", layoutEventAt(layout, 1700000000123L));
	}

	@Test
	public void test_timestamp_format_with_timezone() {
		LogbackEvidenceLayout layout = new LogbackEvidenceLayout();
		layout.setIncludeThreadName(false);
		layout.setTimestampFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

		layout.setTimestampFormatTimezoneId("UTC");
		Assertions.assertEquals("{\"timestamp\":\"2023-11-14T22:13:20.123Z\",\"logger\":\"" + LOGGER_NAME + "\",\"message\":\"hello\",\"context\":\"default\"}", layoutEventAt(layout, 1700000000123L));

		layout.setTimestampFormatTimezoneId("Asia/Tokyo");
		Assertions.assertEquals("{\"timestamp\":\"2023-11-15T07:13:20.123+09:00\",\"logger\":\"" + LOGGER_NAME + "\",\"message\":\"hello\",\"context\":\"default\"}", layoutEventAt(layout, 1700000000123L));
	}

	@Test
	public void test_timestamp_format_on_logged_evidence() {
		LogbackEvidenceLayout layout = setupLogbackWithEvidenceLayout();
		layout.setIncludeTimestamp(true);
		layout.setTimestampFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
		layout.setTimestampFormatTimezoneId("UTC");

		e().kv("userId", 42);
		e().logNow();

		String output = this.getLogOutput();
		Assertions.assertTrue(output.matches("\\{\"timestamp\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z\",\"thread\":\"main\",\"logger\":\"" + LOGGER_NAME + "\",\"evidence\":\\{\"userId\":42\\},\"context\":\"default\"\\}"), output);
	}

	@Test
	public void test_mdc_is_included() {
		setupLogbackWithEvidenceLayout();
		MDC.put("requestId", "req-1");

		e().kv("userId", 42);
		e().logNow();

		Assertions.assertEquals("{\"thread\":\"main\",\"mdc\":{\"requestId\":\"req-1\"},\"logger\":\"" + LOGGER_NAME + "\",\"evidence\":{\"userId\":42},\"context\":\"default\"}", this.getLogOutput());
	}

	@Test
	public void test_mdc_can_be_excluded() {
		setupLogbackWithEvidenceLayout().setIncludeMDC(false);
		MDC.put("requestId", "req-1");

		e().kv("userId", 42);
		e().logNow();

		Assertions.assertEquals("{\"thread\":\"main\",\"logger\":\"" + LOGGER_NAME + "\",\"evidence\":{\"userId\":42},\"context\":\"default\"}", this.getLogOutput());
	}

	@Test
	public void test_thread_logger_and_context_names_can_be_excluded() {
		LogbackEvidenceLayout layout = setupLogbackWithEvidenceLayout();

		layout.setIncludeThreadName(false);
		e().kv("userId", 42);
		e().logNow();
		Assertions.assertEquals("{\"logger\":\"" + LOGGER_NAME + "\",\"evidence\":{\"userId\":42},\"context\":\"default\"}", this.getLogOutput());

		byteArrayOutputStream.reset();
		layout.setIncludeLoggerName(false);
		e().kv("userId", 42);
		e().logNow();
		Assertions.assertEquals("{\"evidence\":{\"userId\":42},\"context\":\"default\"}", this.getLogOutput());

		byteArrayOutputStream.reset();
		layout.setIncludeContextName(false);
		e().kv("userId", 42);
		e().logNow();
		Assertions.assertEquals("{\"evidence\":{\"userId\":42}}", this.getLogOutput());
	}

	@Test
	public void test_append_line_separator() {
		setupLogbackWithEvidenceLayout().setAppendLineSeparator(true);

		e().kv("userId", 42);
		e().logNow();
		e().kv("userId", 43);
		e().logNow();

		String lineSeparator = System.lineSeparator();
		Assertions.assertEquals("{\"thread\":\"main\",\"logger\":\"" + LOGGER_NAME + "\",\"evidence\":{\"userId\":42},\"context\":\"default\"}" + lineSeparator
				+ "{\"thread\":\"main\",\"logger\":\"" + LOGGER_NAME + "\",\"evidence\":{\"userId\":43},\"context\":\"default\"}" + lineSeparator, this.getLogOutput());
	}

	@Test
	public void test_formatted_message_can_be_excluded_on_non_evidence_event() {
		setupLogbackWithEvidenceLayout().setIncludeFormattedMessage(false);

		LoggerFactory.getLogger(ExecutionEvidenceLogger.class).info("Not an evidence line for {}", "someone");

		Assertions.assertEquals("{\"thread\":\"main\",\"logger\":\"" + LOGGER_NAME + "\",\"context\":\"default\"}", this.getLogOutput());
	}

	@Test
	public void test_formatted_message_is_included_on_non_evidence_event() {
		setupLogbackWithEvidenceLayout();

		LoggerFactory.getLogger(ExecutionEvidenceLogger.class).info("Not an evidence line for {}", "someone");

		Assertions.assertEquals("{\"thread\":\"main\",\"logger\":\"" + LOGGER_NAME + "\",\"message\":\"Not an evidence line for someone\",\"context\":\"default\"}", this.getLogOutput());
	}

	@Test
	public void test_facts_are_logged_inside_evidence() {
		setupLogbackWithEvidenceLayout();

		e().kv("userId", 42);
		e().f("payment").kv("status", "success").sf("card").kv("brand", "visa");
		e().logNow();

		Assertions.assertEquals("{\"thread\":\"main\",\"logger\":\"" + LOGGER_NAME + "\",\"evidence\":{\"userId\":42,\"payment\":{\"status\":\"success\",\"card\":{\"brand\":\"visa\"}}},\"context\":\"default\"}", this.getLogOutput());
	}

}
