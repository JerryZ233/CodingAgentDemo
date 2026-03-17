package com.demo.llm.impl;

import com.demo.model.Message;
import com.demo.model.ToolCall;
import java.util.ArrayList;
import java.util.List;

/**
 * Dummy implementation of LLMClient for testing purposes.
 * 
 * This class simulates LLM responses without making actual API calls.
 * It returns predefined responses based on the input or can be configured
 * to return specific responses for testing the agent loop.
 */
public class DummyLLMClientImpl extends LLMClientImpl {
    
    private String fixedResponse;
    private boolean returnToolCall;
    private String toolName;
    private String toolArgs;
    
    /**
     * Creates a dummy LLM client.
     */
    public DummyLLMClientImpl() {
        super("dummy-api-key", "https://dummy.api");
    }
    
    /**
     * Creates a dummy LLM client with a fixed text response.
     * 
     * @param fixedResponse The response text to return
     */
    public DummyLLMClientImpl(String fixedResponse) {
        super("dummy-api-key", "https://dummy.api");
        this.fixedResponse = fixedResponse;
    }
    
    /**
     * Creates a dummy LLM client that returns a tool call.
     * 
     * @param toolName Name of the tool to call
     * @param toolArgs Arguments for the tool (JSON format)
     */
    public DummyLLMClientImpl(String toolName, String toolArgs) {
        super("dummy-api-key", "https://dummy.api");
        this.returnToolCall = true;
        this.toolName = toolName;
        this.toolArgs = toolArgs;
    }
    
    @Override
    protected String getModel() {
        return "dummy-model";
    }
    
    @Override
    protected int getMaxTokens() {
        return 2048;
    }
    
    @Override
    protected double getTemperature() {
        return 0.7;
    }
    
    @Override
    public LLMResponse sendMessage(List<Message> messages, String toolsDescription) {
        if (returnToolCall) {
            List<ToolCall> toolCalls = new ArrayList<>();
            toolCalls.add(new ToolCall(toolName, toolArgs));
            return new LLMResponse("", toolCalls);
        }
        
        String lastMessage = "";
        if (!messages.isEmpty()) {
            lastMessage = messages.get(messages.size() - 1).getContent();
        }
        
        String response = fixedResponse != null ? fixedResponse : 
            "I have completed your task: " + lastMessage;
        
        return new LLMResponse(response, null);
    }
    
    @Override
    protected LLMResponse parseResponse(String jsonResponse) {
        return new LLMResponse("Dummy response", null);
    }
    
    /**
     * Sets the fixed response to return.
     */
    public void setFixedResponse(String response) {
        this.fixedResponse = response;
        this.returnToolCall = false;
    }
    
    /**
     * Configures the client to return a tool call on the next request.
     */
    public void setToolCall(String toolName, String toolArgs) {
        this.toolName = toolName;
        this.toolArgs = toolArgs;
        this.returnToolCall = true;
    }
}
