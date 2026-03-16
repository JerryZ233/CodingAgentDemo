package com.demo.agent;

import com.demo.model.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Memory class.
 */
class MemoryTest {

    private Memory memory;

    @BeforeEach
    void setUp() {
        memory = new Memory();
    }

    @Test
    @DisplayName("save() and load() - round-trip preserves messages")
    void testSaveAndLoadRoundTrip(@TempDir Path tempDir) {
        List<Message> messages = Arrays.asList(
            Message.user("Question 1"),
            Message.assistant("Answer 1"),
            Message.user("Question 2"),
            Message.assistant("Answer 2")
        );
        
        File file = tempDir.resolve("conversation.json").toFile();
        memory.save(messages, file.getAbsolutePath());
        
        List<Message> loaded = memory.load(file.getAbsolutePath());
        
        assertEquals(4, loaded.size());
        assertEquals("user", loaded.get(0).getRole());
        assertEquals("Question 1", loaded.get(0).getContent());
        assertEquals("assistant", loaded.get(1).getRole());
        assertEquals("Answer 1", loaded.get(1).getContent());
    }

    @Test
    @DisplayName("save() and load() - handles special characters")
    void testSaveAndLoadSpecialChars(@TempDir Path tempDir) {
        List<Message> messages = Arrays.asList(
            Message.user("Quote: \"hello\"\nNew line\tTab"),
            Message.assistant("Path: C:\\Users\\test")
        );
        
        File file = tempDir.resolve("special.json").toFile();
        memory.save(messages, file.getAbsolutePath());
        
        List<Message> loaded = memory.load(file.getAbsolutePath());
        
        assertEquals(2, loaded.size());
        assertEquals("Quote: \"hello\"\nNew line\tTab", loaded.get(0).getContent());
        assertEquals("Path: C:\\Users\\test", loaded.get(1).getContent());
    }

    @Test
    @DisplayName("save() creates valid JSON file")
    void testSaveCreatesValidJson(@TempDir Path tempDir) {
        List<Message> messages = Arrays.asList(
            Message.user("Test message")
        );
        
        File file = tempDir.resolve("test.json").toFile();
        memory.save(messages, file.getAbsolutePath());
        
        assertTrue(file.exists());
        assertTrue(file.length() > 0);
    }

    @Test
    @DisplayName("load() on non-existent file returns empty list")
    void testLoadNonExistentFile() {
        List<Message> loaded = memory.load("non_existent_file.json");
        
        assertNotNull(loaded);
        assertTrue(loaded.isEmpty());
    }

    @Test
    @DisplayName("save() and load() - empty list")
    void testSaveAndLoadEmptyList(@TempDir Path tempDir) {
        List<Message> messages = new ArrayList<>();
        
        File file = tempDir.resolve("empty.json").toFile();
        memory.save(messages, file.getAbsolutePath());
        
        List<Message> loaded = memory.load(file.getAbsolutePath());
        
        assertNotNull(loaded);
        assertTrue(loaded.isEmpty());
    }

    @Test
    @DisplayName("delete() removes existing file")
    void testDelete(@TempDir Path tempDir) {
        List<Message> messages = Arrays.asList(Message.user("Test"));
        
        File file = tempDir.resolve("delete.json").toFile();
        memory.save(messages, file.getAbsolutePath());
        
        assertTrue(file.exists());
        
        memory.delete(file.getAbsolutePath());
        
        assertFalse(file.exists());
    }

    @Test
    @DisplayName("delete() on non-existent file does not throw")
    void testDeleteNonExistent() {
        assertDoesNotThrow(() -> memory.delete("non_existent.json"));
    }

    @Test
    @DisplayName("save() overwrites existing file")
    void testSaveOverwrites(@TempDir Path tempDir) {
        File file = tempDir.resolve("overwrite.json").toFile();
        
        // Save first version
        List<Message> v1 = Arrays.asList(Message.user("First"));
        memory.save(v1, file.getAbsolutePath());
        long v1Length = file.length();
        
        // Save second version
        List<Message> v2 = Arrays.asList(Message.user("Second"));
        memory.save(v2, file.getAbsolutePath());
        
        // Verify file was overwritten (not appended)
        List<Message> loaded = memory.load(file.getAbsolutePath());
        assertEquals(1, loaded.size());
        assertEquals("Second", loaded.get(0).getContent());
    }

    @Test
    @DisplayName("save() creates parent directories if needed")
    void testSaveCreatesDirectories(@TempDir Path tempDir) {
        List<Message> messages = Arrays.asList(Message.user("Test"));
        
        // Create nested path
        File dir = tempDir.resolve("subdir").toFile();
        File file = new File(dir, "nested.json");
        
        memory.save(messages, file.getAbsolutePath());
        
        assertTrue(file.exists());
        
        List<Message> loaded = memory.load(file.getAbsolutePath());
        assertEquals(1, loaded.size());
    }

    @Test
    @DisplayName("load() handles single message")
    void testLoadSingleMessage(@TempDir Path tempDir) {
        List<Message> messages = Arrays.asList(Message.user("Only one"));
        
        File file = tempDir.resolve("single.json").toFile();
        memory.save(messages, file.getAbsolutePath());
        
        List<Message> loaded = memory.load(file.getAbsolutePath());
        
        assertEquals(1, loaded.size());
        assertEquals("Only one", loaded.get(0).getContent());
    }

    @Test
    @DisplayName("save() and load() - preserves message order")
    void testPreservesOrder(@TempDir Path tempDir) {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            messages.add(Message.user("Message " + i));
        }
        
        File file = tempDir.resolve("ordered.json").toFile();
        memory.save(messages, file.getAbsolutePath());
        
        List<Message> loaded = memory.load(file.getAbsolutePath());
        
        assertEquals(10, loaded.size());
        for (int i = 0; i < 10; i++) {
            assertEquals("Message " + i, loaded.get(i).getContent());
        }
    }
}
