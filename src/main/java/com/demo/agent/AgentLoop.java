package com.demo.agent;

import com.demo.llm.LLMClient;
import com.demo.model.Message;
import com.demo.model.Tool;
import com.demo.model.ToolCall;
import com.demo.model.ToolResult;
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
     * Implementation idea:
     * 
     * Loop (up to MAX_ITERATIONS):
     *   1. Send current conversation to LLM
     *   2. Get response (text + potential tool calls)
     *   3. If text only (no tools): add to conversation, print result, DONE
     *   4. If tool calls:
     *        a. Add assistant message with tool call to conversation
     *        b. For each tool call:
     *             - Find the tool by name
     *             - Execute with provided arguments
     *             - Add tool result to conversation
     *        c. Continue to next iteration
     * 
     * @param conversation The message history (modified in place)
     */
    public void run(List<Message> conversation) {
        // TODO: implement the agent loop
        
        // Step 1: Prepare tool descriptions for the LLM
        String toolDescriptions = buildToolDescriptions();
        
        // Main loop
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            System.out.println("\n=== Iteration " + (iteration + 1) + " ===");
            
            // STEP 1: THINK - Send messages to LLM
            // llmResponse = llmClient.sendMessage(conversation, toolDescriptions);
            
            // STEP 2: DECIDE - Parse response
            // if (!llmResponse.hasToolCalls()) {
            //     // No tool calls, this is the final response
            //     String finalText = llmResponse.getText();
            //     conversation.add(Message.assistant(finalText));
            //     System.out.println("Final response: " + finalText);
            //     return; // DONE!
            // }
            
            // STEP 3: EXECUTE - Run each tool call
            // for (ToolCall toolCall : llmResponse.getToolCalls()) {
            //     executeToolCall(toolCall, conversation);
            // }
            
            // STEP 4: OBSERVE - Loop continues, result already added to conversation
        }
        
        System.out.println("Maximum iterations reached. Task may not be complete.");
    }
    
    /**
     * Executes a single tool call and adds the result to conversation.
     * 
     * Implementation idea:
     * 1. Look up the tool by name from the tools map
     * 2. Call tool.execute() with the arguments
     * 3. Format the result as a message and add to conversation
     * 4. Handle errors gracefully
     */
    private void executeToolCall(ToolCall toolCall, List<Message> conversation) {
        // TODO: implement
        // Implementation steps:
        // 1. Get tool name: String toolName = toolCall.getToolName();
        // 2. Get tool from map: Tool tool = tools.get(toolName);
        // 3. If tool not found: return error result
        // 4. Execute: ToolResult result = tool.execute(toolCall.getArguments());
        // 5. Format result as message: "Tool X returned: " + result.getOutput()
        // 6. Add to conversation for next iteration
    }
    
    /**
     * Builds the tool descriptions in a format the LLM can understand.
     * This is typically a JSON schema describing each tool.
     */
    private String buildToolDescriptions() {
        // TODO: implement
        // Implementation idea:
        // Return JSON array of tool definitions, e.g.:
        // [
        //   {
        //     "type": "function",
        //     "function": {
        //       "name": "read_file",
        //       "description": "Reads content from a file",
        //       "parameters": {
        //         "type": "object",
        //         "properties": {
        //           "path": {"type": "string", "description": "The file path"}
        //         },
        //         "required": ["path"]
        //       }
        //     }
        //   }
        // ]
        return "[]";
    }
}
