package com.github.tunaozkasap.execlog;

import static com.github.tunaozkasap.execlog.ExecutionEvidenceLogger.e;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ExecutionEvidenceLoggerTest {

	private final List<String> written = Collections.synchronizedList(new ArrayList<>());

	private void captureWrites() {
		ExecutionEvidenceLogger.setWriter(evidence -> written.add(evidence.toString()));
	}

	@AfterEach
	public void resetWriter() {
		ExecutionEvidenceLogger.setWriter(null);
		ExecutionEvidenceLogger.clear();
	}

	@Test
	public void test_console_writer_is_used_without_extensions() {
		Assertions.assertInstanceOf(ConsoleEvidenceWriter.class, ExecutionEvidenceLogger.discoverWriter());
	}

	@Test
	public void test_log_now_prints_evidence_json_to_console_by_default() {
		ByteArrayOutputStream console = new ByteArrayOutputStream();
		PrintStream originalOut = System.out;
		System.setOut(new PrintStream(console, true));
		try {
			e().kv("userId", 23234l);
			e().f("userValidation").kv("status", "success");
			e().logNow();
		} finally {
			System.setOut(originalOut);
		}
		Assertions.assertEquals("{\"userId\":23234,\"userValidation\":{\"status\":\"success\"}}" + System.lineSeparator(), console.toString());
	}

	@Test
	public void test_explicit_writer_receives_evidence() {
		captureWrites();

		e().kv("userLoggedIn", true);
		e().logNow();

		Assertions.assertEquals(List.of("{\"userLoggedIn\":true}"), written);
	}

	@Test
	public void test_evidence_is_cleared_after_log_now() {
		captureWrites();

		e().kv("firstExecution", true);
		e().logNow();
		Assertions.assertEquals("{}", e().toString());

		e().kv("secondExecution", true);
		e().logNow();
		Assertions.assertEquals(List.of("{\"firstExecution\":true}", "{\"secondExecution\":true}"), written);
	}

	@Test
	public void test_failing_writer_does_not_break_execution_and_evidence_is_cleared() {
		ExecutionEvidenceLogger.setWriter(evidence -> {
			throw new IllegalStateException("writer down");
		});
		PrintStream originalErr = System.err;
		System.setErr(new PrintStream(new ByteArrayOutputStream(), true));
		try {
			e().kv("userId", 1);
			Assertions.assertDoesNotThrow(() -> e().logNow());
		} finally {
			System.setErr(originalErr);
		}
		Assertions.assertEquals("{}", e().toString());
	}

	@Test
	public void test_clear_discards_evidence() {
		e().f("someFact").kv("status", "success");
		ExecutionEvidenceLogger.clear();
		Assertions.assertEquals("{}", e().toString());
	}

	@Test
	public void test_threads_do_not_share_evidence() throws Exception {
		int threadCount = 8;
		int iterations = 1000;
		CyclicBarrier startTogether = new CyclicBarrier(threadCount);
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		try {
			List<Future<String>> results = new ArrayList<>();
			for (int i = 0; i < threadCount; i++) {
				int threadNo = i;
				Callable<String> buildEvidence = () -> {
					startTogether.await();
					for (int n = 0; n < iterations; n++) {
						e().kv("threadNo", threadNo);
						e().f("work").kv("iteration", n);
						e().f("work").sf("detail").kv("owner", threadNo);
						Assertions.assertEquals(threadNo, e().toObjectNode().get("threadNo").asInt());
					}
					String evidence = e().toString();
					ExecutionEvidenceLogger.clear();
					return evidence;
				};
				results.add(executor.submit(buildEvidence));
			}

			for (int i = 0; i < threadCount; i++) {
				Assertions.assertEquals("{\"threadNo\":" + i + ",\"work\":{\"iteration\":" + (iterations - 1) + ",\"detail\":{\"owner\":" + i + "}}}", results.get(i).get());
			}
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	public void test_pooled_thread_starts_with_empty_evidence_after_log_now() throws Exception {
		captureWrites();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			executor.submit(() -> {
				e().kv("firstTask", "secret");
				e().logNow();
			}).get();

			String evidenceAtStart = executor.submit(() -> {
				String atStart = e().toString();
				e().kv("secondTask", true);
				e().logNow();
				return atStart;
			}).get();

			Assertions.assertEquals("{}", evidenceAtStart);
			Assertions.assertEquals(List.of("{\"firstTask\":\"secret\"}", "{\"secondTask\":true}"), written);
		} finally {
			executor.shutdownNow();
		}
	}
}
