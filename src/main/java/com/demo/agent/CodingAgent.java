package com.demo.agent;

import com.demo.config.Config;
import com.demo.llm.LLMClient;
import com.demo.llm.impl.DummyLLMClientImpl;
import com.demo.llm.impl.OpenAILLMClient;
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
     * Creates a new coding agent with the real LLM client.
     * 
     * Initializes the LLM client from config and registers available tools.
     * Uses OpenAILLMClient if API key is configured, otherwise falls back to Dummy.
     */
    public CodingAgent() {
        this.llmClient = createLLMClient();
        
        this.tools = new HashMap<>();
        registerTools();
        
        this.agentLoop = new AgentLoop(llmClient, tools);
        this.conversation = new Context();
        
        // Set tool descriptions on context
        this.conversation.setToolDescriptions(buildToolDescriptions());
    }
    
    /**
     * Creates the LLM client based on configuration.
     * Uses OpenAI client if API key is configured, otherwise uses Dummy.
     */
    private LLMClient createLLMClient() {
        Config config = Config.getInstance();
        
        if (config.isConfigured()) {
            System.out.println("Using OpenAI client with model: " + config.getModel());
            return new OpenAILLMClient();
        } else {
            System.out.println("API key not configured. Using Dummy client.");
            System.out.println("To use real LLM, set LLM_API_KEY environment variable or configure in config.yaml");
            return new DummyLLMClientImpl();
        }
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
        
        // Create a fresh context for single execution
        Context singleContext = new Context();
        singleContext.setToolDescriptions(buildToolDescriptions());
        singleContext.addUserMessage(task);
        
        agentLoop.run(singleContext);
        
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
        
        // Run agent loop with the conversation context
        agentLoop.run(conversation);
        
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
        return buildToolDescriptions();
    }
    
    /**
     * Builds the tool descriptions in JSON format for the LLM.
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
