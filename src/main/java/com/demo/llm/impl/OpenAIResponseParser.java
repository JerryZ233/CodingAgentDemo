package com.demo.llm.impl;

import com.demo.llm.LLMClient.LLMResponse;
import com.demo.model.ToolCall;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses OpenAI-compatible chat completion responses into domain responses.
 */
class OpenAIResponseParser {

    LLMResponse parse(String jsonResponse) {
        if (jsonResponse == null || jsonResponse.isEmpty()) {
            return LLMResponse.error("Empty response from LLM");
        }

        try {
            JsonObject root = JsonParser.parseString(jsonResponse).getAsJsonObject();

            String text = "";
            if (root.has("choices")) {
                JsonArray choices = root.getAsJsonArray("choices");
                if (choices != null && choices.size() > 0) {
                    JsonObject firstChoice = choices.get(0).getAsJsonObject();
                    if (firstChoice.has("message")) {
                        JsonObject message = firstChoice.getAsJsonObject("message");
                        if (message.has("content") && !message.get("content").isJsonNull()) {
                            text = message.get("content").getAsString();
                        }

                        if (message.has("tool_calls")) {
                            List<ToolCall> toolCalls;
                            try {
                                toolCalls = extractToolCalls(message.getAsJsonArray("tool_calls"));
                            } catch (IllegalArgumentException e) {
                                return LLMResponse.error("Invalid tool_calls in LLM response: " + e.getMessage());
                            }
                            return new LLMResponse(text, toolCalls);
                        }
                    }
                }
            }

            return new LLMResponse(text, null);
        } catch (Exception e) {
            return LLMResponse.error("Failed to parse LLM response: " + e.getMessage());
        }
    }

    private List<ToolCall> extractToolCalls(JsonArray toolCallsArray) {
        List<ToolCall> toolCalls = new ArrayList<>();

        if (toolCallsArray == null) {
            return toolCalls;
        }

        for (JsonElement element : toolCallsArray) {
            try {
                JsonObject toolCall = element.getAsJsonObject();
                String id = toolCall.has("id") ? toolCall.get("id").getAsString() : null;

                if (toolCall.has("function")) {
                    JsonObject function = toolCall.getAsJsonObject("function");
                    String name = function.get("name").getAsString();

                    String arguments = "{}";
                    if (function.has("arguments")) {
                        JsonElement args = function.get("arguments");
                        if (args.isJsonObject()) {
                            arguments = args.getAsJsonObject().toString();
                        } else {
                            arguments = args.getAsString();
                        }
                    }

                    toolCalls.add(new ToolCall(id, name, arguments));
                } else {
                    throw new IllegalArgumentException("tool call is missing function");
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("malformed tool call: " + e.getMessage(), e);
            }
        }

        return toolCalls;
    }
}
