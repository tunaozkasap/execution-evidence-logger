package com.github.tunaozkasap.execlog;

import java.util.Iterator;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Chooses the {@link EvidenceWriter} used by {@link ScopedEvidence#logNow()}.
 * <p>
 * By default the writer with the highest {@link EvidenceWriter#priority()} registered via {@link ServiceLoader} is
 * used, falling back to {@link ConsoleEvidenceWriter}. {@link #setWriter(EvidenceWriter)} overrides the discovery.
 */
public final class EvidenceWriters {

	private static volatile EvidenceWriter writer;

	private EvidenceWriters() {
	}

	/**
	 * Use the given writer for {@link ScopedEvidence#logNow()} instead of the discovered one.
	 * Passing {@code null} goes back to discovery.
	 */
	public static void setWriter(EvidenceWriter evidenceWriter) {
		writer = evidenceWriter;
	}

	static EvidenceWriter current() {
		EvidenceWriter current = writer;
		if(current == null) {
			synchronized(EvidenceWriters.class) {
				if(writer == null) {
					writer = discover();
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
	static EvidenceWriter discover() {
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
}
