package com.demo.agent;

import com.demo.config.Config;
import com.demo.execution.CancellationToken;
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
        this(LLMClientFactory.create(config, observer), ToolRegistry.fromConfig(config),
                config.getMaxIterations(), observer);
        this.conversation = new Context();
        this.conversation.setToolSpecs(buildToolSpecs());
    }

    /** Stateless execution path used by the session backend. Client ownership stays with its caller. */
    public CodingAgent(LLMClient llmClient, ToolRegistry toolRegistry, int maxIterations) {
        this(llmClient, toolRegistry, maxIterations, AgentObserver.noop());
    }

    private CodingAgent(LLMClient llmClient, ToolRegistry toolRegistry, int maxIterations, AgentObserver observer) {
        this.observer = Objects.requireNonNull(observer, "observer");
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.tools = toolRegistry.getTools();
        if (maxIterations <= 0) throw new IllegalArgumentException("maxIterations must be positive");
        this.agentLoop = new AgentLoop(llmClient, tools, maxIterations, observer);
    }

    public AgentRunResult runTurn(Context context, AgentCheckpoint checkpoint) {
        context.setToolSpecs(buildToolSpecs());
        return agentLoop.run(context, checkpoint);
    }

    public AgentRunResult runTurn(Context context, AgentCheckpoint checkpoint, CancellationToken token, AgentEventLog events) {
        context.setToolSpecs(buildToolSpecs());
        return agentLoop.run(context, checkpoint, token, events);
    }
    
    /**
     * Executes a user task using the agent loop.
     * 
     * @param task The user's coding task (e.g., "write a fibonacci program")
     */
    public AgentRunResult execute(String task) {
        observer.onAgentStarted("Starting agent execution...");
        
        // Create a fresh context for single execution
        Context singleContext = new Context();
        singleContext.setToolSpecs(buildToolSpecs());
        singleContext.addUserMessage(task);
        
        AgentRunResult result = agentLoop.run(singleContext);
        
        observer.onAgentCompleted("Agent execution completed.");
        return result;
    }
    
    /**
     * Executes a user task using the existing conversation history.
     * 
     * This method maintains conversation context across multiple calls,
     * allowing for multi-turn conversations with the agent.
     * 
     * @param task The user's coding task
     */
    public AgentRunResult executeWithHistory(String task) {
        observer.onAgentStarted("Starting agent execution with history...");
        
        // Add user message to conversation context
        getConversation().addUserMessage(task);
        
        // Run agent loop with the conversation context
        AgentRunResult result = agentLoop.run(conversation);
        
        observer.onAgentCompleted("Agent execution completed.");
        return result;
    }
    
    /**
     * Returns the current conversation context.
     * 
     * @return The Context containing conversation history
     */
    public Context getConversation() {
        // Transitional CLI API; session execution never uses this context.
        if (conversation == null) {
            conversation = new Context();
            conversation.setToolSpecs(buildToolSpecs());
        }
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
