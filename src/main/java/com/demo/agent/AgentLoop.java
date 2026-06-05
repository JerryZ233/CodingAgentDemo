package com.demo.agent;

import com.demo.llm.LLMClient;
import com.demo.model.Message;
import com.demo.model.ToolCall;
import com.demo.model.ToolResult;
import com.demo.tools.Tool;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The core agent loop that implements the think-decide-execute-observe cycle.
 * 
 * This class demonstrates the fundamental workflow of an AI coding agent:
 * 
 * 1. THINK: Send messages to LLM to get a response
 * 2. DECIDE: Parse the response to see if tools should be called
 * 3. EXECUTE: If tool call, execute the tool
 * 4. OBSERVE: Add the result back to conversation
 * 5. REPEAT: Continue until task is complete
 * 
 * The loop continues until:
 * - The LLM returns a final text response (no more tool calls)
 * - Maximum iterations reached (to prevent infinite loops)
 * - Error occurs
 */
public class AgentLoop {
    
    private final LLMClient llmClient;
    private final Map<String, Tool> tools;
    private final AgentObserver observer;
    private static final int MAX_ITERATIONS = 10;
    private final int maxIterations;
    
    public AgentLoop(LLMClient llmClient, Map<String, Tool> tools) {
        this(llmClient, tools, MAX_ITERATIONS);
    }

    public AgentLoop(LLMClient llmClient, Map<String, Tool> tools, int maxIterations) {
        this(llmClient, tools, maxIterations, AgentObserver.noop());
    }

    public AgentLoop(LLMClient llmClient, Map<String, Tool> tools, AgentObserver observer) {
        this(llmClient, tools, MAX_ITERATIONS, observer);
    }

    public AgentLoop(LLMClient llmClient, Map<String, Tool> tools, int maxIterations, AgentObserver observer) {
        this.llmClient = llmClient;
        this.tools = tools;
        this.maxIterations = maxIterations;
        this.observer = Objects.requireNonNull(observer, "observer");
    }
    
    /**
     * Runs the agent loop until completion.
     * 
     * Loop (up to MAX_ITERATIONS):
     *   1. Build complete context using Context (system prompt + chat history)
     *   2. Send messages to LLM with tool descriptions
     *   3. Get response (text + potential tool calls)
     *   4. If text only (no tools): add to conversation, notify observer, DONE
     *   5. If tool calls:
     *        a. Add assistant message with tool call to conversation
     *        b. For each tool call:
     *             - Find the tool by name
     *             - Execute with provided arguments
     *             - Add tool result to conversation
     *        c. Continue to next iteration
     * 
     * @param context The Context containing conversation history and configuration
     */
    public AgentRunResult run(Context context) {
        String toolDescriptions = context.getToolDescriptions();
        
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            int completedIterations = iteration + 1;
            observer.onIterationStarted(completedIterations);
            
            // Build complete messages including system prompt
            List<Message> messages = context.buildMessagesForLLM();
            
            LLMClient.LLMResponse response = llmClient.sendMessage(messages, toolDescriptions);
            
            if (response == null) {
                String errorText = "Failed to get response from LLM.";
                observer.onLlmError(errorText);
                return AgentRunResult.llmError(errorText, completedIterations);
            }

            if (response.isError()) {
                String errorText = "Error: " + response.getText();
                context.addAssistantMessage(errorText);
                observer.onLlmError(errorText);
                return AgentRunResult.llmError(errorText, completedIterations);
            }
            
            if (!response.hasToolCalls()) {
                String finalText = response.getText();
                context.addAssistantMessage(finalText);
                observer.onFinalResponse(finalText);
                return AgentRunResult.completed(finalText, completedIterations);
            }

            context.addAssistantToolCalls(response.getText(), response.getToolCalls());
            
            for (ToolCall toolCall : response.getToolCalls()) {
                executeToolCall(toolCall, context);
            }
        }
        
        String errorText = "Maximum iterations reached. Task may not be complete.";
        observer.onMaxIterations(errorText);
        return AgentRunResult.maxIterations(errorText, maxIterations);
    }
    
    /**
     * Executes a single tool call and adds the result to conversation.
     * 
     * Implementation steps:
     * 1. Look up the tool by name from the tools map
     * 2. Call tool.execute() with the arguments
     * 3. Add a structured tool result message to conversation
     * 4. Handle errors gracefully
     */
    private ToolResult executeToolCall(ToolCall toolCall, Context context) {
        String toolName = toolCall.getToolName();
        Tool tool = tools.get(toolName);
        
        ToolResult result;
        if (tool == null) {
            result = ToolResult.error(toolName, "Error: Tool '" + toolName + "' not found.");
        } else {
            try {
                result = tool.execute(toolCall.getArguments());
                if (result == null) {
                    result = ToolResult.error(toolName, "Error: Tool '" + toolName + "' returned no result.");
                }
            } catch (Exception e) {
                result = ToolResult.error(toolName, "Error: Tool '" + toolName + "' failed: " + e.getMessage());
            }
        }
        
        observer.onToolResult(toolName, result);
        context.addToolResult(toolCall, result);
        return result;
    }
    
}
