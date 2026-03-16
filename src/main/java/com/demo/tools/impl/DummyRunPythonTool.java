package com.demo.tools.impl;

import com.demo.tools.Tool;
import com.demo.model.ToolResult;

/**
 * Minimal stub tool to satisfy compile-time references for the demo.
 * It does not perform any operation and returns a non-success result.
 */
public class DummyRunPythonTool implements Tool {
    @Override
    public String getName() {
        return "run_python";
    }

    @Override
    public String getDescription() {
        return "Executes a Python script (dummy implementation for build).";
    }

    @Override
    public ToolResult execute(String args) {
        // Dummy implementation: indicate not implemented in this stub
        return ToolResult.error(getName(), "Dummy Python tool not implemented in this environment");
    }
}
