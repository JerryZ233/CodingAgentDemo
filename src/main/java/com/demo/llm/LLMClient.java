package com.demo.llm;

import com.demo.model.Message;
import com.demo.model.ToolCall;
import java.util.List;

/**
 * Client for communicating with Large Language Models.
 * 
 * This interface defines the contract for:
 * 1. Sending messages to the LLM
 * 2. Receiving responses (text and/or tool calls)
 * 3. Managing the conversation history
 */
public interface LLMClient {
    
    /**
     * Sends messages to the LLM and receives a response.
     * 
     * @param messages Conversation history
     * @param toolsDescription Description of available tools
     * @return Response containing text and/or tool calls
     */
    LLMResponse sendMessage(List<Message> messages, String toolsDescription);
    
    /**
     * Response from the LLM containing text and/or tool calls.
     */
    class LLMResponse {
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
