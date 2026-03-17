package com.demo.llm.impl;

import com.demo.llm.LLMClient;
import com.demo.model.Message;
import com.demo.model.ToolCall;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.io.IOException;

/**
 * Abstract implementation of LLMClient for real LLM integrations.
 * 
 * This class provides the base functionality for communicating with
 * Large Language Models via HTTP APIs (OpenAI, Anthropic, etc.).
 * 
 * Implementation steps:
 * 1. Build JSON request body with messages and available tools
 * 2. Send POST request to LLM API
 * 3. Parse JSON response
 * 4. Return text content and/or tool calls
 */
public abstract class LLMClientImpl implements LLMClient {
    
    protected final String apiKey;
    protected final String apiUrl;
    protected final OkHttpClient httpClient;
    
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    
    /**
     * Creates a new LLM client implementation.
     * 
     * @param apiKey API key for authentication
     * @param apiUrl Base URL for the LLM API
     */
    public LLMClientImpl(String apiKey, String apiUrl) {
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        this.httpClient = new OkHttpClient();
    }
    
    /**
     * Returns the model name to use.
     */
    protected abstract String getModel();
    
    /**
     * Returns the maximum number of tokens to generate.
     */
    protected abstract int getMaxTokens();
    
    /**
     * Returns the temperature for sampling.
     */
    protected abstract double getTemperature();
    
    /**
     * Sends messages to the LLM and receives a response.
     * 
     * @param messages Conversation history
     * @param toolsDescription Description of available tools
     * @return Response containing text and/or tool calls
     */
    @Override
    public LLMResponse sendMessage(List<Message> messages, String toolsDescription) {
        try {
            String requestBody = buildRequestBody(messages, toolsDescription);
            
            Request request = new Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, JSON_TYPE))
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    System.err.println("LLM API error: " + response.code() + " - " + response.body().string());
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
     * Parses the JSON response from the LLM.
     * Subclasses should override this to customize parsing logic.
     * 
     * @param jsonResponse Raw JSON response from the LLM
     * @return Parsed LLMResponse
     */
    protected abstract LLMResponse parseResponse(String jsonResponse);
    
    /**
     * Builds the JSON request body for the LLM API.
     * 
     * @param messages Conversation history
     * @param toolsDescription Description of available tools
     * @return JSON string request body
     */
    protected String buildRequestBody(List<Message> messages, String toolsDescription) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        
        // Add model
        sb.append("\"model\": \"").append(getModel()).append("\", ");
        
        // Add messages
        sb.append("\"messages\": [");
        boolean first = true;
        for (Message msg : messages) {
            if (!first) sb.append(", ");
            first = false;
            sb.append("{");
            sb.append("\"role\": \"").append(msg.getRole()).append("\", ");
            sb.append("\"content\": \"").append(escapeJson(msg.getContent())).append("\"");
            sb.append("}");
        }
        sb.append("]");
        
        // Add tools if provided
        if (toolsDescription != null && !toolsDescription.isEmpty()) {
            sb.append(", \"tools\": ").append(toolsDescription);
        }
        
        // Add generation parameters
        sb.append(", \"max_tokens\": ").append(getMaxTokens());
        sb.append(", \"temperature\": ").append(getTemperature());
        
        sb.append("}");
        return sb.toString();
    }
    
    /**
     * Escapes special characters for JSON string.
     */
    protected String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
}
