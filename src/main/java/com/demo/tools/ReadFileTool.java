package com.demo.tools;

// Removed unused file reading imports after making this class abstract

/**
 * Tool for reading files from the filesystem.
 * 
 * Implementation idea:
 * 1. Parse the file path from arguments (JSON format: {"path": "/path/to/file"})
 * 2. Use Java's Files.readAllBytes() or Files.readString() to read content
 * 3. Return the file content as a ToolResult
 */
public abstract class ReadFileTool implements Tool {
    
    @Override
    public String getName() {
        return "read_file";
    }
    
    @Override
    public String getDescription() {
        return "Reads content from a file. Input: {\"path\": \"file path\"}";
    }
    
    // execute(String) removed: to be implemented by concrete subclasses
}
