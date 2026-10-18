package com.github.tunaozkasap.execlog;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Writes a finished execution evidence somewhere, e.g. to the console or to a logging framework.
 * <p>
 * Extensions register an implementation in {@code META-INF/services/com.github.tunaozkasap.execlog.EvidenceWriter}.
 * When several are registered the one with the highest {@link #priority()} is used. Without any registered writer
 * {@link ConsoleEvidenceWriter} is used. A writer can also be set explicitly via
 * {@link ExecutionEvidenceLogger#setWriter(EvidenceWriter)}.
 */
public interface EvidenceWriter {

	void write(ObjectNode evidence);

	/**
	 * Higher priority wins when several writers are registered.
	 */
	default int priority() {
		return 0;
	}
}
