package com.demo.agent;

import com.demo.config.Config;
import com.demo.llm.LLMClient;
import com.demo.tools.Tool;
import com.demo.llm.LLMClientFactory;
import com.demo.tools.ToolDescriptions;
import com.demo.tools.ToolRegistry;
import com.demo.tools.ToolSpec;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
    private final ToolRegistry toolRegistry;
    private final AgentLoop agentLoop;
    private final Config config;
    private final AgentObserver observer;
    private Context conversation;
    
    /**
     * Creates a new coding agent with the real LLM client.
     * 
     * Initializes the LLM client from config and registers available tools.
     * Uses OpenAILLMClient if API key is configured, otherwise falls back to Dummy.
     */
    public CodingAgent() {
        this(Config.getInstance());
    }

    public CodingAgent(Config config) {
        this(config, new ConsoleAgentObserver());
    }

    public CodingAgent(Config config, AgentObserver observer) {
        this.config = config;
        this.observer = Objects.requireNonNull(observer, "observer");
        this.llmClient = LLMClientFactory.create(config, observer);
        this.toolRegistry = ToolRegistry.fromConfig(config);
        this.tools = toolRegistry.getTools();
        
        this.agentLoop = new AgentLoop(llmClient, tools, config.getMaxIterations(), observer);
        this.conversation = new Context();
        
        // Set tool descriptions on context
        this.conversation.setToolSpecs(buildToolSpecs());
    }
    
    /**
     * Executes a user task using the agent loop.
     * 
     * @param task The user's coding task (e.g., "write a fibonacci program")
     */
    public void execute(String task) {
        observer.onAgentStarted("Starting agent execution...");
        
        // Create a fresh context for single execution
        Context singleContext = new Context();
        singleContext.setToolSpecs(buildToolSpecs());
        singleContext.addUserMessage(task);
        
        agentLoop.run(singleContext);
        
        observer.onAgentCompleted("Agent execution completed.");
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
        observer.onAgentStarted("Starting agent execution with history...");
        
        // Add user message to conversation context
        conversation.addUserMessage(task);
        
        // Run agent loop with the conversation context
        agentLoop.run(conversation);
        
        observer.onAgentCompleted("Agent execution completed.");
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
        this.conversation.setToolSpecs(buildToolSpecs());
    }
    
    /**
     * Returns the tool descriptions formatted for the LLM.
     */
    public String getToolDescriptions() {
        return ToolDescriptions.toOpenAIToolsJson(buildToolSpecs());
    }
    
    /**
     * Builds structured tool specs used by both prompts and API tool schemas.
     */
    private List<ToolSpec> buildToolSpecs() {
        return toolRegistry.getToolSpecs();
    }
}
