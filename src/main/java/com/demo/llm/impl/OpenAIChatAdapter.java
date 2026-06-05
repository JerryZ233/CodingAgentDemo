package com.demo.llm.impl;

import com.demo.model.Message;
import com.demo.model.ToolCall;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;

/**
 * Formats domain messages as OpenAI-compatible chat completion requests.
 */
class OpenAIChatAdapter {

    private final Gson gson;

    OpenAIChatAdapter() {
        this.gson = new GsonBuilder().serializeNulls().create();
    }

    String buildRequestBody(List<Message> messages, String toolsDescription, String model,
            int maxTokens, double temperature) {
        JsonObject request = new JsonObject();
        request.addProperty("model", model);
        request.addProperty("max_tokens", maxTokens);
        request.addProperty("temperature", temperature);
        request.add("messages", formatMessages(messages));

        if (toolsDescription != null && !toolsDescription.isEmpty()) {
            JsonArray toolsArray = JsonParser.parseString(toolsDescription).getAsJsonArray();
            request.add("tools", toolsArray);
        }

        return gson.toJson(request);
    }

    private JsonArray formatMessages(List<Message> messages) {
        JsonArray messageArray = new JsonArray();
        for (Message message : messages) {
            JsonObject item = new JsonObject();
            item.addProperty("role", message.getRole());
            if (message.getContent() == null) {
                item.add("content", JsonNull.INSTANCE);
            } else {
                item.addProperty("content", message.getContent());
            }

            if ("assistant".equals(message.getRole())
                    && message.getToolCalls() != null
                    && !message.getToolCalls().isEmpty()) {
                item.add("tool_calls", formatToolCalls(message.getToolCalls()));
            }

            if ("tool".equals(message.getRole())) {
                item.addProperty("tool_call_id", message.getToolCallId());
                item.addProperty("name", message.getToolName());
            }

            messageArray.add(item);
        }
        return messageArray;
    }

    private JsonArray formatToolCalls(List<ToolCall> toolCalls) {
        JsonArray formatted = new JsonArray();
        for (ToolCall toolCall : toolCalls) {
            JsonObject item = new JsonObject();
            item.addProperty("id", toolCall.getId());
            item.addProperty("type", "function");

            JsonObject function = new JsonObject();
            function.addProperty("name", toolCall.getToolName());
            function.addProperty("arguments", toolCall.getArguments());
            item.add("function", function);

            formatted.add(item);
        }
        return formatted;
    }
}
