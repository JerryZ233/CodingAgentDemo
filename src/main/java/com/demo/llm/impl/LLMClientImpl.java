package com.demo.llm.impl;

import com.demo.config.Config;
import com.demo.llm.LLMClient;
import com.demo.model.Message;
import com.demo.model.ToolCall;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * LLM client implementation for OpenAI-compatible APIs.
 * 
 * This class communicates with OpenAI-compatible APIs (OpenAI, Ollama, LM Studio, etc.)
 * and properly parses both text responses and tool calls.
 * Supports any LLM that follows the OpenAI chat completion API format.
 */
public class LLMClientImpl implements LLMClient {
    
    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final OkHttpClient httpClient;
    private final Gson gson;
    
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    
    /**
     * Creates a new LLM client using Config.
     */
    public LLMClientImpl() {
        this(Config.getInstance().getApiUrl(),
             Config.getInstance().getApiKey(),
             Config.getInstance().getModel(),
             Config.getInstance().getMaxTokens(),
             Config.getInstance().getTemperature());
    }
    
    /**
     * Creates a new LLM client with custom settings.
     */
    public LLMClientImpl(String apiUrl, String apiKey, String model, int maxTokens, double temperature) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
        this.gson = new Gson();
    }
    
    @Override
    public LLMResponse sendMessage(List<Message> messages, String toolsDescription) {
        try {
            // Build request body using Gson
            Map<String, Object> requestMap = new HashMap<>();
            requestMap.put("model", model);
            requestMap.put("max_tokens", maxTokens);
            requestMap.put("temperature", temperature);
            
            // Convert messages to JSON
            List<Map<String, String>> messageList = new ArrayList<>();
            for (Message msg : messages) {
                Map<String, String> msgMap = new HashMap<>();
                msgMap.put("role", msg.getRole());
                msgMap.put("content", msg.getContent());
                messageList.add(msgMap);
            }
            requestMap.put("messages", messageList);
            
            // Add tools if provided
            if (toolsDescription != null && !toolsDescription.isEmpty()) {
                JsonArray toolsArray = JsonParser.parseString(toolsDescription).getAsJsonArray();
                requestMap.put("tools", toolsArray);
            }
            
            String requestBody = gson.toJson(requestMap);
            
            Request request = new Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, JSON_TYPE))
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "No response body";
                    System.err.println("LLM API error: " + response.code() + " - " + errorBody);
                    return new LLMResponse("Error: Failed to get response from LLM", null);
                }
                
                String responseBody = response.body() != null ? response.body().string() : "";
                return parseResponse(responseBody);
            }
        } catch (IOException e) {
            System.err.println("LLM communication error: " + e.getMessage());
            return new LLMResponse("Error: Failed to communicate with LLM: " + e.getMessage(), null);
        }
    }
    
    /**
     * Parses the LLM API JSON response using Gson.
     */
    private LLMResponse parseResponse(String jsonResponse) {
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
