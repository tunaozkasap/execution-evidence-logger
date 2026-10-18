package com.github.tunaozkasap.execlog;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Default writer, prints the evidence JSON as one line to {@code System.out}.
 */
public class ConsoleEvidenceWriter implements EvidenceWriter {

	@Override
	public void write(ObjectNode evidence) {
		System.out.println(evidence.toString());
	}
}
