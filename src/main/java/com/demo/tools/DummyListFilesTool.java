package com.demo.tools;

import com.demo.model.ToolResult;

/**
 * Dummy implementation of ListFilesTool for testing purposes.
 * This class is moved from impl package to tools package.
 */
public class DummyListFilesTool extends ListFilesTool {
   
   @Override
   public ToolResult execute(String args) {
       return ToolResult.success(getName(), "file1.txt\nfile2.txt\ndir1/");
   }
}
