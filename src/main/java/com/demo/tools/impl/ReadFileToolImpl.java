package com.demo.tools.impl;

import com.demo.model.ToolResult;
import com.demo.tools.Tool;

/**
 * Abstract implementation of Tool for reading files from the filesystem.
 * 
 * Implementation steps:
 * 1. Parse the file path from arguments (JSON format: {"path": "/path/to/file"})
 * 2. Validate path exists and is readable
 * 3. Read file content using Files.readString(Paths.get(path))
 * 4. Return success with content, or error if failed
 */
public abstract class ReadFileToolImpl implements Tool {
    
    @Override
    public String getName() {
        return "read_file";
    }
    
    @Override
    public String getDescription() {
        return "Reads content from a file. Input: {\"path\": \"file path\"}";
    }
}
