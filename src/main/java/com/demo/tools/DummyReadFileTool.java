package com.demo.tools;

import com.demo.model.ToolResult;

public class DummyReadFileTool extends ReadFileTool {
    @Override
    public ToolResult execute(String args) {
        return ToolResult.success(getName(), "Dummy file content read successfully");
    }
}
