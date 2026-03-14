package com.demo.agent;

import com.demo.llm.LLMClient;
import com.demo.model.Message;
import com.demo.tools.Tool;
import com.demo.tools.ReadFileTool;
import com.demo.tools.WriteFileTool;
import com.demo.tools.ListFilesTool;
import com.demo.tools.RunPythonTool;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Main coding agent that orchestrates the AI coding workflow.
 * 
 * This class:
 * 1. Manages the available tools
 * 2. Creates the LLM client
 * 3. Initiates the agent loop with the user's task
 * 4. Coordinates between LLM and tool execution
 */
public class CodingAgent {
    
    private final LLMClient llmClient;
    private final Map<String, Tool> tools;
    private final AgentLoop agentLoop;
    
    /**
     * Creates a new coding agent.
     * 
     * Initializes the LLM client and registers available tools.
     */
    public CodingAgent() {
        this.llmClient = new LLMClient(
            System.getenv("LLM_API_KEY"),
            System.getenv("LLM_API_URL")
        );
        
        this.tools = new HashMap<>();
        registerTools();
        
        this.agentLoop = new AgentLoop(llmClient, tools);
    }
    
    /**
     * Registers all available tools that the agent can use.
     */
    private void registerTools() {
        tools.put("read_file", new ReadFileTool());
        tools.put("write_file", new WriteFileTool());
        tools.put("list_files", new ListFilesTool());
        tools.put("run_python", new RunPythonTool());
    }
    
    /**
     * Executes a user task using the agent loop.
     * 
     * Implementation steps:
     * 1. Create initial user message with the task
     * 2. Start the agent loop with the initial message
     * 3. Let the loop run until completion
     * 4. Return the final result
     * 
     * @param task The user's coding task (e.g., "write a fibonacci program")
     */
    public void execute(String task) {
        System.out.println("Starting agent execution...");
        
        Message initialMessage = Message.user(task);
        List<Message> conversation = new ArrayList<>();
        conversation.add(initialMessage);
        
        agentLoop.run(conversation);
        
        System.out.println("Agent execution completed.");
    }
    
    /**
     * Returns the tool descriptions formatted for the LLM.
     * This is sent to the LLM so it knows what tools are available.
     */
    public String getToolDescriptions() {
        StringBuilder sb = new StringBuilder();
        for (Tool tool : tools.values()) {
            sb.append("- ").append(tool.getName()).append(": ")
              .append(tool.getDescription()).append("\n");
        }
        return sb.toString();
    }
}
