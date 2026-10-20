package com.demo.agent;

import com.demo.llm.LLMClient;
import com.demo.model.Message;
import com.demo.model.ToolCall;
import com.demo.model.ToolResult;
import com.demo.tools.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTranscriptReplayTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Fixed transcript can be replayed through save and load")
    void fixedTranscriptCanBeReplayedThroughSaveAndLoad() {
        ToolCall toolCall = new ToolCall("call_read", "read_fixture", "{\"path\":\"fixture.txt\"}");
        AtomicInteger modelCalls = new AtomicInteger();
        LLMClient scriptedModel = (messages, toolsDescription) -> {
            int call = modelCalls.getAndIncrement();
            if (call == 0) {
                assertEquals("user", messages.get(messages.size() - 1).getRole());
                return new LLMClient.LLMResponse("", List.of(toolCall));
            }

            Message lastMessage = messages.get(messages.size() - 1);
            assertEquals("tool", lastMessage.getRole());
            assertEquals("call_read", lastMessage.getToolCallId());
            assertEquals("fixture content", lastMessage.getContent());
            return new LLMClient.LLMResponse("I read the fixture.", null);
        };

        Conversation context = new Conversation();
        context.addUserMessage("read the fixture");

        AgentRunResult result = new AgentLoop(
                scriptedModel,
                Map.of("read_fixture", new FixtureReadTool())
        ).run(context);

        assertEquals(AgentRunResult.Status.COMPLETED, result.getStatus());
        assertEquals(4, context.getMessages().size());
        assertEquals("assistant", context.getMessages().get(1).getRole());
        assertEquals("call_read", context.getMessages().get(1).getToolCalls().get(0).getId());
        assertEquals("tool", context.getMessages().get(2).getRole());
        assertTrue(context.getMessages().get(2).getToolSuccess());
        assertEquals("assistant", context.getMessages().get(3).getRole());

        var repository = new com.demo.session.storage.JsonSessionRepository(tempDir.resolve("sessions"));
        String id = java.util.UUID.randomUUID().toString();
        java.time.Instant now = java.time.Instant.now();
        repository.save(new com.demo.session.SessionSnapshot(id, "fixture", tempDir.toAbsolutePath(),
                now, now, context.getMessages(),
                com.demo.session.SessionRun.started(now).finish(result, now), false));
        Conversation reloaded = Conversation.fromMessages(repository.load(id).messages());

        List<Message> replayed = reloaded.getMessages();
        assertEquals(4, replayed.size());
        assertEquals("read the fixture", replayed.get(0).getContent());
        assertEquals("call_read", replayed.get(1).getToolCalls().get(0).getId());
        assertEquals("fixture content", replayed.get(2).getContent());
        assertTrue(replayed.get(2).getToolSuccess());
        assertNull(replayed.get(2).getToolError());
        assertEquals("I read the fixture.", replayed.get(3).getContent());
    }

    private static class FixtureReadTool implements Tool {
        @Override
        public String getName() {
            return "read_fixture";
        }

        @Override
        public String getDescription() {
            return "Reads a fixed test fixture";
        }

        @Override
        public ToolResult execute(String args) {
            return ToolResult.success(getName(), "fixture content");
        }
    }
}
