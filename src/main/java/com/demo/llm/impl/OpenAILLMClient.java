package com.demo.llm.impl;

import com.demo.config.Config;
import com.demo.llm.LLMClient;
import com.demo.model.ToolCall;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI-compatible LLM client implementation.
 * 
 * This class communicates with OpenAI-compatible APIs (OpenAI, Ollama, LM Studio, etc.)
 * and properly parses both text responses and tool calls.
 */
public class OpenAILLMClient extends LLMClientImpl {
    
    private final String model;
    private final int maxTokens;
    private final double temperature;
    
    /**
     * Creates a new OpenAI client using Config.
     */
    public OpenAILLMClient() {
        this(Config.getInstance().getApiUrl(),
             Config.getInstance().getApiKey(),
             Config.getInstance().getModel(),
             Config.getInstance().getMaxTokens(),
             Config.getInstance().getTemperature());
    }
    
    /**
     * Creates a new OpenAI client with custom settings.
     */
    public OpenAILLMClient(String apiUrl, String apiKey, String model, int maxTokens, double temperature) {
        super(apiKey, apiUrl);
        this.model = model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
    }
    
    @Override
    protected String getModel() {
        return model;
    }
    
    @Override
    protected int getMaxTokens() {
        return maxTokens;
    }
    
    @Override
    protected double getTemperature() {
        return temperature;
    }
    
    /**
     * Parses the OpenAI API JSON response using Gson.
     */
    @Override
    protected LLMResponse parseResponse(String jsonResponse) {
        if (jsonResponse == null || jsonResponse.isEmpty()) {
            return new LLMResponse("Error: Empty response from LLM", null);
        }
        
        try {
            JsonObject root = JsonParser.parseString(jsonResponse).getAsJsonObject();
            
            // Extract text content from first choice
            String text = "";
            if (root.has("choices")) {
                JsonArray choices = root.getAsJsonArray("choices");
                if (choices != null && choices.size() > 0) {
                    JsonObject firstChoice = choices.get(0).getAsJsonObject();
                    if (firstChoice.has("message")) {
                        JsonObject message = firstChoice.getAsJsonObject("message");
                        if (message.has("content")) {
                            text = message.get("content").getAsString();
                        }
                        
                        // Extract tool calls from message
                        if (message.has("tool_calls")) {
                            List<ToolCall> toolCalls = extractToolCalls(message.getAsJsonArray("tool_calls"));
                            return new LLMResponse(text, toolCalls);
                        }
                    }
                }
            }
            
            return new LLMResponse(text, null);
            
        } catch (Exception e) {
            System.err.println("Failed to parse LLM response: " + e.getMessage());
            System.err.println("Response: " + jsonResponse);
            return new LLMResponse("Error: Failed to parse LLM response: " + e.getMessage(), null);
        }
    }
    
    /**
     * Extracts tool calls from JSON array.
     */
    private List<ToolCall> extractToolCalls(JsonArray toolCallsArray) {
        List<ToolCall> toolCalls = new ArrayList<>();
        
        if (toolCallsArray == null) {
            return toolCalls;
        }
        
        for (JsonElement element : toolCallsArray) {
            try {
                JsonObject toolCall = element.getAsJsonObject();
                
                // Handle function format
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
                    
                    toolCalls.add(new ToolCall(name, arguments));
                }
            } catch (Exception e) {
                // Skip invalid tool call
            }
        }
        
        return toolCalls;
    }
}
