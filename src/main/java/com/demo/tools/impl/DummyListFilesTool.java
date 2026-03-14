package com.demo.tools.impl;

import com.demo.model.ToolResult;

/**
 * Dummy implementation of ListFilesTool for testing purposes.
 */
public class DummyListFilesTool extends ListFilesToolImpl {
    
    @Override
    public ToolResult execute(String args) {
        return ToolResult.success(getName(), "file1.txt\nfile2.txt\ndir1/");
    }
}
