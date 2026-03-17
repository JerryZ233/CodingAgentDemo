package com.demo.agent;

import com.demo.model.Message;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages conversation history for the AI agent.
 * 
 * This class maintains a list of messages exchanged between the user and the AI,
 * uses a Memory instance for persistent storage, and builds the complete context
 * to send to the LLM including system prompt, chat history, and tool descriptions.
 * 
 * The system prompt automatically includes the tool descriptions that are set
 * via setToolDescriptions(), so new tools will be automatically included.
 */
public class Context {
    
    private static final String TOOLS_PLACEHOLDER = "{TOOLS}";
    
    private final List<Message> messages;
    private final Memory memory;
    private String systemPromptTemplate;
    private String toolDescriptions;
    
    /**
     * Creates a new Context with a new Memory instance.
     */
    public Context() {
        this.messages = new ArrayList<>();
        this.memory = new Memory();
        this.systemPromptTemplate = getDefaultSystemPromptTemplate();
        this.toolDescriptions = "";
    }
    
    /**
     * Creates a new Context with a custom Memory instance.
     * 
     * @param memory The Memory instance to use for persistence
     */
    public Context(Memory memory) {
        this.messages = new ArrayList<>();
        this.memory = memory;
        this.systemPromptTemplate = getDefaultSystemPromptTemplate();
        this.toolDescriptions = "";
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
               "## Tool Call Protocol\n" +
               "When you need to use a tool, you MUST respond in the following JSON format:\n" +
               "```json\n" +
               "{\n" +
               "  \"tool_calls\": [\n" +
               "    {\n" +
               "      \"name\": \"tool_name\",\n" +
               "      \"arguments\": {\n" +
               "        \"param1\": \"value1\",\n" +
               "        \"param2\": \"value2\"\n" +
               "      }\n" +
               "    }\n" +
               "  ]\n" +
               "}\n" +
               "```\n\n" +
               "IMPORTANT:\n" +
               "- When you need to call a tool, your response must be ONLY the JSON above, nothing else.\n" +
               "- The \"name\" must match exactly one of the available tool names listed above.\n" +
               "- The \"arguments\" must be a valid JSON object with the required parameters.\n" +
               "- After receiving the tool result, you can continue with another tool call or provide your final answer.\n" +
               "- If you don't need to use any tool, respond normally without JSON.\n\n" +
               "## Workflow\n" +
               "For each user request, follow this cycle:\n" +
               "1. THINK: Analyze the request and determine what needs to be done\n" +
               "2. DECIDE: Decide if you need to use tools or can answer directly\n" +
               "3. EXECUTE: If using tools, output the JSON tool call format above\n" +
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
     * Parses the JSON tool descriptions and creates a readable list.
     * 
     * @return Formatted tool descriptions string
     */
    private String formatToolDescriptionsForPrompt() {
        if (toolDescriptions == null || toolDescriptions.isEmpty()) {
            return "(No tools available)";
        }
        
        StringBuilder sb = new StringBuilder();
        
        // Parse the JSON array and extract tool info
        // Expected format: [{"type": "function", "function": {"name": "xxx", "description": "xxx", ...}}, ...]
        try {
            int idx = 0;
            while (true) {
                int nameStart = toolDescriptions.indexOf("\"name\":", idx);
                if (nameStart == -1) break;
                
                int nameValueStart = toolDescriptions.indexOf("\"", nameStart + 7);
                if (nameValueStart == -1) break;
                int nameValueEnd = toolDescriptions.indexOf("\"", nameValueStart + 1);
                if (nameValueEnd == -1) break;
                String toolName = toolDescriptions.substring(nameValueStart + 1, nameValueEnd);
                
                int descStart = toolDescriptions.indexOf("\"description\":", nameValueEnd);
                if (descStart == -1) break;
                
                int descValueStart = toolDescriptions.indexOf("\"", descStart + 15);
                if (descValueStart == -1) break;
                int descValueEnd = toolDescriptions.indexOf("\"", descValueStart + 1);
                if (descValueEnd == -1) break;
                String toolDesc = toolDescriptions.substring(descValueStart + 1, descValueEnd);
                
                sb.append("- ").append(toolName).append(": ").append(toolDesc).append("\n");
                
                idx = descValueEnd + 1;
            }
        } catch (Exception e) {
            // If parsing fails, return raw descriptions
            return toolDescriptions;
        }
        
        return sb.length() > 0 ? sb.toString() : "(No tools available)";
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
        
        // Add all conversation messages
        llmMessages.addAll(messages);
        
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
        memory.save(messages, path);
    }
    
    /**
     * Loads the conversation history from a JSON file.
     * 
     * @param path The file path to load from
     */
    public void loadFromFile(String path) {
        List<Message> loaded = memory.load(path);
        messages.clear();
        messages.addAll(loaded);
    }
    
    /**
     * Returns the Memory instance used for persistence.
     * 
     * @return The Memory instance
     */
    public Memory getMemory() {
        return memory;
    }
}
