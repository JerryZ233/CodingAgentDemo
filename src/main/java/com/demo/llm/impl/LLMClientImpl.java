package com.demo.llm.impl;

import com.demo.llm.LLMClient;
import com.demo.model.Message;
import com.demo.model.ToolCall;
import java.util.List;

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
    
    /**
     * Creates a new LLM client implementation.
     * 
     * @param apiKey API key for authentication
     * @param apiUrl Base URL for the LLM API
     */
    public LLMClientImpl(String apiKey, String apiUrl) {
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
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
        
        if (toolsDescription != null && !toolsDescription.isEmpty()) {
            sb.append(", \"tools\": ").append(toolsDescription);
        }
        
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
