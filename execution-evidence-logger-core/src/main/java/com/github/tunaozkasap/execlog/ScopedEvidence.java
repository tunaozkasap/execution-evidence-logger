package com.github.tunaozkasap.execlog;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Accumulates facts about an execution in a thread - in a likely scenario a request thread - as one JSON evidence,
 * and logs it with {@link #logNow()}. {@link #s()} gives the evidence of the current thread.
 * <p>
 * Facts can be scoped to the call stack: while a parent fact is open, every {@link #kv(String, Object)} and
 * {@link #f(String)} made through {@link #s()} - in any method down the call stack - goes under that parent instead
 * of the evidence root. Parents opened inside another parent become its sub-facts, so the evidence mirrors the call
 * tree. {@link #root()} always writes to the evidence root.
 *
 * <h2>Always open a parent with try-with-resources</h2>
 * {@link #parent(String)} returns a {@link Scope}. Open it in a try-with-resources statement, so the parent is
 * closed when the block ends, also when an exception is thrown. A parent that is never closed keeps collecting
 * everything written afterwards in that execution.
 *
 * <pre>{@code
 * import static com.github.tunaozkasap.execlog.ScopedEvidence.s;
 *
 * public class OrderController {
 *     public void placeOrder(Order order) {
 *         s().kv("orderId", order.getId());              // no parent open -> evidence root
 *
 *         try (var payment = s().parent("payment")) {    // "payment" is the root inside this block
 *             paymentService.charge(order);
 *         }                                              // closed here, even if charge() throws
 *
 *         s().kv("status", "placed");                    // evidence root again
 *         s().logNow();
 *     }
 * }
 *
 * public class PaymentService {
 *     public void charge(Order order) {
 *         s().kv("amount", order.getTotal());            // -> payment.amount
 *         s().root().kv("paymentProvider", "stripe");    // -> evidence root, whatever parent is open
 *
 *         try (var fraudCheck = s().parent("fraudCheck")) {
 *             s().kv("score", fraudClient.score(order)); // -> payment.fraudCheck.score
 *         }
 *     }
 * }
 * }</pre>
 *
 * Logged evidence:
 * {@code {"orderId":42,"payment":{"amount":99.5,"fraudCheck":{"score":0.12}},"paymentProvider":"stripe","status":"placed"}}
 *
 * <h2>Rules</h2>
 * <ul>
 * <li>Opening a parent with a key that already exists under the current parent reuses that fact, it never creates
 * a list. Calling the same method twice under the same parent writes into the same fact.</li>
 * <li>{@link #logNow()} and {@link #clear()} end the execution: the next {@link #s()} on the thread returns a new,
 * empty evidence with no parent open, so a forgotten parent never leaks into the next request on a pooled thread.</li>
 * <li>Evidence and parents are per thread. Work handed to another thread starts with its own empty evidence.</li>
 * <li>Where the evidence is written is decided by {@link EvidenceWriters}.</li>
 * </ul>
 *
 * If try-with-resources can not be used, {@link #pushParent(String)} and {@link #popParent(String)} do the same
 * job, but the pop must then be in a {@code finally} block:
 *
 * <pre>{@code
 * s().pushParent("payment");
 * try {
 *     paymentService.charge(order);
 * } finally {
 *     s().popParent("payment");
 * }
 * }</pre>
 */
public final class ScopedEvidence {

	private static final ObjectMapper objectMapper = new ObjectMapper();

	private static final ThreadLocal<ScopedEvidence> evidencePlane = ThreadLocal.withInitial(ScopedEvidence::new);

	private final ObjectNode evidenceNode = objectMapper.createObjectNode();
	private final Map<String, ExecutionFact> factMap = new HashMap<>();
	private final Deque<Parent> parents = new ArrayDeque<>();
	private final Root root = new Root();

	private ScopedEvidence() {
	}

	/**
	 * The evidence of the current thread. Writes go under the innermost open parent, or to the evidence root
	 * when no parent is open.
	 */
	public static ScopedEvidence s() {
		return evidencePlane.get();
	}

	/**
	 * Discard the evidence gathered so far in the current thread, including open parents. The next call to
	 * {@link #s()} starts a new evidence. Call this at the end of an execution when the evidence is not logged via
	 * {@link #logNow()}, otherwise pooled threads will carry the evidence over to the next execution.
	 */
	public static void clear() {
		evidencePlane.remove();
	}

	/**
	 * Set/Overwrite an item with the given key and value under the current parent, or on the evidence root
	 * when no parent is open.
	 */
	public ScopedEvidence kv(String key, Object value) {
		Parent current = parents.peek();
		if(current == null) {
			root.kv(key, value);
		} else {
			current.fact.kv(key, value);
		}
		return this;
	}

	/**
	 * Get/Create a fact under the current parent, or on the evidence root when no parent is open. If there already
	 * is a fact with the given key there, it returns that instance. The fact does not become a parent, use
	 * {@link #parent(String)} for that.
	 */
	public ExecutionFact f(String factKey) {
		Parent current = parents.peek();
		return current == null ? root.f(factKey) : current.fact.sf(factKey);
	}

	/**
	 * Writes to the evidence root, ignoring any open parent.
	 *
	 * <pre>{@code
	 * try (var payment = s().parent("payment")) {
	 *     s().kv("amount", 10);            // -> payment.amount
	 *     s().root().kv("userId", 7);      // -> userId
	 * }
	 * }</pre>
	 */
	public Root root() {
		return root;
	}

	/**
	 * Open the fact with the given key, under the current parent, as the new parent until the returned
	 * {@link Scope} is closed. <b>Always use it in a try-with-resources statement:</b>
	 *
	 * <pre>{@code
	 * try (var payment = s().parent("payment")) {
	 *     paymentService.charge(order);   // everything written via s() lands in "payment"
	 * }
	 * }</pre>
	 */
	public Scope parent(String factKey) {
		return new Scope(this, push(factKey));
	}

	/**
	 * Same as {@link #parent(String)} without try-with-resources. Prefer {@link #parent(String)}; when using this
	 * method, call {@link #popParent(String)} in a {@code finally} block.
	 */
	public ScopedEvidence pushParent(String factKey) {
		push(factKey);
		return this;
	}

	/**
	 * Close the innermost parent opened with the given key. If inner parents were left open they are closed too,
	 * and a warning is printed to {@code System.err}. If no parent with the key is open nothing changes and a
	 * warning is printed.
	 */
	public ScopedEvidence popParent(String factKey) {
		for(Parent parent : parents) {
			if(parent.key.equals(factKey)) {
				close(parent);
				return this;
			}
		}
		System.err.println("ScopedEvidence: no open parent \"" + factKey + "\" to pop, open parents: " + openParentKeys());
		return this;
	}

	/**
	 * Keys of the open parents from the outermost to the innermost, e.g. {@code [payment, fraudCheck]}.
	 */
	public String openParentKeys() {
		StringBuilder keys = new StringBuilder("[");
		Iterator<Parent> outermostFirst = parents.descendingIterator();
		while(outermostFirst.hasNext()) {
			keys.append(outermostFirst.next().key);
			if(outermostFirst.hasNext()) {
				keys.append(", ");
			}
		}
		return keys.append("]").toString();
	}

	/**
	 * The whole evidence as JSON string.
	 */
	@Override
	public String toString() {
		return evidenceNode.toString();
	}

	/**
	 * The whole evidence as JSON ObjectNode. Later changes to the evidence are reflected in it.
	 */
	public ObjectNode toObjectNode() {
		return evidenceNode;
	}

	/**
	 * Write the evidence with the current {@link EvidenceWriter} (see {@link EvidenceWriters}) and clear it from the
	 * current thread, see {@link #clear()}. A failing writer never breaks the execution, the failure is reported to
	 * {@code System.err}.
	 */
	public void logNow() {
		try {
			EvidenceWriters.current().write(evidenceNode);
		} catch (RuntimeException ex) {
			System.err.println("Failed writing execution evidence: " + ex);
			ex.printStackTrace();
		} finally {
			if(evidencePlane.get() == this) {
				clear();
			}
		}
	}

	private Parent push(String factKey) {
		Parent parent = new Parent(factKey, f(factKey));
		parents.push(parent);
		return parent;
	}

	private void close(Parent parent) {
		if(!parents.contains(parent)) {
			// Already closed
			return;
		}
		while(parents.peek() != parent) {
			Parent leftOpen = parents.pop();
			System.err.println("ScopedEvidence: parent \"" + leftOpen.key + "\" was not closed before \"" + parent.key + "\", closing it now");
		}
		parents.pop();
	}

	// Compared by identity, a scope closes exactly the entry it opened
	private static final class Parent {
		private final String key;
		private final ExecutionFact fact;

		private Parent(String key, ExecutionFact fact) {
			this.key = key;
			this.fact = fact;
		}
	}

	/**
	 * The evidence root, returned by {@link ScopedEvidence#root()}. Writes here ignore any open parent.
	 */
	public final class Root {

		private Root() {
		}

		/**
		 * Set/Overwrite an item on the evidence root with the given key and value.
		 */
		public Root kv(String key, Object value) {
			evidenceNode.set(key, objectMapper.valueToTree(value));
			return this;
		}

		/**
		 * Get/Create a fact on the evidence root. If there already is a fact created with the given key
		 * it returns that instance.
		 */
		public ExecutionFact f(String factKey) {
			ExecutionFact fact = factMap.get(factKey);
			if(fact == null) {
				fact = new ExecutionFact();
				evidenceNode.set(factKey, fact.factNode);
				factMap.put(factKey, fact);
			}
			return fact;
		}
	}

	/**
	 * A fact of the evidence: a JSON object holding key-value pairs and sub-facts.
	 */
	public static final class ExecutionFact {
		private final ObjectNode factNode = objectMapper.createObjectNode();
		private final Map<String, ExecutionFact> subFactMap = new HashMap<>();

		private ExecutionFact() {
		}

		/**
		 * Set/Overwrite an fact item with the given key and value.
		 */
		public ExecutionFact kv(String key, Object value) {
			factNode.set(key, objectMapper.valueToTree(value));
			return this;
		}

		/**
		 * Get/Create a sub-fact of this fact. If there already is a sub-fact created with the given key
		 * it returns that instance.
		 */
		public ExecutionFact sf(String subFactKey) {
			ExecutionFact subFact = subFactMap.get(subFactKey);
			if(subFact == null) {
				subFact = new ExecutionFact();
				factNode.set(subFactKey, subFact.factNode);
				subFactMap.put(subFactKey, subFact);
			}
			return subFact;
		}
	}

	/**
	 * An open parent, returned by {@link ScopedEvidence#parent(String)}. Closing it makes the previous parent, or
	 * the evidence root, current again. Closing it more than once has no effect. <b>Use it in a try-with-resources
	 * statement</b> so it is always closed:
	 *
	 * <pre>{@code
	 * try (var payment = s().parent("payment")) {
	 *     payment.kv("provider", "stripe");   // same as s().kv(...) inside this block
	 *     paymentService.charge(order);
	 * }
	 * }</pre>
	 */
	public static final class Scope implements AutoCloseable {
		private final ScopedEvidence scopedEvidence;
		private final Parent parent;

		private Scope(ScopedEvidence scopedEvidence, Parent parent) {
			this.scopedEvidence = scopedEvidence;
			this.parent = parent;
		}

		/**
		 * Set/Overwrite an item directly on this parent's fact.
		 */
		public Scope kv(String key, Object value) {
			parent.fact.kv(key, value);
			return this;
		}

		/**
		 * The fact this scope opened as parent.
		 */
		public ExecutionFact fact() {
			return parent.fact;
		}

		@Override
		public void close() {
			scopedEvidence.close(parent);
		}
	}
}
