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
    
    public AgentLoop(LLMClient llmClient, Map<String, Tool> tools) {
        this.llmClient = llmClient;
        this.tools = tools;
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
    public void run(Context context) {
        String toolDescriptions = context.getToolDescriptions();
        
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            System.out.println("\n=== Iteration " + (iteration + 1) + " ===");
            
            // Build complete messages including system prompt
            List<Message> messages = context.buildMessagesForLLM();
            
            LLMClient.LLMResponse response = llmClient.sendMessage(messages, toolDescriptions);
            
            if (response == null) {
                System.out.println("Failed to get response from LLM.");
                return;
            }
            
            if (!response.hasToolCalls()) {
                String finalText = response.getText();
                context.addAssistantMessage(finalText);
                System.out.println("Final response: " + finalText);
                return;
            }
            
            for (ToolCall toolCall : response.getToolCalls()) {
                executeToolCall(toolCall, context);
            }
        }
        
        System.out.println("Maximum iterations reached. Task may not be complete.");
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
    private void executeToolCall(ToolCall toolCall, Context context) {
        String toolName = toolCall.getToolName();
        Tool tool = tools.get(toolName);
        
        String resultContent;
        if (tool == null) {
            resultContent = "Error: Tool '" + toolName + "' not found.";
            System.out.println(resultContent);
        } else {
            ToolResult result = tool.execute(toolCall.getArguments());
            resultContent = result.getOutput();
            System.out.println("Tool '" + toolName + "' result: " + resultContent);
        }
        
        String toolResultMessage = "Tool " + toolName + " returned: " + resultContent;
        context.addUserMessage(toolResultMessage);
    }
    
    /**
     * Builds the tool descriptions in a format the LLM can understand.
     * This is typically a JSON schema describing each tool.
     */
    private String buildToolDescriptions() {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        boolean first = true;
        for (Tool tool : tools.values()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append("{");
            sb.append("\"type\": \"function\", ");
            sb.append("\"function\": {");
            sb.append("\"name\": \"").append(tool.getName()).append("\", ");
            sb.append("\"description\": \"").append(tool.getDescription()).append("\", ");
            sb.append("\"parameters\": {\"type\": \"object\", \"properties\": {}}");
            sb.append("}");
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }
}
