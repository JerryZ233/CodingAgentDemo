package com.demo.storage;

/** No JUnit dependencies: launched in a separate JVM by the lock integration test. */
public final class StorageLockProbe {
    public static void main(String[] args) throws Exception {
        var files = new StorageFiles(java.nio.file.Path.of(args[0]));
        try (var lease = files.lock("local/locks/memory.lck")) { System.out.println("ACQUIRED"); }
        catch (StorageFiles.BusyException busy) { System.out.println("BUSY"); }
    }
}
