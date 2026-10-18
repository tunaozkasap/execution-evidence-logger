package com.github.tunaozkasap.execlog;

import static com.github.tunaozkasap.execlog.ExecutionEvidenceLogger.e;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tunaozkasap.execlog.ExecutionEvidenceLogger.ExecutionEvidence.ExecutionFact;

public class ExecutionFactTest {

	@BeforeEach
	@AfterEach
	public void clearEvidence() {
		// Tests run on the same thread, do not let evidence leak between them
		ExecutionEvidenceLogger.clear();
	}

	@Test
	public void test_fact_with_same_key_returns_same_instance() {
		ExecutionFact first = e().f("payment");
		ExecutionFact second = e().f("payment");

		Assertions.assertSame(first, second);
		Assertions.assertNotSame(first, e().f("shipment"));
	}

	@Test
	public void test_sub_fact_with_same_key_returns_same_instance() {
		ExecutionFact first = e().f("payment").sf("card");
		ExecutionFact second = e().f("payment").sf("card");

		Assertions.assertSame(first, second);
		Assertions.assertNotSame(first, e().f("payment").sf("bank"));
		Assertions.assertNotSame(first, e().f("refund").sf("card"));
	}

	@Test
	public void test_fact_kv_overwrites_in_place() {
		e().f("payment").kv("status", "pending").kv("amount", 100);
		e().f("payment").kv("status", "success");

		Assertions.assertEquals("{\"payment\":{\"status\":\"success\",\"amount\":100}}", e().toString());
	}

	@Test
	public void test_evidence_kv_overwrites_in_place() {
		e().kv("step", 1).kv("userId", 42);
		e().kv("step", 2);

		Assertions.assertEquals("{\"step\":2,\"userId\":42}", e().toString());
	}

	@Test
	public void test_sub_facts_nest_multiple_levels() {
		e().f("request").sf("db").sf("query").kv("rows", 5);
		e().f("request").sf("db").sf("query").sf("plan").kv("index", "idx_user");
		e().f("request").sf("db").kv("pool", "main");

		Assertions.assertEquals("{\"request\":{\"db\":{\"query\":{\"rows\":5,\"plan\":{\"index\":\"idx_user\"}},\"pool\":\"main\"}}}", e().toString());
	}

	@Test
	public void test_keys_keep_insertion_order() {
		e().kv("z", 1);
		e().f("m").kv("y", 1).kv("b", 2);
		e().kv("a", 1);
		e().f("m").sf("x").kv("k", 1);
		e().f("m").kv("a", 3);
		e().f("c");

		Assertions.assertEquals("{\"z\":1,\"m\":{\"y\":1,\"b\":2,\"x\":{\"k\":1},\"a\":3},\"a\":1,\"c\":{}}", e().toString());
	}

	@Test
	public void test_object_node_reflects_later_changes() {
		ObjectNode evidenceNode = e().toObjectNode();
		ExecutionFact payment = e().f("payment");

		payment.kv("status", "pending");
		e().kv("userId", 42);
		payment.sf("card").kv("brand", "visa");
		payment.kv("status", "success");

		Assertions.assertSame(evidenceNode, e().toObjectNode());
		Assertions.assertEquals("{\"payment\":{\"status\":\"success\",\"card\":{\"brand\":\"visa\"}},\"userId\":42}", evidenceNode.toString());
	}

}
