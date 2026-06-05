package com.demo.llm.impl;

import com.demo.config.Config;
import com.demo.llm.LLMClient;
import com.demo.model.Message;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.io.IOException;
import java.util.List;
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
    private final OpenAIChatAdapter chatAdapter;
    private final OpenAIResponseParser responseParser;
    
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    
    /**
     * Creates a new LLM client using Config.
     */
    public LLMClientImpl() {
        this(Config.getInstance());
    }

    public LLMClientImpl(Config config) {
        this(config.getApiUrl(),
             config.getApiKey(),
             config.getModel(),
             config.getMaxTokens(),
             config.getTemperature());
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
        this.chatAdapter = new OpenAIChatAdapter();
        this.responseParser = new OpenAIResponseParser();
    }
    
    @Override
    public LLMResponse sendMessage(List<Message> messages, String toolsDescription) {
        try {
            String requestBody = chatAdapter.buildRequestBody(messages, toolsDescription, model, maxTokens, temperature);
            
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
                    return LLMResponse.error("Failed to get response from LLM: HTTP " + response.code());
                }
                
                String responseBody = response.body() != null ? response.body().string() : "";
                return parseResponse(responseBody);
            }
        } catch (IOException e) {
            System.err.println("LLM communication error: " + e.getMessage());
            return LLMResponse.error("Failed to communicate with LLM: " + e.getMessage());
        }
    }
    
    /**
     * Parses the LLM API JSON response.
     */
    LLMResponse parseResponse(String jsonResponse) {
        return responseParser.parse(jsonResponse);
    }
}
