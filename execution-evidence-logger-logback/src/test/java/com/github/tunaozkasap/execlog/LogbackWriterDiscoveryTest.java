package com.github.tunaozkasap.execlog;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.github.tunaozkasap.execlog.logback.LogbackEvidenceWriter;

public class LogbackWriterDiscoveryTest {

	@Test
	public void test_logback_writer_is_discovered_when_module_is_on_classpath() {
		Assertions.assertInstanceOf(LogbackEvidenceWriter.class, EvidenceWriters.discover());
	}
}
