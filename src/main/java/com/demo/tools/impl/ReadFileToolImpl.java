package com.demo.tools.impl;

import com.demo.model.ToolResult;
import com.demo.tools.ReadFileTool;

/**
 * Abstract implementation of a Tool that reads files from the filesystem.
 *
 * This implementation extends ReadFileTool to inherit common metadata
 * (name/description) and to allow concrete subclasses to provide the
 * execution logic.
 */
public abstract class ReadFileToolImpl extends ReadFileTool {
    // execute(String) remains abstract and is implemented by concrete subclasses
}
