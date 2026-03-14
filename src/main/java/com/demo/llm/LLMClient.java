package com.demo.llm;

import com.demo.model.Message;
import com.demo.model.ToolCall;
import java.util.List;
import java.util.Map;

/**
 * Client for communicating with Large Language Models.
 * 
 * This class handles:
 * 1. Sending messages to the LLM
 * 2. Receiving responses (text and/or tool calls)
 * 3. Managing the conversation history
 * 
 * Implementation idea:
 * - Use HTTP client (e.g., OkHttp or built-in HttpURLConnection)
 * - Send requests to LLM API (OpenAI, Anthropic, etc.)
 * - Parse JSON response to extract text and tool calls
 */
public class LLMClient {
    
    private final String apiKey;
    private final String apiUrl;
    
    /**
     * Creates a new LLM client.
     * 
     * @param apiKey API key for authentication
     * @param apiUrl Base URL for the LLM API
     */
    public LLMClient(String apiKey, String apiUrl) {
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
    }
    
    /**
     * Sends messages to the LLM and receives a response.
     * 
     * Implementation idea:
     * 1. Build JSON request body with messages and available tools
     * 2. Send POST request to LLM API
     * 3. Parse JSON response
     * 4. Return text content and/or tool calls
     * 
     * @param messages Conversation history
     * @param toolsDescription Description of available tools
     * @return Response containing text and/or tool calls
     */
    public LLMResponse sendMessage(List<Message> messages, String toolsDescription) {
        // TODO: implement
        // Implementation steps:
        // 1. Construct JSON request body:
        //    {
        //      "messages": [...],
        //      "tools": [tool1_schema, tool2_schema, ...]
        //    }
        // 2. Send HTTP POST to apiUrl with Authorization header
        // 3. Parse response JSON:
        //    - Extract "content" field for text response
        //    - Extract "tool_calls" array if present
        // 4. Return LLMResponse object
        
        return null; // TODO: return actual response
    }
    
    /**
     * Response from the LLM containing text and/or tool calls.
     */
    public static class LLMResponse {
        private final String text;
        private final List<ToolCall> toolCalls;
        
        public LLMResponse(String text, List<ToolCall> toolCalls) {
            this.text = text;
            this.toolCalls = toolCalls;
        }
        
        public String getText() {
            return text;
        }
        
        public List<ToolCall> getToolCalls() {
            return toolCalls;
        }
        
        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }
}
