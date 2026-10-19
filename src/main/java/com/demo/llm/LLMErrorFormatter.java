package com.demo.llm;

/** Mechanical field rendering, without interpreting or translating provider messages. */
public final class LLMErrorFormatter {
    private LLMErrorFormatter() { }
    public static String format(LLMFailure failure) {
        LLMError error = failure.error();
        StringBuilder text = new StringBuilder();
        if (error.source() == LLMError.Source.SERVER) {
            text.append("[server]\n");
            if (error.httpStatus() != null) text.append("http_status: ").append(error.httpStatus()).append('\n');
            error.fields().forEach((key, value) -> text.append(key).append(": ").append(value).append('\n'));
        }
        text.append("[client]\nsource: ").append(error.source()).append('\n');
        if (error.fieldsOmitted()) text.append("fields_omitted: true\n");
        if (error.source() != LLMError.Source.SERVER)
            error.fields().forEach((key, value) -> text.append(key).append(": ").append(value).append('\n'));
        text.append("attempt: ").append(failure.attempt()).append('/').append(failure.maxAttempts())
                .append("\nstate: ").append(failure.state());
        if (failure.retryInMillis() != null) text.append("\nretry_in_ms: ").append(failure.retryInMillis());
        return text.toString();
    }
}
