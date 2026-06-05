package com.demo.agent;

import com.demo.model.Message;
import com.demo.model.ToolCall;
import com.demo.model.ToolResult;
import com.demo.tools.ToolSpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Manages conversation history for the AI agent.
 * 
 * This class maintains a list of messages exchanged between the user and the AI,
 * delegates persistent storage through a ConversationStore, and builds the complete context
 * to send to the LLM including system prompt, chat history, and tool descriptions.
 * 
 * The system prompt automatically includes structured tool specs set through
 * setToolSpecs(). The older JSON tool description setter remains for
 * compatibility but does not infer prompt details from the schema.
 */
public class Context {

    private final List<Message> messages;
    private final ConversationStore conversationStore;
    private final Memory memory;
    private final PromptBuilder promptBuilder;
    private ContextWindowStrategy contextWindowStrategy;
    
    /**
     * Creates a new Context with the default conversation store.
     */
    public Context() {
        this(new ConversationMemoryAdapter());
    }
    
    /**
     * Creates a new Context with a custom Memory instance.
     * 
     * @param memory The Memory instance to use for persistence
     */
    public Context(Memory memory) {
        this(new ConversationMemoryAdapter(memory), memory);
    }

    /**
     * Creates a new Context with a custom conversation store.
     *
     * @param conversationStore The store to use for persistence
     */
    public Context(ConversationStore conversationStore) {
        this(conversationStore, extractMemory(conversationStore));
    }

    private Context(ConversationStore conversationStore, Memory memory) {
        this.messages = new ArrayList<>();
        this.conversationStore = Objects.requireNonNull(conversationStore, "conversationStore");
        this.memory = memory;
        this.promptBuilder = new PromptBuilder();
        this.contextWindowStrategy = new ContextWindowStrategy();
    }

    private static Memory extractMemory(ConversationStore conversationStore) {
        if (conversationStore instanceof ConversationMemoryAdapter adapter) {
            return adapter.getMemory();
        }
        return null;
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
        return messages;
    }
    
    /**
     * Clears all messages from the conversation history.
     */
    public void clear() {
        messages.clear();
    }
    
    /**
     * Saves the conversation history to a JSON file.
     * 
     * @param path The file path to save to
     */
    public void saveToFile(String path) {
        conversationStore.save(messages, path);
    }
    
    /**
     * Loads the conversation history from a JSON file.
     * 
     * @param path The file path to load from
     */
    public void loadFromFile(String path) {
        List<Message> loaded = conversationStore.load(path);
        messages.clear();
        messages.addAll(loaded);
    }
    
    /**
     * Returns the Memory instance used for persistence.
     * 
     * @return The Memory instance, or null when Context was created with a non-Memory store
     */
    public Memory getMemory() {
        return memory;
    }

    /**
     * Returns the store used for conversation persistence.
     *
     * @return The conversation store
     */
    public ConversationStore getConversationStore() {
        return conversationStore;
    }
}
