package com.demo.memory.storage;

import com.demo.storage.StorageFiles;
import java.io.IOException;

/** Call only while holding the directory's memory lock. */
final class MemoryDirectory {
    static final String LOCK = "local/locks/memory.lck";
    static void initialize(StorageFiles files) throws IOException {
        var existing = files.read(".gitignore");
        String text = existing.isEmpty() ? "" : MemoryJson.text(existing.get());
        if (text.lines().noneMatch(line -> line.equals("/local/") || line.equals("local/"))) {
            files.replace(".gitignore", MemoryJson.bytes(text + (text.isEmpty() || text.endsWith("\n") ? "" : "\n") + "/local/\n"));
        }
    }
}
