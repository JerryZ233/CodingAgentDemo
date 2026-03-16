package com.demo.tools.impl;

import com.demo.model.ToolResult;
import com.demo.tools.Tool;
import com.demo.tools.WriteFileTool;

/**
 * Abstract implementation of Tool for writing content to files.
 * 
 * Implementation steps:
 * 1. Parse the file path and content from arguments
 *    (JSON format: {"path": "/path/to/file", "content": "file content"})
 * 2. Create parent directories if they don't exist
 * 3. Write content using Files.writeString()
 * 4. Return success confirmation or error
 */
public abstract class WriteFileToolImpl extends WriteFileTool {
    
    // getName() and getDescription() are inherited from WriteFileTool
}
