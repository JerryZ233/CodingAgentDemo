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
    private long contentRevision;
    private ContextPreparation preparation;
    private com.demo.memory.ContextBudget.Report lastBudgetReport;
    
    public Context() {
        this.messages = new ArrayList<>();
        this.promptBuilder = new PromptBuilder();
        this.contextWindowStrategy = new ContextWindowStrategy();
    }

    /** Restore caller-owned history; persistence belongs to SessionService. */
    public static Context fromMessages(List<Message> history) {
        return fromMessages(history, history.size());
    }

    public static Context fromMessages(List<Message> history, long contentRevision) {
        if (contentRevision < history.size()) throw new IllegalArgumentException("Invalid content revision");
        Context context = new Context();
        context.messages.addAll(history.stream().map(Message::copyOf).toList());
        context.contentRevision = contentRevision;
        return context;
    }

    public long getContentRevision() { return contentRevision; }

    private void append(Message message) {
        long next = Math.incrementExact(contentRevision);
        messages.add(message);
        contentRevision = next;
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
     * Sets the legacy approximate history budget. An installed preparation uses its own full-request budget.
     * In the legacy path, the system prompt is preserved and does not count against this budget.
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
        return prepareForLLM(com.demo.execution.CancellationToken.none()).messages();
    }

    /** Host supplies preparation; Context itself never reads files or calls a model. */
    public void setPreparation(ContextPreparation preparation) {
        this.preparation = java.util.Objects.requireNonNull(preparation);
        lastBudgetReport = null;
    }

    public java.util.Optional<com.demo.memory.ContextBudget.Report> getLastBudgetReport() {
        return java.util.Optional.ofNullable(lastBudgetReport);
    }

    public ContextPreparation.Input prepareForLLM(com.demo.execution.CancellationToken token) {
        token.throwIfCancellationRequested();
        lastBudgetReport = null;
        try {
            ContextPreparation.Input input = preparation == null
                    ? new ContextPreparation.Input(buildLegacyMessages(), getToolDescriptions(), java.util.Optional.empty())
                    : preparation.prepare(this, token);
            token.throwIfCancellationRequested();
            lastBudgetReport = input.budget().orElse(null);
            return input;
        } catch (com.demo.memory.ContextBlockedException blocked) {
            lastBudgetReport = blocked.report().orElse(null);
            throw blocked;
        }
    }

    private List<Message> buildLegacyMessages() {
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
        append(Message.user(content));
    }
    
    /**
     * Adds an assistant message to the conversation history.
     * 
     * @param content The content of the assistant message
     */
    public void addAssistantMessage(String content) {
        append(Message.assistant(content));
    }

    public void addAssistantToolCalls(String content, List<ToolCall> toolCalls) {
        append(Message.assistantToolCalls(content, toolCalls));
    }

    public void addToolResult(ToolCall toolCall, String content) {
        append(Message.tool(toolCall.getId(), toolCall.getToolName(), content));
    }

    public void addToolResult(ToolCall toolCall, ToolResult result) {
        String error = result.isSuccess() ? null : result.getOutput();
        append(Message.tool(
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
        if (messages.isEmpty()) return;
        long next = Math.incrementExact(contentRevision);
        messages.clear();
        contentRevision = next;
    }
    
}
