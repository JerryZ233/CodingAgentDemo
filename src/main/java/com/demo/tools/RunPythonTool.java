package com.demo.tools;

/**
 * Tool for running Python scripts and capturing output.
 * Abstract base class; concrete subclasses must implement execution logic.
 */
public abstract class RunPythonTool implements Tool {
    
    @Override
    public String getName() {
        return "run_python";
    }
    
    @Override
    public String getDescription() {
        return "Runs Python code or script. Input: {\"code\": \"print('hello')\"} or {\"file\": \"script.py\"}";
    }
    
    // execute() method removed; concrete subclasses provide implementation
}
