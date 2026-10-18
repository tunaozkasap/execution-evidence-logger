package com.github.tunaozkasap.execlog;

import static com.github.tunaozkasap.execlog.ExecutionEvidenceLogger.e;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

import com.github.tunaozkasap.execlog.ExecutionEvidenceLogger.ExecutionEvidence;
import com.github.tunaozkasap.execlog.ExecutionEvidenceLogger.ExecutionEvidence.ExecutionFact;

/**
 * Extends {@link ExecutionEvidenceLogger} with a per-thread stack of parent facts. While a parent is open, every
 * {@link #kv(String, Object)} and {@link #f(String)} made through {@link #s()} - in any method down the call stack -
 * goes under that parent instead of the evidence root. Parents opened inside another parent become its sub-facts,
 * so the evidence mirrors the call tree.
 *
 * <h2>Always open a parent with try-with-resources</h2>
 * {@link #parent(String)} returns a {@link Scope}. Open it in a try-with-resources statement, so the parent is
 * closed when the block ends, also when an exception is thrown. A parent that is never closed keeps collecting
 * everything written afterwards in that execution.
 *
 * <pre>{@code
 * import static com.github.tunaozkasap.execlog.ExecutionEvidenceLogger.e;
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
 *         e().logNow();
 *     }
 * }
 *
 * public class PaymentService {
 *     public void charge(Order order) {
 *         s().kv("amount", order.getTotal());            // -> payment.amount
 *
 *         try (var fraudCheck = s().parent("fraudCheck")) {
 *             s().kv("score", fraudClient.score(order)); // -> payment.fraudCheck.score
 *         }
 *     }
 * }
 * }</pre>
 *
 * Logged evidence: {@code {"orderId":42,"payment":{"amount":99.5,"fraudCheck":{"score":0.12}},"status":"placed"}}
 *
 * <h2>Rules</h2>
 * <ul>
 * <li>Opening a parent with a key that already exists under the current parent reuses that fact, it never creates
 * a list. Calling the same method twice under the same parent writes into the same fact.</li>
 * <li>{@link ExecutionEvidenceLogger#e()} is unaffected and always writes to the evidence root.</li>
 * <li>The stack belongs to the current evidence. After {@link ExecutionEvidence#logNow()} or
 * {@link ExecutionEvidenceLogger#clear()} the next execution on the thread starts with no parent open, so a
 * forgotten parent never leaks into the next request on a pooled thread.</li>
 * <li>Like the evidence, parents are per thread. Work handed to another thread starts without a parent.</li>
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
public class ScopedEvidence {

	private static final ThreadLocal<ScopedEvidence> scopedEvidencePlane = ThreadLocal.withInitial(ScopedEvidence::new);

	private final Deque<Parent> parents = new ArrayDeque<>();
	private ExecutionEvidence owner;

	private ScopedEvidence() {
	}

	/**
	 * The scoped evidence of the current thread. Writes go under the innermost open parent, or to the evidence root
	 * when no parent is open.
	 */
	public static ScopedEvidence s() {
		ScopedEvidence scopedEvidence = scopedEvidencePlane.get();
		ExecutionEvidence current = e();
		if(scopedEvidence.owner != current) {
			// A new execution started, parents of the previous evidence do not apply anymore
			scopedEvidence.parents.clear();
			scopedEvidence.owner = current;
		}
		return scopedEvidence;
	}

	/**
	 * Set/Overwrite an item with the given key and value under the current parent, or on the evidence root
	 * when no parent is open.
	 */
	public ScopedEvidence kv(String key, Object value) {
		Parent current = parents.peek();
		if(current == null) {
			owner.kv(key, value);
		} else {
			current.fact.kv(key, value);
		}
		return this;
	}

	/**
	 * Get/Create a fact under the current parent, or on the evidence root when no parent is open.
	 * The fact does not become a parent, use {@link #parent(String)} for that.
	 */
	public ExecutionFact f(String factKey) {
		Parent current = parents.peek();
		return current == null ? owner.f(factKey) : current.fact.sf(factKey);
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

	private Parent push(String factKey) {
		Parent parent = new Parent(factKey, f(factKey));
		parents.push(parent);
		return parent;
	}

	private void close(Parent parent) {
		if(!parents.contains(parent)) {
			// Already closed, or the evidence was logged/cleared in the meantime
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
	 * An open parent, returned by {@link #parent(String)}. Closing it makes the previous parent, or the evidence
	 * root, current again. Closing it more than once has no effect. <b>Use it in a try-with-resources statement</b>
	 * so it is always closed:
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
