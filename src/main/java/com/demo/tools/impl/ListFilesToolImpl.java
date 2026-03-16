package com.demo.tools.impl;

import com.demo.model.ToolResult;
import com.demo.tools.ListFilesTool;

/**
 * Abstract implementation of ListFilesTool.
 * This class now extends the base ListFilesTool and keeps execute() abstract
 * for concrete implementations to fill in.
 */
public abstract class ListFilesToolImpl extends ListFilesTool {
    public abstract ToolResult execute(String args);
}
