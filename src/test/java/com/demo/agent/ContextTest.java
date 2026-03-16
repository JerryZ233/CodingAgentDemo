package com.demo.agent;

import com.demo.model.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Context class.
 */
class ContextTest {

    private Context context;

    @BeforeEach
    void setUp() {
        context = new Context();
    }

    @Test
    @DisplayName("New context has empty message list")
    void testNewContextIsEmpty() {
        List<Message> messages = context.getMessages();
        assertNotNull(messages);
        assertTrue(messages.isEmpty());
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
    @DisplayName("saveToFile() and loadFromFile() - round-trip preserves messages")
    void testSaveAndLoadRoundTrip(@TempDir Path tempDir) {
        // Add some messages
        context.addUserMessage("What is Java?");
        context.addAssistantMessage("Java is a programming language.");
        context.addUserMessage("Tell me more.");
        context.addAssistantMessage("Java is object-oriented and platform-independent.");
        
        // Save to file
        File file = tempDir.resolve("conversation.json").toFile();
        context.saveToFile(file.getAbsolutePath());
        
        // Create new context and load
        Context loadedContext = new Context();
        loadedContext.loadFromFile(file.getAbsolutePath());
        
        // Verify messages are preserved
        List<Message> loadedMessages = loadedContext.getMessages();
        assertEquals(4, loadedMessages.size());
        
        assertEquals("user", loadedMessages.get(0).getRole());
        assertEquals("What is Java?", loadedMessages.get(0).getContent());
        
        assertEquals("assistant", loadedMessages.get(1).getRole());
        assertEquals("Java is a programming language.", loadedMessages.get(1).getContent());
        
        assertEquals("user", loadedMessages.get(2).getRole());
        assertEquals("Tell me more.", loadedMessages.get(2).getContent());
        
        assertEquals("assistant", loadedMessages.get(3).getRole());
        assertEquals("Java is object-oriented and platform-independent.", loadedMessages.get(3).getContent());
    }

    @Test
    @DisplayName("saveToFile() and loadFromFile() - handles special characters")
    void testSaveAndLoadSpecialChars(@TempDir Path tempDir) {
        // Add messages with special characters
        context.addUserMessage("Quote: \"hello\"\nNew line\tTab");
        context.addAssistantMessage("Path: C:\\Users\\test");
        
        // Save and load
        File file = tempDir.resolve("special.json").toFile();
        context.saveToFile(file.getAbsolutePath());
        
        Context loadedContext = new Context();
        loadedContext.loadFromFile(file.getAbsolutePath());
        
        // Verify special characters are preserved
        List<Message> loadedMessages = loadedContext.getMessages();
        assertEquals(2, loadedMessages.size());
        
        assertEquals("Quote: \"hello\"\nNew line\tTab", loadedMessages.get(0).getContent());
        assertEquals("Path: C:\\Users\\test", loadedMessages.get(1).getContent());
    }

    @Test
    @DisplayName("saveToFile() creates valid JSON file")
    void testSaveCreatesValidJson(@TempDir Path tempDir) {
        context.addUserMessage("Test message");
        context.addAssistantMessage("Response");
        
        File file = tempDir.resolve("test.json").toFile();
        context.saveToFile(file.getAbsolutePath());
        
        assertTrue(file.exists());
        assertTrue(file.length() > 0);
    }

    @Test
    @DisplayName("loadFromFile() on non-existent file does not throw")
    void testLoadNonExistentFile() {
        // Should not throw, just print error
        assertDoesNotThrow(() -> context.loadFromFile("non_existent_file.json"));
    }

    @Test
    @DisplayName("saveToFile() and loadFromFile() - empty context")
    void testSaveAndLoadEmptyContext(@TempDir Path tempDir) {
        // Save empty context
        File file = tempDir.resolve("empty.json").toFile();
        context.saveToFile(file.getAbsolutePath());
        
        // Load into new context
        Context loadedContext = new Context();
        loadedContext.loadFromFile(file.getAbsolutePath());
        
        // Should still be empty
        assertTrue(loadedContext.getMessages().isEmpty());
    }

    @Test
    @DisplayName("saveToFile() overwrites existing file")
    void testSaveOverwrites(@TempDir Path tempDir) {
        File file = tempDir.resolve("overwrite.json").toFile();
        
        // Save first version
        context.addUserMessage("First message");
        context.saveToFile(file.getAbsolutePath());
        
        // Clear and save second version
        context.clear();
        context.addUserMessage("Second message");
        context.saveToFile(file.getAbsolutePath());
        
        // Load and verify only second message exists
        Context loadedContext = new Context();
        loadedContext.loadFromFile(file.getAbsolutePath());
        
        assertEquals(1, loadedContext.getMessages().size());
        assertEquals("Second message", loadedContext.getMessages().get(0).getContent());
    }

    @Test
    @DisplayName("loadFromFile() clears existing messages before loading")
    void testLoadClearsExisting(@TempDir Path tempDir) {
        // Create and save a context
        context.addUserMessage("Saved message");
        File file = tempDir.resolve("test.json").toFile();
        context.saveToFile(file.getAbsolutePath());
        
        // Add more messages to context
        context.addUserMessage("Additional message");
        assertEquals(2, context.getMessages().size());
        
        // Load from file - should replace all messages
        context.loadFromFile(file.getAbsolutePath());
        assertEquals(1, context.getMessages().size());
        assertEquals("Saved message", context.getMessages().get(0).getContent());
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

    @Test
    @DisplayName("Context uses injected Memory")
    void testInjectedMemory(@TempDir Path tempDir) {
        Memory customMemory = new Memory();
        Context contextWithMemory = new Context(customMemory);
        
        contextWithMemory.addUserMessage("Test");
        
        File file = tempDir.resolve("injected.json").toFile();
        contextWithMemory.saveToFile(file.getAbsolutePath());
        
        // Verify custom memory was used
        assertTrue(file.exists());
        
        // Verify getMemory() returns the injected instance
        assertSame(customMemory, contextWithMemory.getMemory());
    }
}
