package com.demo.tools;

import com.demo.model.ToolResult;
import java.nio.file.Files;
import java.nio.file.Paths;

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
public class WriteFileTool implements Tool {
    
    @Override
    public String getName() {
        return "write_file";
    }
    
    @Override
    public String getDescription() {
        return "Writes content to a file. Input: {\"path\": \"file path\", \"content\": \"text\"}";
    }
    
    @Override
    public ToolResult execute(String args) {
        // TODO: implement
        // Implementation steps:
        // 1. Parse JSON args to extract "path" and "content" fields
        // 2. Create parent directories using Files.createDirectories()
        // 3. Write content using Files.writeString(Paths.get(path), content)
        // 4. Return success message or error
        
        return ToolResult.error(getName(), "Not implemented yet");
    }
}
