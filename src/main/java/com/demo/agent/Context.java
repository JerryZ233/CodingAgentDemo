package com.demo.agent;

import com.demo.model.Message;
import com.demo.model.ToolCall;
import com.demo.model.ToolResult;
import com.demo.tools.ToolSpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Manages conversation history for the AI agent.
 * 
 * This class maintains a list of messages exchanged between the user and the AI,
 * and builds the complete context
 * to send to the LLM including system prompt, chat history, and tool descriptions.
 * 
 * The system prompt automatically includes structured tool specs set through
 * setToolSpecs(). The older JSON tool description setter remains for
 * compatibility but does not infer prompt details from the schema.
 */
public class Context {

    private final List<Message> messages;
    private final PromptBuilder promptBuilder;
    private ContextWindowStrategy contextWindowStrategy;
    
    public Context() {
        this.messages = new ArrayList<>();
        this.promptBuilder = new PromptBuilder();
        this.contextWindowStrategy = new ContextWindowStrategy();
    }

    /** Restore caller-owned history; persistence belongs to SessionService. */
    public static Context fromMessages(List<Message> history) {
        Context context = new Context();
        context.messages.addAll(history.stream().map(Message::copyOf).toList());
        return context;
    }

    public void setWorkspace(java.nio.file.Path workspace) {
        promptBuilder.setWorkspace(workspace);
    }

    /**
     * Builds the complete system prompt by injecting tool descriptions into the template.
     * 
     * @return The complete system prompt with tool descriptions
     */
    public String buildSystemPrompt() {
        return promptBuilder.buildSystemPrompt();
    }
    
    /**
     * Sets the system prompt template.
     * The template should contain {TOOLS} placeholder which will be replaced with tool descriptions.
     * 
     * @param systemPromptTemplate The system prompt template
     */
    public void setSystemPromptTemplate(String systemPromptTemplate) {
        promptBuilder.setSystemPromptTemplate(systemPromptTemplate);
    }
    
    /**
     * Sets the system prompt that defines the agent's role and behavior.
     * Note: This sets a complete prompt. For dynamic tool inclusion, use setSystemPromptTemplate().
     * 
     * @param systemPrompt The system prompt to use
     */
    public void setSystemPrompt(String systemPrompt) {
        promptBuilder.setSystemPromptTemplate(systemPrompt);
    }
    
    /**
     * Returns the current system prompt (with tool descriptions injected).
     * 
     * @return The complete system prompt
     */
    public String getSystemPrompt() {
        return buildSystemPrompt();
    }
    
    /**
     * Sets the tool descriptions (JSON format) for available tools.
     * These will be automatically included in the system prompt.
     * 
     * @param toolDescriptions The tool descriptions JSON
     */
    public void setToolDescriptions(String toolDescriptions) {
        promptBuilder.setToolDescriptions(toolDescriptions);
    }

    /**
     * Sets the structured tool specs for available tools.
     * These are used to render both prompt text and LLM API tool schemas.
     *
     * @param toolSpecs The available tool specs
     */
    public void setToolSpecs(Collection<ToolSpec> toolSpecs) {
        promptBuilder.setToolSpecs(toolSpecs);
    }

    /**
     * Returns the current structured tool specs.
     *
     * @return The tool specs
     */
    public List<ToolSpec> getToolSpecs() {
        return promptBuilder.getToolSpecs();
    }
    
    /**
     * Returns the current tool descriptions (raw JSON).
     * 
     * @return The tool descriptions
     */
    public String getToolDescriptions() {
        return promptBuilder.getToolDescriptions();
    }

    /**
     * Sets the approximate history budget used when building LLM messages.
     * The system prompt is always preserved and does not count against this budget.
     *
     * @param maxHistoryChars Approximate character budget for conversation history
     * @param maxHistoryMessages Maximum number of conversation messages to include
     */
    public void setContextWindowBudget(int maxHistoryChars, int maxHistoryMessages) {
        this.contextWindowStrategy = new ContextWindowStrategy(maxHistoryChars, maxHistoryMessages);
    }
    
    /**
     * Builds the complete message list to send to the LLM.
     * 
     * The messages include:
     * 1. System message (with tool descriptions injected) - defines agent role and behavior
     * 2. Chat history - previous conversation between user and assistant
     * 
     * @return The complete message list for LLM
     */
    public List<Message> buildMessagesForLLM() {
        List<Message> llmMessages = new ArrayList<>();
        
        // Build system prompt with tool descriptions injected
        String fullSystemPrompt = buildSystemPrompt();
        if (fullSystemPrompt != null && !fullSystemPrompt.isEmpty()) {
            llmMessages.add(Message.system(fullSystemPrompt));
        }
        
        // Add recent conversation messages within the context budget.
        llmMessages.addAll(contextWindowStrategy.selectHistory(messages));
        
        return llmMessages;
    }
    
    /**
     * Adds a user message to the conversation history.
     * 
     * @param content The content of the user message
     */
    public void addUserMessage(String content) {
        messages.add(Message.user(content));
    }
    
    /**
     * Adds an assistant message to the conversation history.
     * 
     * @param content The content of the assistant message
     */
    public void addAssistantMessage(String content) {
        messages.add(Message.assistant(content));
    }

    public void addAssistantToolCalls(String content, List<ToolCall> toolCalls) {
        messages.add(Message.assistantToolCalls(content, toolCalls));
    }

    public void addToolResult(ToolCall toolCall, String content) {
        messages.add(Message.tool(toolCall.getId(), toolCall.getToolName(), content));
    }

    public void addToolResult(ToolCall toolCall, ToolResult result) {
        String error = result.isSuccess() ? null : result.getOutput();
        messages.add(Message.tool(
                toolCall.getId(),
                toolCall.getToolName(),
                result.getOutput(),
                result.isSuccess(),
                error));
    }
    
    /**
     * Returns the list of messages in the conversation history.
     * 
     * @return The message list
     */
    public List<Message> getMessages() {
        return List.copyOf(messages);
    }
    
    /**
     * Clears all messages from the conversation history.
     */
    public void clear() {
        messages.clear();
    }
    
}
