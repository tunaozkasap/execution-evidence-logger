package com.github.tunaozkasap.execlog;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;

/**
 * A logger for accumulating facts about an execution in a thread. In a likely scenario thread will be a request thread.
 *
 */
@Slf4j
public class ExecutionEvidenceLogger {
	private static ObjectMapper objectMapper = new ObjectMapper();
	
	private static ThreadLocal<ExecutionEvidence> evidencePlane = ThreadLocal.withInitial(() -> new ExecutionEvidence());
	
	public static ExecutionEvidence e() {
		return evidencePlane.get();
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
		
		public void logNow() {
			logbackHook();
	        // This message won't be logged out if the layout was registered correctly
			log.info("LogbackEvidenceLayout is not registered correctly!, normally evidence json message should be in this log line");
		}
		
		public void logbackHook() {
			try {
				if(isLogbackClassicInClasspath()) {
					Class<?> logbackHookClass = Class.forName("com.github.tunaozkasap.execlog.LogbackHook");
					logbackHookClass.getMethod("hook").invoke(logbackHookClass);
				}
			} catch (Exception ex) {
				log.error("Failed calling logback hook", ex);
			}
		}
		
		static boolean isLogbackClassicInClasspath() {
			try {
				Class.forName("ch.qos.logback.classic.Logger");
				return true;
			} catch(ClassNotFoundException ex) {
				return false;
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
