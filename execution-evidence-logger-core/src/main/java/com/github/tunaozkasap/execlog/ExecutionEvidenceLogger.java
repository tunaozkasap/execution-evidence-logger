package com.github.tunaozkasap.execlog;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A logger for accumulating facts about an execution in a thread. In a likely scenario thread will be a request thread.
 *
 */
public class ExecutionEvidenceLogger {
	private static ObjectMapper objectMapper = new ObjectMapper();

	private static volatile EvidenceWriter writer;

	private static ThreadLocal<ExecutionEvidence> evidencePlane = ThreadLocal.withInitial(() -> new ExecutionEvidence());
	
	public static ExecutionEvidence e() {
		return evidencePlane.get();
	}

	/**
	 * Discard the evidence gathered so far in the current thread. The next call to {@link #e()} starts a new evidence.
	 * Call this at the end of an execution when the evidence is not logged via {@link ExecutionEvidence#logNow()},
	 * otherwise pooled threads will carry the evidence over to the next execution.
	 */
	public static void clear() {
		evidencePlane.remove();
	}

	/**
	 * Use the given writer for {@link ExecutionEvidence#logNow()} instead of the discovered one.
	 * Passing {@code null} goes back to discovery, see {@link EvidenceWriter}.
	 */
	public static void setWriter(EvidenceWriter evidenceWriter) {
		writer = evidenceWriter;
	}

	static EvidenceWriter writer() {
		EvidenceWriter current = writer;
		if(current == null) {
			synchronized(ExecutionEvidenceLogger.class) {
				if(writer == null) {
					writer = discoverWriter();
				}
				current = writer;
			}
		}
		return current;
	}

	/**
	 * Pick the {@link EvidenceWriter} with the highest priority registered via {@link ServiceLoader},
	 * falling back to {@link ConsoleEvidenceWriter}. Writers that fail to load, e.g. because their
	 * logging framework is missing from the classpath, are skipped.
	 */
	static EvidenceWriter discoverWriter() {
		EvidenceWriter best = null;
		Iterator<EvidenceWriter> writers = ServiceLoader.load(EvidenceWriter.class).iterator();
		while(true) {
			try {
				if(!writers.hasNext()) {
					break;
				}
				EvidenceWriter candidate = writers.next();
				if(best == null || candidate.priority() > best.priority()) {
					best = candidate;
				}
			} catch (ServiceConfigurationError | LinkageError ex) {
				System.err.println("Skipping evidence writer that failed to load: " + ex);
			}
		}
		return best != null ? best : new ConsoleEvidenceWriter();
	}

	public static class ExecutionEvidence {
		private ObjectNode evidenceNode = objectMapper.createObjectNode();
		private Map<String, ExecutionFact> factMap = new HashMap<>();
		
		/**
		 * Set/Overwrite an evidence item with the given key and value.
		 */
		public ExecutionEvidence kv(String key, Object value) {
			evidenceNode.set(key, objectMapper.valueToTree(value));
			return this;
		}
		
		/**
		 * Get/Create a fact for this execution evidence. If there already is a fact created with the given key
		 * it returns that instance.
		 */
		public ExecutionFact f(String factKey) {
			if(!factMap.containsKey(factKey)) {
				ExecutionFact executionFact = new ExecutionFact();
				evidenceNode.set(factKey, executionFact.factNode);
				factMap.put(factKey, executionFact);
			}
			
			return factMap.get(factKey);
		}
		
		public String toString() {
			return evidenceNode.toString();
		}
		
		public ObjectNode toObjectNode() {
			return evidenceNode;
		}
		
		/**
		 * Write the evidence with the current {@link EvidenceWriter} and clear it from the current thread,
		 * see {@link ExecutionEvidenceLogger#clear()}. A failing writer never breaks the execution, the failure
		 * is reported to {@code System.err}.
		 */
		public void logNow() {
			try {
				writer().write(toObjectNode());
			} catch (RuntimeException ex) {
				System.err.println("Failed writing execution evidence: " + ex);
				ex.printStackTrace();
			} finally {
				clear();
			}
		}

		public class ExecutionFact {
			private ObjectNode factNode = objectMapper.createObjectNode();
			private Map<String, ExecutionFact> subFactMap = new HashMap<>();
			
			/**
			 * Set/Overwrite an fact item with the given key and value.
			 */
			public ExecutionFact kv(String key, Object value) {
				factNode.set(key, objectMapper.valueToTree(value));
				return this;
			}
			
			public ExecutionFact sf(String subFactKey) {
				if(!subFactMap.containsKey(subFactKey)) {
					ExecutionFact executionSubFact = new ExecutionFact();
					factNode.set(subFactKey, executionSubFact.factNode);
					subFactMap.put(subFactKey, executionSubFact);
				}
				return subFactMap.get(subFactKey);
			}
		}
	}
}
