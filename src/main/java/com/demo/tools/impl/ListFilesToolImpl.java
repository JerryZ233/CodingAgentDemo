package com.demo.tools.impl;

import com.demo.model.ToolResult;
import com.demo.tools.Tool;

/**
 * Abstract implementation of Tool for listing files in a directory.
 * 
 * Implementation steps:
 * 1. Parse the directory path from arguments (JSON format: {"path": "/directory"})
 * 2. Validate path exists and is a directory
 * 3. List files using new File(path).listFiles()
 * 4. Format output as: "file1.txt\nfile2.txt\ndir1/\n..."
 * 5. Return success with listing, or error if failed
 */
public abstract class ListFilesToolImpl implements Tool {
    
    @Override
    public String getName() {
        return "list_files";
    }
    
    @Override
    public String getDescription() {
        return "Lists files in a directory. Input: {\"path\": \"directory path\"}";
    }
}
