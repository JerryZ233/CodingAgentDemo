package com.demo.llm.impl;

import com.demo.config.Config;
import com.demo.llm.LLMClient;
import com.demo.model.ToolCall;
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
     * Parses the OpenAI API JSON response.
     * 
     * Expected response format:
     * {
     *   "choices": [
     *     {
     *       "message": {
     *         "content": "text response",
     *         "tool_calls": [
     *           {
     *             "id": "call_123",
     *             "type": "function",
     *             "function": {
     *               "name": "tool_name",
     *               "arguments": "{\"arg1\": \"value1\"}"
     *             }
     *           }
     *         ]
     *       }
     *     }
     *   ]
     * }
     */
    @Override
    protected LLMResponse parseResponse(String jsonResponse) {
        if (jsonResponse == null || jsonResponse.isEmpty()) {
            return new LLMResponse("Error: Empty response from LLM", null);
        }
        
        try {
            // Extract text content
            String text = extractJsonField(jsonResponse, "content");
            if (text == null) {
                text = "";
            }
            
            // Extract tool calls
            List<ToolCall> toolCalls = extractToolCalls(jsonResponse);
            
            return new LLMResponse(text, toolCalls);
        } catch (Exception e) {
            System.err.println("Failed to parse LLM response: " + e.getMessage());
            System.err.println("Response: " + jsonResponse);
            return new LLMResponse("Error: Failed to parse LLM response: " + e.getMessage(), null);
        }
    }
    
    /**
     * Extracts tool calls from the JSON response.
     */
    private List<ToolCall> extractToolCalls(String json) {
        List<ToolCall> toolCalls = new ArrayList<>();
        
        // Find "tool_calls" array
        int toolCallsStart = json.indexOf("\"tool_calls\"");
        if (toolCallsStart == -1) {
            return toolCalls;
        }
        
        // Find the opening bracket of the array
        int arrayStart = json.indexOf("[", toolCallsStart);
        int arrayEnd = json.indexOf("]", toolCallsStart);
        if (arrayStart == -1 || arrayEnd == -1) {
            return toolCalls;
        }
        
        String toolCallsArray = json.substring(arrayStart + 1, arrayEnd);
        
        // Parse each tool call object
        int pos = 0;
        while (pos < toolCallsArray.length()) {
            int objStart = toolCallsArray.indexOf("{", pos);
            if (objStart == -1 || objStart > arrayEnd) break;
            
            int objEnd = toolCallsArray.indexOf("}", objStart);
            if (objEnd == -1) break;
            
            String toolCallObj = toolCallsArray.substring(objStart, objEnd + 1);
            
            // Extract function name and arguments
            String functionName = extractFunctionName(toolCallObj);
            String arguments = extractFunctionArguments(toolCallObj);
            
            if (functionName != null) {
                toolCalls.add(new ToolCall(functionName, arguments));
            }
            
            pos = objEnd + 1;
        }
        
        return toolCalls;
    }
    
    /**
     * Extracts function name from tool call object.
     */
    private String extractFunctionName(String toolCallObj) {
        int funcStart = toolCallObj.indexOf("\"function\"");
        if (funcStart == -1) return null;
        
        int nameStart = toolCallObj.indexOf("\"name\"", funcStart);
        if (nameStart == -1) return null;
        
        return extractQuotedValue(toolCallObj, nameStart + 6);
    }
    
    /**
     * Extracts function arguments from tool call object.
     */
    private String extractFunctionArguments(String toolCallObj) {
        int funcStart = toolCallObj.indexOf("\"function\"");
        if (funcStart == -1) return "{}";
        
        int argsStart = toolCallObj.indexOf("\"arguments\"", funcStart);
        if (argsStart == -1) return "{}";
        
        String args = extractQuotedValue(toolCallObj, argsStart + 12);
        return args != null ? args : "{}";
    }
    
    /**
     * Extracts a quoted string value from JSON.
     */
    private String extractQuotedValue(String json, int afterIndex) {
        int valueStart = json.indexOf("\"", afterIndex);
        if (valueStart == -1) return null;
        
        // Skip escaped quotes
        StringBuilder sb = new StringBuilder();
        int i = valueStart + 1;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                sb.append(c);
                sb.append(json.charAt(i + 1));
                i += 2;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
    
    /**
     * Extracts a field value from JSON using simple parsing.
     */
    private String extractJsonField(String json, String fieldName) {
        String searchKey = "\"" + fieldName + "\"";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex == -1) return null;
        
        int colonIndex = json.indexOf(":", keyIndex);
        if (colonIndex == -1) return null;
        
        int valueStart = colonIndex + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }
        
        if (valueStart >= json.length()) return null;
        
        if (json.charAt(valueStart) == '"') {
            return extractQuotedValue(json, valueStart);
        }
        
        return null;
    }
}
