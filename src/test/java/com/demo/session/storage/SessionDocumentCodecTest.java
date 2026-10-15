package com.demo.session.storage;

import com.demo.model.Message;
import com.demo.model.ToolCall;
import com.demo.session.SessionRun;
import com.demo.session.SessionSnapshot;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionDocumentCodecTest {
    private final SessionDocumentCodec codec = new SessionDocumentCodec();

    private SessionSnapshot fixture() {
        Instant now = Instant.parse("2026-10-15T10:00:00Z");
        List<ToolCall> calls = new ArrayList<>(List.of(new ToolCall("call_1", "read_file", "{\"path\":\"a.txt\"}")));
        return new SessionSnapshot(UUID.randomUUID().toString(), "中文 😀 \"title\"", Path.of(".").toAbsolutePath(),
                now, now, List.of(Message.user("line 1\n\tline 2"), Message.assistantToolCalls(null, calls),
                Message.tool("call_1", "read_file", "", false, "failed"), Message.assistant("")),
                SessionRun.started(now).finish(SessionRun.Status.COMPLETED, now, ""), true);
    }

    @Test
    void roundTripPreservesMetadataAndDoesNotSerializeRuntimeState() {
        SessionSnapshot original = fixture();
        String json = codec.encode(original);
        SessionSnapshot decoded = codec.decode(json);
        assertEquals(original.id(), decoded.id());
        assertEquals(original.title(), decoded.title());
        assertEquals(original.workspace(), decoded.workspace());
        assertEquals(original.lastRun(), decoded.lastRun());
        assertEquals(original.messages().get(0).getContent(), decoded.messages().get(0).getContent());
        assertNull(decoded.messages().get(1).getContent());
        assertEquals("call_1", decoded.messages().get(1).getToolCalls().get(0).getId());
        assertFalse(decoded.messages().get(2).getToolSuccess());
        assertEquals("failed", decoded.messages().get(2).getToolError());
        assertFalse(decoded.unsaved());
        assertFalse(json.contains("unsaved"));
        assertFalse(json.contains("apiKey"));
        assertThrows(UnsupportedOperationException.class, () -> decoded.messages().clear());
        assertThrows(UnsupportedOperationException.class, () -> decoded.messages().get(1).getToolCalls().clear());
    }

    @Test
    void defensiveSnapshotCopyAlsoRepairsGsonCreatedMessageLists() {
        Message deserialized = Message.fromJson("{\"role\":\"assistant\",\"content\":\"\",\"toolCalls\":["
                + "{\"id\":\"a\",\"toolName\":\"read_file\",\"arguments\":\"{}\"}]}");
        SessionSnapshot base = fixture();
        SessionSnapshot copy = new SessionSnapshot(base.id(), base.title(), base.workspace(), base.createdAt(),
                base.updatedAt(), List.of(deserialized), base.lastRun(), false);
        deserialized.getToolCalls().clear();
        assertEquals(1, copy.messages().get(0).getToolCalls().size());
    }

    @Test
    void unfinishedFinalBlockIsAcceptedForRecovery() {
        JsonObject root = JsonParser.parseString(codec.encode(fixture())).getAsJsonObject();
        var messages = root.getAsJsonArray("messages");
        messages.remove(3);
        messages.remove(2);
        assertEquals(2, codec.decode(root.toString()).messages().size());
    }

    @Test
    void missingOrInvalidFieldsAreRejectedWithoutEchoingDocument() {
        for (String field : List.of("schemaVersion", "id", "title", "workspace", "createdAt", "updatedAt", "messages")) {
            JsonObject root = JsonParser.parseString(codec.encode(fixture())).getAsJsonObject();
            root.remove(field);
            SessionStorageException error = assertThrows(SessionStorageException.class, () -> codec.decode(root.toString()));
            assertEquals(SessionStorageException.Reason.MALFORMED_DATA, error.getReason());
            assertFalse(error.getMessage().contains("line 1"));
        }
        for (String invalid : List.of("", "not-json", "null", "[]", "{}")) {
            assertThrows(SessionStorageException.class, () -> codec.decode(invalid));
        }
    }

    @Test
    void validatesVersionNumbersTypesAndTimeRanges() {
        JsonObject root = JsonParser.parseString(codec.encode(fixture())).getAsJsonObject();
        root.addProperty("schemaVersion", 2);
        assertEquals(SessionStorageException.Reason.UNSUPPORTED_VERSION,
                assertThrows(SessionStorageException.class, () -> codec.decode(root.toString())).getReason());
        root.addProperty("schemaVersion", 1.1);
        assertThrows(SessionStorageException.class, () -> codec.decode(root.toString()));
        root.addProperty("schemaVersion", "1");
        assertThrows(SessionStorageException.class, () -> codec.decode(root.toString()));
        root.addProperty("schemaVersion", 1);
        root.addProperty("createdAt", "tomorrow");
        assertThrows(SessionStorageException.class, () -> codec.decode(root.toString()));
        root.addProperty("createdAt", "2027-01-01T00:00:00Z");
        assertThrows(SessionStorageException.class, () -> codec.decode(root.toString()));
    }

    @Test
    void rejectsToolProtocolCorruptionAndInvalidNullContent() {
        for (String mutation : List.of("id", "toolName", "role", "toolSuccess", "toolCalls", "content")) {
            JsonObject root = JsonParser.parseString(codec.encode(fixture())).getAsJsonObject();
            var messages = root.getAsJsonArray("messages");
            switch (mutation) {
                case "id" -> messages.get(1).getAsJsonObject().getAsJsonArray("toolCalls").get(0).getAsJsonObject().remove("id");
                case "toolName" -> messages.get(2).getAsJsonObject().addProperty("toolName", "different");
                case "role" -> messages.get(0).getAsJsonObject().addProperty("role", "unknown");
                case "toolSuccess" -> messages.get(2).getAsJsonObject().addProperty("toolSuccess", "false");
                case "toolCalls" -> messages.get(0).getAsJsonObject().add("toolCalls", new com.google.gson.JsonArray());
                case "content" -> messages.get(0).getAsJsonObject().remove("content");
            }
            assertThrows(SessionStorageException.class, () -> codec.decode(root.toString()), mutation);
        }
        JsonObject root = JsonParser.parseString(codec.encode(fixture())).getAsJsonObject();
        root.getAsJsonArray("messages").remove(2); // A subsequent assistant message cannot follow unresolved calls.
        assertThrows(SessionStorageException.class, () -> codec.decode(root.toString()));
    }
}
