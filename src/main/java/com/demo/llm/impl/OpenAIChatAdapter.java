package com.demo.llm.impl;

import com.demo.model.Message;
import com.demo.model.ToolCall;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import java.util.List;

/** Maps domain messages to SDK types; the SDK owns wire serialization. */
class OpenAIChatAdapter {
    private static final Gson GSON = new Gson();

    ChatCompletionCreateParams buildRequest(List<Message> messages, String toolsDescription,
            String model, int maxTokens, double temperature) {
        try {
            return buildRequestInternal(messages, toolsDescription, model, maxTokens, temperature);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid message or tool definition", e);
        }
    }

    private ChatCompletionCreateParams buildRequestInternal(List<Message> messages, String toolsDescription,
            String model, int maxTokens, double temperature) {
        // Keep max_tokens for the project's existing Chat Completions endpoints.
        var request = ChatCompletionCreateParams.builder()
                .model(model).maxTokens((long) maxTokens).temperature(temperature);
        for (Message message : messages) {
            switch (message.getRole()) {
                case "system" -> request.addMessage(ChatCompletionSystemMessageParam.builder()
                        .content(message.getContent()).build());
                case "user" -> request.addMessage(ChatCompletionUserMessageParam.builder()
                        .content(message.getContent()).build());
                case "assistant" -> {
                    var assistant = ChatCompletionAssistantMessageParam.builder();
                    if (message.getContent() != null) {
                        assistant.content(message.getContent());
                    }
                    if (message.getToolCalls() != null) {
                        for (ToolCall call : message.getToolCalls()) {
                            assistant.addToolCall(ChatCompletionMessageFunctionToolCall.builder()
                                    .id(call.getId())
                                    .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                            .name(call.getToolName()).arguments(call.getArguments()).build())
                                    .build());
                        }
                    }
                    request.addMessage(assistant.build());
                }
                case "tool" -> request.addMessage(ChatCompletionToolMessageParam.builder()
                        .toolCallId(message.getToolCallId()).content(message.getContent()).build());
                default -> throw new IllegalArgumentException("Unsupported message role");
            }
        }
        if (toolsDescription != null && !toolsDescription.isBlank()) {
            for (JsonElement element : JsonParser.parseString(toolsDescription).getAsJsonArray()) {
                var tool = element.getAsJsonObject();
                if (!"function".equals(tool.get("type").getAsString())) {
                    throw new IllegalArgumentException("Only function tools are supported");
                }
                var function = tool.getAsJsonObject("function");
                var definition = FunctionDefinition.builder().name(function.get("name").getAsString());
                if (function.has("description")) {
                    definition.description(function.get("description").getAsString());
                }
                if (function.has("parameters")) {
                    var parameters = FunctionParameters.builder();
                    function.getAsJsonObject("parameters").entrySet().forEach(entry ->
                            parameters.putAdditionalProperty(entry.getKey(),
                                    JsonValue.from(GSON.fromJson(entry.getValue(), Object.class))));
                    definition.parameters(parameters.build());
                }
                if (function.has("strict")) {
                    definition.strict(function.get("strict").getAsBoolean());
                }
                request.addTool(ChatCompletionFunctionTool.builder().function(definition.build()).build());
            }
        }
        return request.build();
    }
}
