package com.demo.tools;
// (Imports removed: no longer required in abstract base class)

/**
 * Tool for writing content to files.
 * 
 * Implementation idea:
 * 1. Parse the file path and content from arguments
 *    (JSON format: {"path": "/path/to/file", "content": "file content"})
 * 2. Create parent directories if they don't exist
 * 3. Write content using Files.writeString()
 * 4. Return success confirmation or error
 */
public abstract class WriteFileTool implements Tool {
    
    @Override
    public String getName() {
        return "write_file";
    }
    
    @Override
    public String getDescription() {
        return "Writes content to a file. Input: {\"path\": \"file path\", \"content\": \"text\"}";
    }
    
    // Abstract class: concrete tools will implement execute(...)
}
