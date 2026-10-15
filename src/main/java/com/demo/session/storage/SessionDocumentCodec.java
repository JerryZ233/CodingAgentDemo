package com.demo.session.storage;

import com.demo.model.Message;
import com.demo.model.ToolCall;
import com.demo.session.SessionRun;
import com.demo.session.SessionSnapshot;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit wire format: no reflective Instant/Path adapters or executable runtime objects. */
final class SessionDocumentCodec {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private static final int VERSION = 1;

    String encode(SessionSnapshot snapshot) {
        JsonArray messages = new JsonArray();
        snapshot.messages().forEach(message -> messages.add(JsonParser.parseString(message.toJson())));
        SessionRun run = snapshot.lastRun();
        RunDocument runDocument = run == null ? null : new RunDocument(run.id(), run.startedAt().toString(),
                run.endedAt() == null ? null : run.endedAt().toString(), run.status().name(),
                run.iterations(), run.message());
        String json = GSON.toJson(new SessionDocument(VERSION, snapshot.id(), snapshot.title(),
                snapshot.workspace().toString(), snapshot.createdAt().toString(),
                snapshot.updatedAt().toString(), messages, runDocument));
        decode(json); // Never replace a good checkpoint with a document we cannot restore.
        return json;
    }

    SessionSnapshot decode(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (integer(root, "schemaVersion") != VERSION) {
                throw new SessionStorageException(SessionStorageException.Reason.UNSUPPORTED_VERSION,
                        "Unsupported session schema version");
            }
            List<Message> messages = decodeMessages(root.getAsJsonArray("messages"));
            JsonObject run = root.has("lastRun") && !root.get("lastRun").isJsonNull()
                    ? root.getAsJsonObject("lastRun") : null;
            SessionRun lastRun = run == null ? null : new SessionRun(text(run, "id", false),
                    Instant.parse(text(run, "startedAt", false)), instantOrNull(run, "endedAt"),
                    SessionRun.Status.valueOf(text(run, "status", false)), integer(run, "iterations"),
                    text(run, "message", true));
            if ((!messages.isEmpty() && lastRun == null) || (messages.isEmpty() && lastRun != null)) {
                throw new IllegalArgumentException("History and run metadata disagree");
            }
            Instant created = Instant.parse(text(root, "createdAt", false));
            Instant updated = Instant.parse(text(root, "updatedAt", false));
            if (lastRun != null && (lastRun.startedAt().isBefore(created)
                    || lastRun.startedAt().isAfter(updated)
                    || (lastRun.endedAt() != null && lastRun.endedAt().isAfter(updated)))) {
                throw new IllegalArgumentException("Run timestamps are outside session lifetime");
            }
            return new SessionSnapshot(text(root, "id", false), text(root, "title", false),
                    Path.of(text(root, "workspace", false)), created, updated, messages, lastRun, false);
        } catch (SessionStorageException e) {
            throw e;
        } catch (RuntimeException e) {
            // Do not echo the document (which can contain user code or credentials in messages).
            throw new SessionStorageException(SessionStorageException.Reason.MALFORMED_DATA,
                    "Malformed session document", e);
        }
    }

    private List<Message> decodeMessages(JsonArray array) {
        if (array == null) throw new IllegalArgumentException("Missing messages");
        List<Message> messages = new ArrayList<>();
        Map<String, String> pending = new LinkedHashMap<>();
        for (JsonElement element : array) {
            JsonObject object = element.getAsJsonObject();
            String role = text(object, "role", false);
            String content = text(object, "content", true);
            boolean hasCalls = object.has("toolCalls") && !object.get("toolCalls").isJsonNull();
            if (!"tool".equals(role) && !pending.isEmpty()) {
                throw new IllegalArgumentException("Incomplete tool block inside history");
            }
            if (!"assistant".equals(role) && hasCalls) {
                throw new IllegalArgumentException("Tool calls on a non-assistant message");
            }
            if (!"tool".equals(role)) {
                for (String field : List.of("toolCallId", "toolName", "toolSuccess", "toolError")) {
                    if (object.has(field) && !object.get(field).isJsonNull()) {
                        throw new IllegalArgumentException("Tool metadata on a non-tool message");
                    }
                }
            }
            Message message;
            switch (role) {
                case "user", "system" -> {
                    if (content == null) throw new IllegalArgumentException("Missing message content");
                    message = new Message(role, content);
                }
                case "assistant" -> {
                    List<ToolCall> calls = new ArrayList<>();
                    if (hasCalls) {
                        for (JsonElement item : object.getAsJsonArray("toolCalls")) {
                            JsonObject call = item.getAsJsonObject();
                            String id = nonblank(call, "id");
                            String name = nonblank(call, "toolName");
                            if (pending.putIfAbsent(id, name) != null) {
                                throw new IllegalArgumentException("Duplicate tool call ID");
                            }
                            calls.add(new ToolCall(id, name, text(call, "arguments", false)));
                        }
                    }
                    if (content == null && calls.isEmpty()) {
                        throw new IllegalArgumentException("Assistant message has no text or calls");
                    }
                    message = calls.isEmpty() ? Message.assistant(content) : Message.assistantToolCalls(content, calls);
                }
                case "tool" -> {
                    String id = nonblank(object, "toolCallId");
                    String name = nonblank(object, "toolName");
                    if (!name.equals(pending.remove(id)) || content == null) {
                        throw new IllegalArgumentException("Unmatched tool result");
                    }
                    Boolean success = booleanOrNull(object, "toolSuccess");
                    String error = text(object, "toolError", true);
                    message = Message.tool(id, name, content, success, error);
                }
                default -> throw new IllegalArgumentException("Unsupported role");
            }
            messages.add(message);
        }
        // An unfinished final tool block is legal on disk and is repaired on restore.
        return messages;
    }

    private static String text(JsonObject object, String name, boolean nullable) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            if (nullable) return null;
            throw new IllegalArgumentException("Missing " + name);
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Expected string: " + name);
        }
        return value.getAsString();
    }

    private static String nonblank(JsonObject object, String name) {
        String value = text(object, name, false);
        if (value.isBlank()) throw new IllegalArgumentException("Blank " + name);
        return value;
    }

    private static int integer(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Expected integer: " + name);
        }
        return value.getAsBigDecimal().intValueExact();
    }

    private static Boolean booleanOrNull(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("Expected boolean: " + name);
        }
        return value.getAsBoolean();
    }

    private static Instant instantOrNull(JsonObject object, String name) {
        String value = text(object, name, true);
        return value == null ? null : Instant.parse(value);
    }

    private record SessionDocument(int schemaVersion, String id, String title, String workspace,
                                   String createdAt, String updatedAt, JsonArray messages, RunDocument lastRun) { }
    private record RunDocument(String id, String startedAt, String endedAt, String status,
                               int iterations, String message) { }
}
