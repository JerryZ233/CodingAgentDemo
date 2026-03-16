package com.demo.agent;

import com.demo.llm.LLMClient;
import com.demo.llm.impl.DummyLLMClientImpl;
import com.demo.model.Message;
import com.demo.tools.Tool;
import com.demo.tools.FileReadTool;
import com.demo.tools.FileWriteTool;
import com.demo.tools.FileListTool;
import com.demo.tools.ShellRunTool;
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
    private Context conversation;
    
    /**
     * Creates a new coding agent.
     * 
     * Initializes the LLM client and registers available tools.
     */
    public CodingAgent() {
        this.llmClient = new DummyLLMClientImpl();
        
        this.tools = new HashMap<>();
        registerTools();
        
        this.agentLoop = new AgentLoop(llmClient, tools);
        this.conversation = new Context();
    }
    
    /**
     * Registers all available tools that the agent can use.
     */
    private void registerTools() {
        tools.put("read_file", new FileReadTool());
        tools.put("write_file", new FileWriteTool());
        tools.put("list_files", new FileListTool());
        tools.put("run_shell", new ShellRunTool());
    }
    
    /**
     * Executes a user task using the agent loop.
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
     * Executes a user task using the existing conversation history.
     * 
     * This method maintains conversation context across multiple calls,
     * allowing for multi-turn conversations with the agent.
     * 
     * @param task The user's coding task
     */
    public void executeWithHistory(String task) {
        System.out.println("Starting agent execution with history...");
        
        // Add user message to conversation context
        conversation.addUserMessage(task);
        
        // Run agent loop with the conversation history
        agentLoop.run(conversation.getMessages());
        
        System.out.println("Agent execution completed.");
    }
    
    /**
     * Returns the current conversation context.
     * 
     * @return The Context containing conversation history
     */
    public Context getConversation() {
        return conversation;
    }
    
    /**
     * Sets the conversation context (e.g., to restore from a saved session).
     * 
     * @param conversation The Context to use
     */
    public void setConversation(Context conversation) {
        this.conversation = conversation;
    }
    
    /**
     * Returns the tool descriptions formatted for the LLM.
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
