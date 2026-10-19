package com.demo.llm.impl;

import com.demo.llm.LLMClient.LLMResponse;
import com.demo.model.ToolCall;
import com.openai.models.chat.completions.ChatCompletion;
import java.util.ArrayList;
import java.util.List;

/** Maps typed SDK responses back to the agent's model. */
class OpenAIResponseMapper {
    LLMResponse map(ChatCompletion completion) {
        if (completion.choices().isEmpty()) {
            return LLMResponse.error("LLM response contains no choices");
        }
        var message = completion.choices().get(0).message();
        List<ToolCall> calls = new ArrayList<>();
        for (var call : message.toolCalls().orElse(List.of())) {
            var function = call.asFunction();
            String id = function.id();
            String name = function.function().name();
            String arguments = function.function().arguments();
            if (id.isBlank() || name.isBlank()) {
                return LLMResponse.error("Invalid tool call in LLM response");
            }
            calls.add(new ToolCall(id, name, arguments));
        }
        return new LLMResponse(message.content().orElse(""), calls, usage(completion));
    }

    static com.demo.llm.TokenUsage usage(ChatCompletion completion) {
        return completion.usage().map(u -> {
            Long cache = u.promptTokensDetails().flatMap(d -> d.cachedTokens()).orElse(null);
            // DeepSeek uses a provider-specific top-level usage field instead of prompt_tokens_details.
            if (cache == null && u._additionalProperties().containsKey("prompt_cache_hit_tokens")) {
                Object value = u._additionalProperties().get("prompt_cache_hit_tokens").asNumber().orElse(null);
                if (value instanceof Number number) cache = number.longValue();
            }
            return new com.demo.llm.TokenUsage(u.promptTokens(), u.completionTokens(), u.totalTokens(), cache);
        }).orElse(null);
    }
}
