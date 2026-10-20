package com.demo.memory.storage;

import com.demo.storage.StorageFiles;
import com.google.gson.JsonElement;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A newline is the record boundary. Only a torn final record may be ignored/repaired. */
final class MemoryJournal {
    private static final int MAX_LINE_BYTES = 8 * 1024 * 1024;
    private final StorageFiles files;
    private final String path;
    MemoryJournal(StorageFiles files, String path) { this.files = files; this.path = path; }

    record Event(int schemaVersion, String type, JsonElement data) {
        Event {
            if (schemaVersion != 1) throw new MemoryStorageException(MemoryStorageException.Reason.UNSUPPORTED_VERSION,
                    "Unsupported memory journal version");
            if (type == null || type.isBlank() || data == null || !data.isJsonObject()) {
                throw new IllegalArgumentException("Invalid journal event");
            }
        }
    }
    record Read(List<Event> events, long completeBytes, byte[] tail) {
        Read { events = List.copyOf(events); tail = tail.clone(); }
        boolean incompleteTail() { return tail.length != 0; }
    }

    Read read() throws IOException {
        var target = files.path(path);
        List<Event> events = new ArrayList<>();
        long complete = 0;
        var line = new ByteArrayOutputStream();
        try (var input = new java.io.BufferedInputStream(Files.newInputStream(target))) {
            for (int value; (value = input.read()) != -1;) {
                if (value == '\n') {
                    byte[] bytes = line.toByteArray();
                    events.add(MemoryJson.decode(bytes, Event.class));
                    complete += bytes.length + 1L;
                    line.reset();
                } else {
                    line.write(value);
                    if (line.size() > MAX_LINE_BYTES) {
                        throw new MemoryStorageException(MemoryStorageException.Reason.MALFORMED_DATA, "Memory journal record exceeds limit");
                    }
                }
            }
        } catch (java.nio.file.NoSuchFileException absent) { return new Read(List.of(), 0, new byte[0]); }
        return new Read(events, complete, line.toByteArray());
    }

    void repairTail() throws IOException {
        Read state = read();
        if (state.incompleteTail()) {
            files.replace("local/versions/journal-tail-" + UUID.randomUUID() + ".bin", state.tail());
            files.truncate(path, state.completeBytes());
        }
    }

    void append(String type, Object data) throws IOException {
        String json = MemoryJson.GSON.toJson(new Event(1, type, MemoryJson.GSON.toJsonTree(data)));
        if (MemoryJson.bytes(json).length > MAX_LINE_BYTES) throw new IllegalArgumentException("Memory record exceeds limit");
        repairTail();
        files.append(path, MemoryJson.bytes(json + "\n"));
    }
}
