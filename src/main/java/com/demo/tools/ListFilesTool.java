package com.demo.tools;

// import java.io.File; // removed: not used in abstract base class

/**
 * Tool for listing files in a directory.
 * 
 * Implementation idea:
 * 1. Parse the directory path from arguments (JSON format: {"path": "/directory"})
 * 2. Use File.listFiles() or Files.list() to get directory contents
 * 3. Format as a readable list (files and subdirectories)
 * 4. Return the file listing
 */
public abstract class ListFilesTool implements Tool {
    
    @Override
    public String getName() {
        return "list_files";
    }
    
    @Override
    public String getDescription() {
        return "Lists files in a directory. Input: {\"path\": \"directory path\"}";
    }
    
    // execute(String) is deliberately not defined in the abstract base class.
}
