package com.demo.llm;

import com.demo.execution.CancellationToken;
import com.demo.model.Message;
import java.util.*;
import java.util.function.Consumer;

/** Observer is supplied per call, safe for concurrent foreground/background requests. */
public final class ObservedLLMClient implements LLMClient {
    private final LLMClient delegate;
    private final Consumer<RequestAttempt> observer;
    public ObservedLLMClient(LLMClient delegate, Consumer<RequestAttempt> observer) {
        this.delegate=Objects.requireNonNull(delegate);this.observer=Objects.requireNonNull(observer);
    }
    public LLMResponse sendMessage(List<Message> messages,String tools) { return sendMessage(messages,tools,CancellationToken.none()); }
    public LLMResponse sendMessage(List<Message> messages,String tools,CancellationToken token) { return sendMessage(messages,tools,token,ignored -> {}); }
    public LLMResponse sendMessage(List<Message> messages,String tools,CancellationToken token,Consumer<LLMFailure> retry) {
        return sendMessage(messages,tools,token,retry,RequestPurpose.AGENT);
    }
    public LLMResponse sendMessage(List<Message> messages,String tools,CancellationToken token,Consumer<LLMFailure> retry,RequestPurpose purpose) {
        return delegate.sendMessage(messages,tools,token,retry,purpose,observer);
    }
    public LLMResponse sendMessage(List<Message> messages,String tools,CancellationToken token,Consumer<LLMFailure> retry,
                                    RequestPurpose purpose,Consumer<RequestAttempt> perRequest) {
        return delegate.sendMessage(messages,tools,token,retry,purpose,attempt -> {
            try { observer.accept(attempt); } finally { perRequest.accept(attempt); }
        });
    }
    @Override public void close() { delegate.close(); }
}
