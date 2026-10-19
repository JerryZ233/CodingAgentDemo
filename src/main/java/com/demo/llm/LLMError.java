package com.demo.llm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Only safe display fields leave the provider adapter. Values are JSON encoded. */
public record LLMError(Source source, Integer httpStatus, Map<String, String> fields,
                       boolean retryable, long serverWaitMillis, boolean fieldsOmitted) {
    public enum Source { SERVER, TRANSPORT, REQUEST, RESPONSE }
    public LLMError {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
    public LLMError(Source source, Integer httpStatus, Map<String, String> fields, boolean retryable, long serverWaitMillis) {
        this(source, httpStatus, fields, retryable, serverWaitMillis, false);
    }
}
