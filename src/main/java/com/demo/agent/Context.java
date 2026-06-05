package com.demo.agent;

import com.demo.model.Message;
import com.demo.model.ToolCall;
import com.demo.model.ToolResult;
import com.demo.tools.ToolDescriptions;
import com.demo.tools.ToolSpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Manages conversation history for the AI agent.
 * 
 * This class maintains a list of messages exchanged between the user and the AI,
 * delegates persistent storage through a ConversationStore, and builds the complete context
 * to send to the LLM including system prompt, chat history, and tool descriptions.
 * 
 * The system prompt automatically includes the tool descriptions that are set
 * via setToolDescriptions(), so new tools will be automatically included.
 */
public class Context {
    
    private static final String TOOLS_PLACEHOLDER = "{TOOLS}";
    private static final int DEFAULT_MAX_HISTORY_CHARS = 64_000;
    private static final int DEFAULT_MAX_HISTORY_MESSAGES = 100;
    
    private final List<Message> messages;
    private final ConversationStore conversationStore;
    private final Memory memory;
    private String systemPromptTemplate;
    private String toolDescriptions;
    private List<ToolSpec> toolSpecs;
    private int maxHistoryChars;
    private int maxHistoryMessages;
    
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
        this.systemPromptTemplate = getDefaultSystemPromptTemplate();
        this.toolDescriptions = "";
        this.toolSpecs = List.of();
        this.maxHistoryChars = DEFAULT_MAX_HISTORY_CHARS;
        this.maxHistoryMessages = DEFAULT_MAX_HISTORY_MESSAGES;
    }

    private static Memory extractMemory(ConversationStore conversationStore) {
        if (conversationStore instanceof ConversationMemoryAdapter adapter) {
            return adapter.getMemory();
        }
        return null;
    }
    
    /**
     * Returns the default system prompt template.
     * The placeholder {TOOLS} will be replaced with actual tool descriptions.
     */
    private String getDefaultSystemPromptTemplate() {
        return "You are an AI coding assistant with the ability to execute tools to accomplish coding tasks.\n\n" +
               "## Your Role\n" +
               "You help users with programming tasks by understanding their requirements, writing code,\n" +
               "executing commands, and managing files using the available tools.\n\n" +
               "## Available Tools\n" +
               "You have access to the following tools:\n" +
               TOOLS_PLACEHOLDER + "\n\n" +
               "## Tool Use\n" +
               "When a task requires reading files, writing files, listing directories, or running another available tool,\n" +
               "use the tool calling mechanism provided by the API. Do not describe a tool call in prose.\n" +
               "After receiving the tool result, decide whether another tool call is needed or provide your final answer.\n\n" +
               "## Workflow\n" +
               "For each user request, follow this cycle:\n" +
               "1. THINK: Analyze the request and determine what needs to be done\n" +
               "2. DECIDE: Decide if you need to use tools or can answer directly\n" +
               "3. EXECUTE: If using tools, call the appropriate API tool\n" +
               "4. OBSERVE: Read the tool result returned to you\n" +
               "5. RESPOND: Provide a clear response to the user\n\n" +
               "## Tool Calling Rules\n" +
               "- Use tools ONLY when necessary to complete the user's request\n" +
               "- If the user asks a question you can answer directly (like general knowledge),\n" +
               "  respond without using tools\n" +
               "- If the user asks you to create files, run commands, read files, or perform\n" +
               "  any action that requires tool use, call the appropriate tool\n" +
               "- Always check tool results before providing final response\n\n" +
               "## Response Guidelines\n" +
               "- Be concise but informative\n" +
               "- Explain what you're going to do before doing it\n" +
               "- Show the user the results of tool executions\n" +
               "- If something goes wrong, explain the error and try to fix it\n\n" +
               "## Constraints\n" +
               "- All file operations must be within the designated workspace directory\n" +
               "- Do not execute destructive commands (rm -rf, format, etc.)\n" +
               "- Always confirm potentially dangerous operations with the user first";
    }
    
    /**
     * Builds the complete system prompt by injecting tool descriptions into the template.
     * 
     * @return The complete system prompt with tool descriptions
     */
    public String buildSystemPrompt() {
        if (systemPromptTemplate == null) {
            return "";
        }
        
        // Format tool descriptions as a readable list
        String formattedTools = formatToolDescriptionsForPrompt();
        
        // Replace placeholder with actual tool descriptions
        return systemPromptTemplate.replace(TOOLS_PLACEHOLDER, formattedTools);
    }
    
    /**
     * Formats tool descriptions for inclusion in the system prompt.
     * Uses structured tool specs so the prompt and API schema share one source.
     * 
     * @return Formatted tool descriptions string
     */
    private String formatToolDescriptionsForPrompt() {
        if (toolSpecs != null && !toolSpecs.isEmpty()) {
            return ToolDescriptions.toPromptText(toolSpecs);
        }

        if (toolDescriptions != null && !toolDescriptions.isEmpty()) {
            return toolDescriptions;
        }

        return "(No tools available)";
    }
    
    /**
     * Sets the system prompt template.
     * The template should contain {TOOLS} placeholder which will be replaced with tool descriptions.
     * 
     * @param systemPromptTemplate The system prompt template
     */
    public void setSystemPromptTemplate(String systemPromptTemplate) {
        this.systemPromptTemplate = systemPromptTemplate;
    }
    
    /**
     * Sets the system prompt that defines the agent's role and behavior.
     * Note: This sets a complete prompt. For dynamic tool inclusion, use setSystemPromptTemplate().
     * 
     * @param systemPrompt The system prompt to use
     */
    public void setSystemPrompt(String systemPrompt) {
        this.systemPromptTemplate = systemPrompt;
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
        this.toolDescriptions = toolDescriptions;
        this.toolSpecs = List.of();
    }

    /**
     * Sets the structured tool specs for available tools.
     * These are used to render both prompt text and LLM API tool schemas.
     *
     * @param toolSpecs The available tool specs
     */
    public void setToolSpecs(Collection<ToolSpec> toolSpecs) {
        this.toolSpecs = toolSpecs == null ? List.of() : List.copyOf(toolSpecs);
        this.toolDescriptions = ToolDescriptions.toOpenAIToolsJson(this.toolSpecs);
    }

    /**
     * Returns the current structured tool specs.
     *
     * @return The tool specs
     */
    public List<ToolSpec> getToolSpecs() {
        return toolSpecs;
    }
    
    /**
     * Returns the current tool descriptions (raw JSON).
     * 
     * @return The tool descriptions
     */
    public String getToolDescriptions() {
        return toolDescriptions;
    }

    /**
     * Sets the approximate history budget used when building LLM messages.
     * The system prompt is always preserved and does not count against this budget.
     *
     * @param maxHistoryChars Approximate character budget for conversation history
     * @param maxHistoryMessages Maximum number of conversation messages to include
     */
    public void setContextWindowBudget(int maxHistoryChars, int maxHistoryMessages) {
        if (maxHistoryChars <= 0) {
            throw new IllegalArgumentException("maxHistoryChars must be positive");
        }
        if (maxHistoryMessages <= 0) {
            throw new IllegalArgumentException("maxHistoryMessages must be positive");
        }
        this.maxHistoryChars = maxHistoryChars;
        this.maxHistoryMessages = maxHistoryMessages;
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
        llmMessages.addAll(trimHistoryForLLM());
        
        return llmMessages;
    }

    private List<Message> trimHistoryForLLM() {
        List<List<Message>> blocks = buildAtomicHistoryBlocks();
        List<Message> selected = new ArrayList<>();
        int selectedChars = 0;
        int selectedMessages = 0;

        for (int i = blocks.size() - 1; i >= 0; i--) {
            List<Message> block = blocks.get(i);
            int blockChars = estimateMessagesChars(block);
            int blockMessages = block.size();
            boolean fits = selectedMessages + blockMessages <= maxHistoryMessages
                    && selectedChars + blockChars <= maxHistoryChars;

            if (fits || selected.isEmpty()) {
                selected.addAll(0, block);
                selectedChars += blockChars;
                selectedMessages += blockMessages;
            } else {
                break;
            }
        }

        return selected;
    }

    private List<List<Message>> buildAtomicHistoryBlocks() {
        List<List<Message>> blocks = new ArrayList<>();

        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            List<Message> block = new ArrayList<>();
            block.add(message);

            if (hasToolCalls(message)) {
                Set<String> expectedToolCallIds = new HashSet<>();
                for (ToolCall toolCall : message.getToolCalls()) {
                    expectedToolCallIds.add(toolCall.getId());
                }

                while (i + 1 < messages.size()) {
                    Message next = messages.get(i + 1);
                    if (!"tool".equals(next.getRole()) || !expectedToolCallIds.contains(next.getToolCallId())) {
                        break;
                    }
                    block.add(next);
                    i++;
                }
            }

            blocks.add(block);
        }

        return blocks;
    }

    private boolean hasToolCalls(Message message) {
        return message.getToolCalls() != null && !message.getToolCalls().isEmpty();
    }

    private int estimateMessagesChars(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            total += estimateMessageChars(message);
        }
        return total;
    }

    private int estimateMessageChars(Message message) {
        int total = length(message.getRole()) + length(message.getContent())
                + length(message.getToolCallId()) + length(message.getToolName());

        if (message.getToolCalls() != null) {
            for (ToolCall toolCall : message.getToolCalls()) {
                total += length(toolCall.getId()) + length(toolCall.getToolName()) + length(toolCall.getArguments());
            }
        }

        return total;
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
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
