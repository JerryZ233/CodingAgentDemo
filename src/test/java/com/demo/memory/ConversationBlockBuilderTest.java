package com.demo.memory;

import com.demo.model.Message;
import com.demo.model.ToolCall;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConversationBlockBuilderTest {
    private final ConversationBlockBuilder builder = new ConversationBlockBuilder();
    private final ToolCall first = new ToolCall("first", "read_file", "{}");
    private final ToolCall second = new ToolCall("second", "search_text", "{}");

    @Test void groupsAllResultsRegardlessOfResultOrderAndSharesCompressionBoundaries() {
        var raw = List.of(Message.user("work"), Message.assistantToolCalls(null, List.of(first, second)),
                Message.tool("second", "search_text", "found"), Message.tool("first", "read_file", "read"),
                Message.assistant("done"));
        var blocks = builder.build(raw);
        assertEquals(List.of(1, 4, 5), blocks.stream().map(ConversationBlockBuilder.Block::end).toList());
        assertTrue(blocks.stream().allMatch(ConversationBlockBuilder.Block::complete));
        assertEquals(4, new CompressionState(raw.get(3).getId(), "history", "summary", 1).coveredCount(raw));
        assertThrows(IllegalArgumentException.class,
                () -> new CompressionState(raw.get(2).getId(), "history", "summary", 1).coveredCount(raw));
    }

    @Test void incompleteTrailingBlockIsRecognizedButCannotBeCompressed() {
        var raw = List.of(Message.assistantToolCalls(null, List.of(first, second)),
                Message.tool("first", "read_file", "read"));
        assertFalse(builder.build(raw).get(0).complete());
        assertThrows(IllegalArgumentException.class,
                () -> new CompressionState(raw.get(1).getId(), "history", "summary", 1).coveredCount(raw));
    }

    @Test void rejectsOrphansDuplicateResultsMismatchedNamesAndInterruptedBlocks() {
        assertThrows(IllegalArgumentException.class, () -> builder.build(List.of(Message.tool("first", "read_file", "orphan"))));
        assertThrows(IllegalArgumentException.class, () -> builder.build(List.of(
                Message.assistantToolCalls(null, List.of(first)), Message.tool("first", "wrong", "result"))));
        assertThrows(IllegalArgumentException.class, () -> builder.build(List.of(
                Message.assistantToolCalls(null, List.of(first)), Message.tool("first", "read_file", "result"),
                Message.tool("first", "read_file", "duplicate"))));
        assertThrows(IllegalArgumentException.class, () -> builder.build(List.of(
                Message.assistantToolCalls(null, List.of(first)), Message.user("interrupted"))));
        assertThrows(IllegalArgumentException.class, () -> builder.build(List.of(
                Message.assistantToolCalls(null, List.of(first, first)))));
    }
}
