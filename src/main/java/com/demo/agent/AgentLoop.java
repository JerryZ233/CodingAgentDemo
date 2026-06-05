package com.demo.agent;

import com.demo.llm.LLMClient;
import com.demo.model.Message;
import com.demo.model.ToolCall;
import com.demo.model.ToolResult;
import com.demo.tools.Tool;
import java.util.List;
import java.util.Map;

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
    private static final int MAX_ITERATIONS = 10;
    private final int maxIterations;
    
    public AgentLoop(LLMClient llmClient, Map<String, Tool> tools) {
        this(llmClient, tools, MAX_ITERATIONS);
    }

    public AgentLoop(LLMClient llmClient, Map<String, Tool> tools, int maxIterations) {
        this.llmClient = llmClient;
        this.tools = tools;
        this.maxIterations = maxIterations;
    }
    
    /**
     * Runs the agent loop until completion.
     * 
     * Loop (up to MAX_ITERATIONS):
     *   1. Build complete context using Context (system prompt + chat history)
     *   2. Send messages to LLM with tool descriptions
     *   3. Get response (text + potential tool calls)
     *   4. If text only (no tools): add to conversation, print result, DONE
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
            System.out.println("\n=== Iteration " + (iteration + 1) + " ===");
            
            // Build complete messages including system prompt
            List<Message> messages = context.buildMessagesForLLM();
            
            LLMClient.LLMResponse response = llmClient.sendMessage(messages, toolDescriptions);
            
            if (response == null) {
                String errorText = "Failed to get response from LLM.";
                System.out.println(errorText);
                return AgentRunResult.llmError(errorText, completedIterations);
            }

            if (response.isError()) {
                String errorText = "Error: " + response.getText();
                context.addAssistantMessage(errorText);
                System.out.println(errorText);
                return AgentRunResult.llmError(errorText, completedIterations);
            }
            
            if (!response.hasToolCalls()) {
                String finalText = response.getText();
                context.addAssistantMessage(finalText);
                System.out.println("Final response: " + finalText);
                return AgentRunResult.completed(finalText, completedIterations);
            }

            context.addAssistantToolCalls(response.getText(), response.getToolCalls());
            
            for (ToolCall toolCall : response.getToolCalls()) {
                ToolResult result = executeToolCall(toolCall, context);
                if (!result.isSuccess()) {
                    return AgentRunResult.toolError(result.getOutput(), completedIterations);
                }
            }
        }
        
        String errorText = "Maximum iterations reached. Task may not be complete.";
        System.out.println(errorText);
        return AgentRunResult.maxIterations(errorText, maxIterations);
    }
    
    /**
     * Executes a single tool call and adds the result to conversation.
     * 
     * Implementation steps:
     * 1. Look up the tool by name from the tools map
     * 2. Call tool.execute() with the arguments
     * 3. Format the result as a message and add to conversation
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
        
        System.out.println("Tool '" + toolName + "' result: " + result.getOutput());
        context.addToolResult(toolCall, result);
        return result;
    }
    
}
