package com.demo.agent;

import com.demo.model.Message;
import com.demo.model.ToolCall;
import com.demo.tools.ToolSpec;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Conversation class.
 */
class ConversationTest {
    private PromptBuilder prompt = new PromptBuilder();
    private com.demo.memory.context.ContextWindowStrategy window = new com.demo.memory.context.ContextWindowStrategy();
    private List<Message> prepare(Conversation conversation) {
        return window.prepare(new com.demo.memory.context.ContextPreparation.Request(prompt.buildSystemPrompt(),
                prompt.getToolDescriptions(), conversation.getMessages(), conversation.getContentRevision()),
                com.demo.execution.CancellationToken.none()).messages();
    }


    @Test
    void contentRevisionCountsContentChangesButNotPromptOrReadOperations() {
        Conversation state = new Conversation();
        assertEquals(0, state.getContentRevision());
        state.addUserMessage("work");
        assertEquals(1, state.getContentRevision());
        prompt.setSystemPromptTemplate("system");
        prepare(state);
        state.getMessages();
        assertEquals(1, state.getContentRevision());
        var restored = Conversation.fromMessages(state.getMessages(), 42);
        assertEquals(42, restored.getContentRevision());
        assertEquals(state.getMessages().get(0).getId(), restored.getMessages().get(0).getId());
        var call = new ToolCall("revision-call", "read_file", "{}");
        restored.addAssistantToolCalls(null, List.of(call));
        restored.addToolResult(call, "result");
        restored.addAssistantMessage("done");
        assertEquals(45, restored.getContentRevision());
        restored.clear();
        assertEquals(46, restored.getContentRevision());
        restored.clear();
        assertEquals(46, restored.getContentRevision());
    }

    @Test
    void restoredContextDefensivelyCopiesHistoryAndDelegatesPersistenceToSessionService() {
        List<Message> history = new ArrayList<>(List.of(Message.user("first")));
        Conversation restored = Conversation.fromMessages(history);
        history.clear();
        assertEquals(1, restored.getMessages().size());
        Path workspace = Path.of("workspace-{TOOLS}").toAbsolutePath();
        prompt.setWorkspace(workspace);
        prepare(restored);
        prepare(restored);
        assertEquals(1, restored.getMessages().size());
        assertTrue(prompt.buildSystemPrompt().contains(workspace.toString()));
    }

    private Conversation context;

    @BeforeEach
    void setUp() {
        context = new Conversation();
    }

    @Test
    @DisplayName("Legacy preparation trims long history")
    void testBuildMessagesForLLMTrimsLongHistory() {
        prompt.setSystemPromptTemplate("system prompt");
        window = new com.demo.memory.context.ContextWindowStrategy(200, 5);

        for (int i = 0; i < 10; i++) {
            context.addUserMessage("old user " + i);
            context.addAssistantMessage("old assistant " + i);
        }

        List<Message> llmMessages = prepare(context);

        assertEquals("system", llmMessages.get(0).getRole());
        assertTrue(llmMessages.size() <= 6);
        assertFalse(llmMessages.stream().anyMatch(m -> "old user 0".equals(m.getContent())));
        assertTrue(llmMessages.stream().anyMatch(m -> "old assistant 9".equals(m.getContent())));
    }

    @Test
    @DisplayName("Legacy preparation preserves system prompt when history is trimmed")
    void testBuildMessagesForLLMPreservesSystemPrompt() {
        prompt.setSystemPromptTemplate("keep this system prompt");
        window = new com.demo.memory.context.ContextWindowStrategy(1, 1);
        context.addUserMessage("this history message is larger than the tiny budget");

        List<Message> llmMessages = prepare(context);

        assertEquals("system", llmMessages.get(0).getRole());
        assertEquals("keep this system prompt", llmMessages.get(0).getContent());
    }

    @Test
    @DisplayName("Legacy preparation preserves recent messages")
    void testBuildMessagesForLLMPreservesRecentMessages() {
        prompt.setSystemPromptTemplate("system prompt");
        window = new com.demo.memory.context.ContextWindowStrategy(1_000, 3);

        for (int i = 0; i < 6; i++) {
            context.addUserMessage("message " + i);
        }

        List<Message> llmMessages = prepare(context);

        assertEquals(4, llmMessages.size());
        assertEquals("message 3", llmMessages.get(1).getContent());
        assertEquals("message 4", llmMessages.get(2).getContent());
        assertEquals("message 5", llmMessages.get(3).getContent());
    }

    @Test
    @DisplayName("Legacy preparation keeps recent tool call and result together")
    void testBuildMessagesForLLMKeepsToolCallAndResultTogether() {
        prompt.setSystemPromptTemplate("system prompt");
        window = new com.demo.memory.context.ContextWindowStrategy(80, 2);
        context.addUserMessage("old message that should be trimmed");
        ToolCall toolCall = new ToolCall("call_recent", "read_file", "{\"path\":\"README.md\"}");

        context.addAssistantToolCalls("", List.of(toolCall));
        context.addToolResult(toolCall, "recent tool result");

        List<Message> llmMessages = prepare(context);

        assertEquals(3, llmMessages.size());
        assertEquals("assistant", llmMessages.get(1).getRole());
        assertEquals("call_recent", llmMessages.get(1).getToolCalls().get(0).getId());
        assertEquals("tool", llmMessages.get(2).getRole());
        assertEquals("call_recent", llmMessages.get(2).getToolCallId());
        assertFalse(llmMessages.stream().anyMatch(m -> "old message that should be trimmed".equals(m.getContent())));
    }

    @Test
    @DisplayName("Conversation window truncates oversized messages without mutating history")
    void contextWindowTruncatesOversizedMessagesWithoutMutatingHistory() {
        ToolCall toolCall = new ToolCall("call_large", "read_file", "{\"path\":\"large.txt\"}");
        Message assistant = Message.assistantToolCalls("", List.of(toolCall));
        Message largeToolResult = Message.tool("call_large", "read_file", "x".repeat(200), true, null);
        com.demo.memory.context.ContextWindowStrategy strategy = new com.demo.memory.context.ContextWindowStrategy(1_000, 10, 80);

        List<Message> selected = strategy.selectHistory(List.of(assistant, largeToolResult));

        Message selectedToolResult = selected.get(1);
        assertEquals("tool", selectedToolResult.getRole());
        assertEquals("call_large", selectedToolResult.getToolCallId());
        assertEquals(80, selectedToolResult.getContent().length());
        assertTrue(selectedToolResult.getContent().endsWith("[message truncated]"));
        assertEquals(200, largeToolResult.getContent().length());
    }

    @Test
    @DisplayName("New context has empty message list")
    void testNewContextIsEmpty() {
        List<Message> messages = context.getMessages();
        assertNotNull(messages);
        assertTrue(messages.isEmpty());
    }

    @Test
    @DisplayName("Default prompt uses API tool calling instead of JSON protocol")
    void testDefaultPromptUsesApiToolCalling() {
        String prompt = this.prompt.buildSystemPrompt();

        assertTrue(prompt.contains("tool calling mechanism provided by the API"));
        assertFalse(prompt.contains("ONLY the JSON"));
        assertFalse(prompt.contains("\"tool_calls\""));
    }

    @Test
    @DisplayName("System prompt uses structured tool specs including required parameters")
    void testPromptUsesStructuredToolSpecs() {
        JsonObject parametersSchema = JsonParser.parseString("""
            {
              "type": "object",
              "properties": {
                "path": { "type": "string" }
              },
              "required": ["path"]
            }
            """).getAsJsonObject();
        prompt.setToolSpecs(List.of(new ToolSpec("read_file", "Reads a file.", parametersSchema)));

        String prompt = this.prompt.buildSystemPrompt();

        assertTrue(prompt.contains("- read_file: Reads a file. Required parameters: path."));
        assertFalse(prompt.contains("\"function\""));
    }

    @Test
    @DisplayName("addUserMessage() adds message correctly")
    void testAddUserMessage() {
        context.addUserMessage("Hello, AI!");
        
        List<Message> messages = context.getMessages();
        assertEquals(1, messages.size());
        
        Message msg = messages.get(0);
        assertEquals("user", msg.getRole());
        assertEquals("Hello, AI!", msg.getContent());
    }

    @Test
    @DisplayName("addAssistantMessage() adds message correctly")
    void testAddAssistantMessage() {
        context.addAssistantMessage("Hello, human!");
        
        List<Message> messages = context.getMessages();
        assertEquals(1, messages.size());
        
        Message msg = messages.get(0);
        assertEquals("assistant", msg.getRole());
        assertEquals("Hello, human!", msg.getContent());
    }

    @Test
    @DisplayName("Multiple messages are added in order")
    void testMultipleMessages() {
        context.addUserMessage("Question 1");
        context.addAssistantMessage("Answer 1");
        context.addUserMessage("Question 2");
        context.addAssistantMessage("Answer 2");
        
        List<Message> messages = context.getMessages();
        assertEquals(4, messages.size());
        
        assertEquals("user", messages.get(0).getRole());
        assertEquals("Question 1", messages.get(0).getContent());
        
        assertEquals("assistant", messages.get(1).getRole());
        assertEquals("Answer 1", messages.get(1).getContent());
        
        assertEquals("user", messages.get(2).getRole());
        assertEquals("Question 2", messages.get(2).getContent());
        
        assertEquals("assistant", messages.get(3).getRole());
        assertEquals("Answer 2", messages.get(3).getContent());
    }

    @Test
    @DisplayName("getMessages() returns the message list")
    void testGetMessages() {
        context.addUserMessage("Test");
        
        List<Message> messages = context.getMessages();
        assertNotNull(messages);
        assertEquals(1, messages.size());
        assertEquals("Test", messages.get(0).getContent());
        assertThrows(UnsupportedOperationException.class, () -> messages.add(Message.user("external mutation")));
    }

    @Test
    @DisplayName("clear() removes all messages")
    void testClear() {
        context.addUserMessage("Message 1");
        context.addAssistantMessage("Message 2");
        context.addUserMessage("Message 3");
        
        assertEquals(3, context.getMessages().size());
        
        context.clear();
        
        assertTrue(context.getMessages().isEmpty());
    }

    @Test
    @DisplayName("clear() on empty context does nothing")
    void testClearEmpty() {
        assertTrue(context.getMessages().isEmpty());
        context.clear();
        assertTrue(context.getMessages().isEmpty());
    }

    @Test
    @DisplayName("Multiple user messages in sequence")
    void testMultipleUserMessages() {
        context.addUserMessage("First");
        context.addUserMessage("Second");
        context.addUserMessage("Third");
        
        List<Message> messages = context.getMessages();
        assertEquals(3, messages.size());
        
        assertTrue(messages.stream().allMatch(m -> "user".equals(m.getRole())));
    }

    @Test
    @DisplayName("Multiple assistant messages in sequence")
    void testMultipleAssistantMessages() {
        context.addAssistantMessage("First");
        context.addAssistantMessage("Second");
        context.addAssistantMessage("Third");
        
        List<Message> messages = context.getMessages();
        assertEquals(3, messages.size());
        
        assertTrue(messages.stream().allMatch(m -> "assistant".equals(m.getRole())));
    }

}
