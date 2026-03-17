package com.demo.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Message class.
 */
class MessageTest {

    @Test
    @DisplayName("toJson() produces valid JSON for user message")
    void testToJsonUserMessage() {
        Message message = Message.user("Hello, world!");
        String json = message.toJson();
        
        assertNotNull(json);
        assertTrue(json.contains("\"role\":\"user\""), "JSON should contain role: " + json);
        assertTrue(json.contains("\"content\":\"Hello, world!\""), "JSON should contain content: " + json);
        assertTrue(json.startsWith("{"));
        assertTrue(json.endsWith("}"));
    }

    @Test
    @DisplayName("toJson() produces valid JSON for assistant message")
    void testToJsonAssistantMessage() {
        Message message = Message.assistant("I can help with that.");
        String json = message.toJson();
        
        assertNotNull(json);
        assertTrue(json.contains("\"role\":\"assistant\""), "JSON should contain role: " + json);
        assertTrue(json.contains("\"content\":\"I can help with that.\""), "JSON should contain content: " + json);
    }

    @Test
    @DisplayName("fromJson() correctly parses JSON")
    void testFromJson() {
        String json = "{\"role\": \"user\", \"content\": \"Test message\"}";
        Message message = Message.fromJson(json);
        
        assertNotNull(message);
        assertEquals("user", message.getRole());
        assertEquals("Test message", message.getContent());
    }

    @Test
    @DisplayName("fromJson() parses assistant message")
    void testFromJsonAssistant() {
        String json = "{\"role\": \"assistant\", \"content\": \"Response\"}";
        Message message = Message.fromJson(json);
        
        assertNotNull(message);
        assertEquals("assistant", message.getRole());
        assertEquals("Response", message.getContent());
    }

    @Test
    @DisplayName("Round-trip: toJson -> fromJson produces equivalent Message")
    void testRoundTrip() {
        Message original = Message.user("Test round-trip message");
        String json = original.toJson();
        Message parsed = Message.fromJson(json);
        
        assertNotNull(parsed);
        assertEquals(original.getRole(), parsed.getRole());
        assertEquals(original.getContent(), parsed.getContent());
    }

    @Test
    @DisplayName("Round-trip with assistant message")
    void testRoundTripAssistant() {
        Message original = Message.assistant("Assistant response");
        String json = original.toJson();
        Message parsed = Message.fromJson(json);
        
        assertNotNull(parsed);
        assertEquals(original.getRole(), parsed.getRole());
        assertEquals(original.getContent(), parsed.getContent());
    }

    @Test
    @DisplayName("Escaping: handles quotes in content")
    void testEscapingQuotes() {
        Message original = Message.user("He said \"hello\" to me");
        String json = original.toJson();
        Message parsed = Message.fromJson(json);
        
        assertNotNull(parsed);
        assertEquals("He said \"hello\" to me", parsed.getContent());
    }

    @Test
    @DisplayName("Escaping: handles newlines in content")
    void testEscapingNewlines() {
        Message original = Message.user("Line 1\nLine 2\nLine 3");
        String json = original.toJson();
        Message parsed = Message.fromJson(json);
        
        assertNotNull(parsed);
        assertEquals("Line 1\nLine 2\nLine 3", parsed.getContent());
    }

    @Test
    @DisplayName("Escaping: handles backslashes in content")
    void testEscapingBackslashes() {
        Message original = Message.user("Path: C:\\Users\\test\\file.txt");
        String json = original.toJson();
        Message parsed = Message.fromJson(json);
        
        assertNotNull(parsed);
        assertEquals("Path: C:\\Users\\test\\file.txt", parsed.getContent());
    }

    @Test
    @DisplayName("Escaping: handles tabs in content")
    void testEscapingTabs() {
        Message original = Message.user("Column1\tColumn2\tColumn3");
        String json = original.toJson();
        Message parsed = Message.fromJson(json);
        
        assertNotNull(parsed);
        assertEquals("Column1\tColumn2\tColumn3", parsed.getContent());
    }

    @Test
    @DisplayName("Escaping: handles carriage returns in content")
    void testEscapingCarriageReturns() {
        Message original = Message.user("Line 1\r\nLine 2");
        String json = original.toJson();
        Message parsed = Message.fromJson(json);
        
        assertNotNull(parsed);
        assertEquals("Line 1\r\nLine 2", parsed.getContent());
    }

    @Test
    @DisplayName("Escaping: handles mixed special characters")
    void testEscapingMixedSpecialChars() {
        Message original = Message.user("Quote: \"hi\"\nTab:\tHere\nPath: C:\\test");
        String json = original.toJson();
        Message parsed = Message.fromJson(json);
        
        assertNotNull(parsed);
        assertEquals("Quote: \"hi\"\nTab:\tHere\nPath: C:\\test", parsed.getContent());
    }

    @Test
    @DisplayName("fromJson() returns null for null input")
    void testFromJsonNull() {
        Message message = Message.fromJson(null);
        assertNull(message);
    }

    @Test
    @DisplayName("fromJson() returns null for invalid JSON")
    void testFromJsonInvalid() {
        Message message = Message.fromJson("not valid json");
        assertNull(message);
    }

    @Test
    @DisplayName("fromJson() returns null for missing role")
    void testFromJsonMissingRole() {
        // Gson will set missing fields to null
        String json = "{\"content\": \"Test message\"}";
        Message message = Message.fromJson(json);
        // Either null (if field missing) or empty (if field present but null) is acceptable
        assertTrue(message == null || message.getRole() == null || message.getRole().isEmpty());
    }

    @Test
    @DisplayName("fromJson() returns null for missing content")
    void testFromJsonMissingContent() {
        // Gson will set missing fields to null
        String json = "{\"role\": \"user\"}";
        Message message = Message.fromJson(json);
        // Either null (if field missing) or empty (if field present but null) is acceptable
        assertTrue(message == null || message.getContent() == null || message.getContent().isEmpty());
    }

    @Test
    @DisplayName("Constructor creates message with correct role and content")
    void testConstructor() {
        Message message = new Message("user", "Test content");
        assertEquals("user", message.getRole());
        assertEquals("Test content", message.getContent());
    }

    @Test
    @DisplayName("user() factory method creates user message")
    void testUserFactoryMethod() {
        Message message = Message.user("User message");
        assertEquals("user", message.getRole());
        assertEquals("User message", message.getContent());
    }

    @Test
    @DisplayName("assistant() factory method creates assistant message")
    void testAssistantFactoryMethod() {
        Message message = Message.assistant("Assistant message");
        assertEquals("assistant", message.getRole());
        assertEquals("Assistant message", message.getContent());
    }

    @Test
    @DisplayName("toJson() handles empty content")
    void testToJsonEmptyContent() {
        Message message = Message.user("");
        String json = message.toJson();
        Message parsed = Message.fromJson(json);
        
        assertNotNull(parsed);
        assertEquals("", parsed.getContent());
    }
}
