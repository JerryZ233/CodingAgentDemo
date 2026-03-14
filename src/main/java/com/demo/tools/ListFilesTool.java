package com.demo.tools;

import com.demo.model.ToolResult;
import java.io.File;

/**
 * Tool for listing files in a directory.
 * 
 * Implementation idea:
 * 1. Parse the directory path from arguments (JSON format: {"path": "/directory"})
 * 2. Use File.listFiles() or Files.list() to get directory contents
 * 3. Format as a readable list (files and subdirectories)
 * 4. Return the file listing
 */
public class ListFilesTool implements Tool {
    
    @Override
    public String getName() {
        return "list_files";
    }
    
    @Override
    public String getDescription() {
        return "Lists files in a directory. Input: {\"path\": \"directory path\"}";
    }
    
    @Override
    public ToolResult execute(String args) {
        // TODO: implement
        // Implementation steps:
        // 1. Parse JSON args to extract "path" field
        // 2. Validate path exists and is a directory
        // 3. List files using new File(path).listFiles()
        // 4. Format output as: "file1.txt\nfile2.txt\ndir1/\n..."
        // 5. Return success with listing, or error if failed
        
        return ToolResult.error(getName(), "Not implemented yet");
    }
}
