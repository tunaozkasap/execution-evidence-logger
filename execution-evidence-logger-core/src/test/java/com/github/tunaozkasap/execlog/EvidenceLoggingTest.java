package com.github.tunaozkasap.execlog;

import static com.github.tunaozkasap.execlog.ScopedEvidence.s;

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

public class EvidenceLoggingTest {

	private final List<String> written = Collections.synchronizedList(new ArrayList<>());

	private void captureWrites() {
		EvidenceWriters.setWriter(evidence -> written.add(evidence.toString()));
	}

	@AfterEach
	public void resetWriter() {
		EvidenceWriters.setWriter(null);
		ScopedEvidence.clear();
	}

	@Test
	public void test_console_writer_is_used_without_extensions() {
		Assertions.assertInstanceOf(ConsoleEvidenceWriter.class, EvidenceWriters.discover());
	}

	@Test
	public void test_log_now_prints_evidence_json_to_console_by_default() {
		ByteArrayOutputStream console = new ByteArrayOutputStream();
		PrintStream originalOut = System.out;
		System.setOut(new PrintStream(console, true));
		try {
			s().kv("userId", 23234l);
			s().f("userValidation").kv("status", "success");
			s().logNow();
		} finally {
			System.setOut(originalOut);
		}
		Assertions.assertEquals("{\"userId\":23234,\"userValidation\":{\"status\":\"success\"}}" + System.lineSeparator(), console.toString());
	}

	@Test
	public void test_explicit_writer_receives_evidence() {
		captureWrites();

		s().kv("userLoggedIn", true);
		s().logNow();

		Assertions.assertEquals(List.of("{\"userLoggedIn\":true}"), written);
	}

	@Test
	public void test_evidence_is_cleared_after_log_now() {
		captureWrites();

		s().kv("firstExecution", true);
		s().logNow();
		Assertions.assertEquals("{}", s().toString());

		s().kv("secondExecution", true);
		s().logNow();
		Assertions.assertEquals(List.of("{\"firstExecution\":true}", "{\"secondExecution\":true}"), written);
	}

	@Test
	public void test_failing_writer_does_not_break_execution_and_evidence_is_cleared() {
		EvidenceWriters.setWriter(evidence -> {
			throw new IllegalStateException("writer down");
		});
		PrintStream originalErr = System.err;
		System.setErr(new PrintStream(new ByteArrayOutputStream(), true));
		try {
			s().kv("userId", 1);
			Assertions.assertDoesNotThrow(() -> s().logNow());
		} finally {
			System.setErr(originalErr);
		}
		Assertions.assertEquals("{}", s().toString());
	}

	@Test
	public void test_clear_discards_evidence() {
		s().f("someFact").kv("status", "success");
		ScopedEvidence.clear();
		Assertions.assertEquals("{}", s().toString());
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
						s().kv("threadNo", threadNo);
						s().f("work").kv("iteration", n);
						s().f("work").sf("detail").kv("owner", threadNo);
						Assertions.assertEquals(threadNo, s().toObjectNode().get("threadNo").asInt());
					}
					String evidence = s().toString();
					ScopedEvidence.clear();
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
				s().kv("firstTask", "secret");
				s().logNow();
			}).get();

			String evidenceAtStart = executor.submit(() -> {
				String atStart = s().toString();
				s().kv("secondTask", true);
				s().logNow();
				return atStart;
			}).get();

			Assertions.assertEquals("{}", evidenceAtStart);
			Assertions.assertEquals(List.of("{\"firstTask\":\"secret\"}", "{\"secondTask\":true}"), written);
		} finally {
			executor.shutdownNow();
		}
	}
}
