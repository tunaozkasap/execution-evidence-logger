package com.github.tunaozkasap.execlog;

import static com.github.tunaozkasap.execlog.ExecutionEvidenceLogger.e;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;

import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.LayoutBase;
import lombok.Getter;
import lombok.Setter;


@Getter
@Setter
public class LogbackEvidenceLayout extends LayoutBase<ILoggingEvent> {
	
	public static final String TIMESTAMP_ATTR_NAME = "timestamp";
    public static final String LEVEL_ATTR_NAME = "level";
    public static final String THREAD_ATTR_NAME = "thread";
    public static final String MDC_ATTR_NAME = "mdc";
    public static final String LOGGER_ATTR_NAME = "logger";
    public static final String EVIDENCE_ATTR_NAME = "evidence";
    public static final String EXCEPTION_ATTR_NAME = "exception";
    public static final String CONTEXT_ATTR_NAME = "context";
    public final static String CONTENT_TYPE = "application/json";
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    protected boolean includeTimestamp;
    protected String timestampFormat;
    protected String timestampFormatTimezoneId;
    protected boolean appendLineSeparator;
    protected boolean includeThreadName;
    protected boolean includeMDC;
    protected boolean includeLoggerName;
    protected boolean includeFormattedMessage;
    protected boolean includeException;
    protected boolean includeContextName;
    
    private ThrowableProxyConverter throwableProxyConverter;

    public LogbackEvidenceLayout() {
        this.includeTimestamp = true;
        this.appendLineSeparator = false;
        this.includeThreadName = true;
        this.includeMDC = true;
        this.includeLoggerName = true;
        this.includeFormattedMessage = true;
        this.includeException = true;
        this.includeContextName = true;
        this.throwableProxyConverter = new ThrowableProxyConverter();
    }
    
    @Override
    public void start() {
        this.throwableProxyConverter.start();
        super.start();
    }

    @Override
    public void stop() {
        super.stop();
        this.throwableProxyConverter.stop();
    }

    @Override
    public String doLayout(ILoggingEvent event) {
    	ObjectNode rootNode = objectMapper.createObjectNode();

        addTimestamp(TIMESTAMP_ATTR_NAME, this.includeTimestamp, event.getTimeStamp(), rootNode);
        add(THREAD_ATTR_NAME, this.includeThreadName, event.getThreadName(), rootNode);
        addMap(MDC_ATTR_NAME, this.includeMDC, event.getMDCPropertyMap(), rootNode);
        add(LOGGER_ATTR_NAME, this.includeLoggerName, event.getLoggerName(), rootNode);
        rootNode.set(EVIDENCE_ATTR_NAME, e().toObjectNode());
        add(CONTEXT_ATTR_NAME, this.includeContextName, event.getLoggerContextVO().getName(), rootNode);
        addThrowableInfo(EXCEPTION_ATTR_NAME, this.includeException, event, rootNode);
    	
        String result = rootNode.toString();
        return isAppendLineSeparator() ? result + CoreConstants.LINE_SEPARATOR : result;
    }
    
    protected void addThrowableInfo(String fieldName, boolean fieldIsIncluded, ILoggingEvent value, ObjectNode rootNode) {
        if (fieldIsIncluded && value != null) {
            IThrowableProxy throwableProxy = value.getThrowableProxy();
            if (throwableProxy != null) {
                String ex = throwableProxyConverter.convert(value);
                if (ex != null && !ex.equals("")) {
                	rootNode.put(fieldName, ex);
                }
            }
        }
    }

    protected String formatTimestamp(long timestamp) {
        if (this.timestampFormat == null || timestamp < 0) {
            return String.valueOf(timestamp);
        }
        Date date = new Date(timestamp);
        DateFormat format = createDateFormat(this.timestampFormat);

        if (this.timestampFormatTimezoneId != null) {
            TimeZone tz = TimeZone.getTimeZone(this.timestampFormatTimezoneId);
            format.setTimeZone(tz);
        }

        return format(date, format);
    }

    public void addMap(String key, boolean fieldIsIncluded, Map<String, ?> mapValue, ObjectNode rootNode) {
        if (fieldIsIncluded && mapValue != null && !mapValue.isEmpty()) {
        	rootNode.set(key, objectMapper.valueToTree(mapValue));
        }
    }

    public void addTimestamp(String key, boolean fieldIsIncluded, long timeStamp, ObjectNode rootNode) {
        if(fieldIsIncluded){
            String formatted = formatTimestamp(timeStamp);
            if (formatted != null) {
            	rootNode.put(key, formatted);
            }
        }
    }

    public void add(String fieldName, boolean fieldIsIncluded, String value, ObjectNode rootNode) {
        if (fieldIsIncluded && value != null) {
            rootNode.put(fieldName, value);
        }
    }

    protected DateFormat createDateFormat(String timestampFormat) {
        return new SimpleDateFormat(timestampFormat);
    }

    protected String format(Date date, DateFormat format) {
        return format.format(date);
    }

    @Override
    public String getContentType() {
        return CONTENT_TYPE;
    }
}
