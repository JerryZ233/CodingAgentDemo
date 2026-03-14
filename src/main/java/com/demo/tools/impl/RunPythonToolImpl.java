package com.demo.tools.impl;

import com.demo.model.ToolResult;
import com.demo.tools.Tool;

/**
 * Abstract implementation of Tool for running Python scripts and capturing output.
 * 
 * Implementation steps:
 * 1. Parse the script path or code from arguments
 *    (JSON format: {"code": "print('hello')"} or {"file": "script.py"})
 * 2. Create a temporary file if running inline code
 * 3. Use ProcessBuilder to run: python script.py
 * 4. Read stdout and stderr using BufferedReader
 * 5. Combine and return output, or return error on failure
 */
public abstract class RunPythonToolImpl implements Tool {
    
    @Override
    public String getName() {
        return "run_python";
    }
    
    @Override
    public String getDescription() {
        return "Runs Python code or script. Input: {\"code\": \"print('hello')\"} or {\"file\": \"script.py\"}";
    }
}
