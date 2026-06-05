package com.demo.agent;

import com.demo.model.Message;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages persistent storage for conversation data.
 * 
 * This class handles all file I/O operations for saving and loading
 * conversation history to/from JSON files using Gson.
 */
public class Memory {
    
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    /**
     * Saves a list of messages to a JSON file.
     * Creates parent directories if they don't exist.
     * 
     * @param messages The messages to save
     * @param path The file path to save to
     */
    public void save(List<Message> messages, String path) {
        Path target = Path.of(path).toAbsolutePath().normalize();
        Path parent = target.getParent();
        Path tempFile = null;

        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tempDirectory = parent == null ? Path.of(".").toAbsolutePath().normalize() : parent;
            tempFile = Files.createTempFile(tempDirectory, target.getFileName().toString(), ".tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
                MessageList container = new MessageList(messages);
                GSON.toJson(container, writer);
            }
            moveIntoPlace(tempFile, target);
            tempFile = null;
        } catch (IOException e) {
            throw new AgentStorageException(
                    AgentStorageException.Reason.IO_FAILURE,
                    "Failed to save memory file: " + path,
                    e);
        } finally {
            deleteTempFile(tempFile);
        }
    }

    private void moveIntoPlace(Path tempFile, Path target) throws IOException {
        try {
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteTempFile(Path tempFile) {
        if (tempFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException ignored) {
            // Best-effort cleanup after a failed save.
        }
    }
    
    /**
     * Loads messages from a JSON file.
     * 
     * @param path The file path to load from
     * @return List of messages, or empty list if file doesn't exist
     */
    public List<Message> load(String path) {
        File file = new File(path);
        if (!file.exists()) {
            return new ArrayList<>();
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            Type listType = new TypeToken<MessageList>(){}.getType();
            MessageList container = GSON.fromJson(reader, listType);
            if (container != null && container.messages != null) {
                return container.messages;
            }
            return new ArrayList<>();
        } catch (FileNotFoundException e) {
            return new ArrayList<>();
        } catch (JsonParseException | IllegalStateException e) {
            throw new AgentStorageException(
                    AgentStorageException.Reason.MALFORMED_DATA,
                    "Malformed memory file: " + path,
                    e);
        } catch (IOException e) {
            throw new AgentStorageException(
                    AgentStorageException.Reason.IO_FAILURE,
                    "Failed to load memory file: " + path,
                    e);
        }
    }
    
    /**
     * Deletes a file.
     * 
     * @param path The file path to delete
     */
    public void delete(String path) {
        java.io.File file = new java.io.File(path);
        if (file.exists() && !file.delete()) {
            throw new AgentStorageException(
                    AgentStorageException.Reason.IO_FAILURE,
                    "Failed to delete memory file: " + path);
        }
    }
    
    /**
     * Helper class for JSON serialization.
     */
    private static class MessageList {
        List<Message> messages;
        
        MessageList(List<Message> messages) {
            this.messages = messages;
        }
    }
}
