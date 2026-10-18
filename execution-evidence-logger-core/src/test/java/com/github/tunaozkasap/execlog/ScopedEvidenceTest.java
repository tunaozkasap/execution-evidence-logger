package com.github.tunaozkasap.execlog;

import static com.github.tunaozkasap.execlog.ExecutionEvidenceLogger.e;
import static com.github.tunaozkasap.execlog.ScopedEvidence.s;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ScopedEvidenceTest {

	private final List<String> written = Collections.synchronizedList(new ArrayList<>());
	private final ByteArrayOutputStream err = new ByteArrayOutputStream();
	private PrintStream originalErr;

	@BeforeEach
	public void setUp() {
		ExecutionEvidenceLogger.clear();
		ExecutionEvidenceLogger.setWriter(evidence -> written.add(evidence.toString()));
		originalErr = System.err;
		System.setErr(new PrintStream(err, true));
	}

	@AfterEach
	public void tearDown() {
		System.setErr(originalErr);
		ExecutionEvidenceLogger.setWriter(null);
		ExecutionEvidenceLogger.clear();
	}

	// Library-user style code: these methods know nothing about the parent they are called under

	private void chargePayment() {
		s().kv("amount", 99.5);
		try (var fraudCheck = s().parent("fraudCheck")) {
			scoreFraud();
		}
		s().kv("result", "charged");
	}

	private void scoreFraud() {
		s().kv("score", 0.12);
	}

	private void audit() {
		s().kv("audited", true);
	}

	@Test
	public void test_writes_go_to_root_without_parent() {
		s().kv("orderId", 42);
		s().f("payment").kv("status", "success");

		Assertions.assertEquals("{\"orderId\":42,\"payment\":{\"status\":\"success\"}}", e().toString());
	}

	@Test
	public void test_parent_is_root_for_called_methods_until_closed() {
		s().kv("orderId", 42);
		try (var payment = s().parent("payment")) {
			chargePayment();
		}
		s().kv("status", "placed");
		e().logNow();

		Assertions.assertEquals(List.of("{\"orderId\":42,\"payment\":{\"amount\":99.5,\"fraudCheck\":{\"score\":0.12},\"result\":\"charged\"},\"status\":\"placed\"}"), written);
	}

	@Test
	public void test_same_method_writes_where_it_is_called() {
		audit();
		try (var refund = s().parent("refund")) {
			audit();
		}

		Assertions.assertEquals("{\"audited\":true,\"refund\":{\"audited\":true}}", e().toString());
	}

	@Test
	public void test_reopening_same_key_reuses_fact_without_list() {
		for (int i = 0; i < 3; i++) {
			try (var payment = s().parent("payment")) {
				s().kv("attempt", i);
				s().kv("lastAttemptOnly" + (i == 2 ? "" : i), true);
			}
		}

		Assertions.assertEquals("{\"payment\":{\"attempt\":2,\"lastAttemptOnly0\":true,\"lastAttemptOnly1\":true,\"lastAttemptOnly\":true}}", e().toString());
	}

	@Test
	public void test_facts_are_relative_to_current_parent() {
		try (var payment = s().parent("payment")) {
			s().f("card").kv("brand", "visa");
			payment.kv("provider", "stripe");
		}

		Assertions.assertEquals("{\"payment\":{\"card\":{\"brand\":\"visa\"},\"provider\":\"stripe\"}}", e().toString());
	}

	@Test
	public void test_e_ignores_open_parent() {
		try (var payment = s().parent("payment")) {
			e().kv("userId", 7);
			s().kv("amount", 10);
		}

		Assertions.assertEquals("{\"payment\":{\"amount\":10},\"userId\":7}", e().toString());
	}

	@Test
	public void test_parent_is_closed_when_exception_is_thrown() {
		Assertions.assertThrows(IllegalStateException.class, () -> {
			try (var payment = s().parent("payment")) {
				s().kv("amount", 10);
				throw new IllegalStateException("payment failed");
			}
		});
		s().kv("status", "failed");

		Assertions.assertEquals("[]", s().openParentKeys());
		Assertions.assertEquals("{\"payment\":{\"amount\":10},\"status\":\"failed\"}", e().toString());
	}

	@Test
	public void test_closing_scope_twice_has_no_effect() {
		try (var payment = s().parent("payment")) {
			var fraudCheck = s().parent("fraudCheck");
			fraudCheck.close();
			fraudCheck.close();
			Assertions.assertEquals("[payment]", s().openParentKeys());
		}
		Assertions.assertEquals("[]", s().openParentKeys());
		Assertions.assertEquals("", err.toString());
	}

	@Test
	public void test_push_and_pop_with_finally() {
		s().pushParent("payment");
		try {
			chargePayment();
		} finally {
			s().popParent("payment");
		}
		s().kv("status", "placed");

		Assertions.assertEquals("{\"payment\":{\"amount\":99.5,\"fraudCheck\":{\"score\":0.12},\"result\":\"charged\"},\"status\":\"placed\"}", e().toString());
	}

	@Test
	public void test_closing_outer_parent_closes_forgotten_inner_parents_with_warning() {
		try (var payment = s().parent("payment")) {
			s().pushParent("fraudCheck");   // never popped
			s().kv("score", 0.12);
		}
		s().kv("status", "placed");

		Assertions.assertEquals("{\"payment\":{\"fraudCheck\":{\"score\":0.12}},\"status\":\"placed\"}", e().toString());
		Assertions.assertTrue(err.toString().contains("parent \"fraudCheck\" was not closed before \"payment\""), err.toString());
	}

	@Test
	public void test_popping_unknown_parent_warns_and_changes_nothing() {
		try (var payment = s().parent("payment")) {
			s().popParent("shipment");
			Assertions.assertEquals("[payment]", s().openParentKeys());
		}
		Assertions.assertTrue(err.toString().contains("no open parent \"shipment\" to pop, open parents: [payment]"), err.toString());
	}

	@Test
	public void test_forgotten_parent_does_not_leak_into_next_execution() {
		s().pushParent("payment");   // never popped
		s().kv("amount", 10);
		e().logNow();

		s().kv("orderId", 43);
		e().logNow();

		Assertions.assertEquals(List.of("{\"payment\":{\"amount\":10}}", "{\"orderId\":43}"), written);
	}

	@Test
	public void test_scope_closed_after_log_now_has_no_effect_on_next_execution() {
		try (var payment = s().parent("payment")) {
			s().kv("amount", 10);
			e().logNow();
			try (var refund = s().parent("refund")) {
				s().kv("reason", "duplicate");
			}
		}

		Assertions.assertEquals("{\"refund\":{\"reason\":\"duplicate\"}}", e().toString());
		Assertions.assertEquals("", err.toString());
	}

	@Test
	public void test_threads_have_separate_parents() throws Exception {
		int threadCount = 8;
		CyclicBarrier startTogether = new CyclicBarrier(threadCount);
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		try {
			List<Future<String>> results = new ArrayList<>();
			for (int i = 0; i < threadCount; i++) {
				String parentKey = "parent" + i;
				results.add(executor.submit(() -> {
					startTogether.await();
					for (int n = 0; n < 500; n++) {
						try (var parent = s().parent(parentKey)) {
							s().kv("n", n);
						}
					}
					String evidence = e().toString();
					ExecutionEvidenceLogger.clear();
					return evidence;
				}));
			}
			for (int i = 0; i < threadCount; i++) {
				Assertions.assertEquals("{\"parent" + i + "\":{\"n\":499}}", results.get(i).get());
			}
		} finally {
			executor.shutdownNow();
		}
	}
}
