package com.demo.tools;

import com.demo.model.ToolResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tool for listing files in a directory.
 * 
 * Security features (via SecurityUtil):
 * - Workspace confinement
 * - Path traversal prevention
 * - Dangerous path blocking
 * - Max 1000 files limit
 */
public class FileListTool implements Tool {

    private final Path workspaceRoot;

    public FileListTool() {
        this(SecurityUtil.getWorkspaceRoot());
    }

    public FileListTool(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    @Override
    public String getName() {
        return "list_files";
    }

    @Override
    public String getDescription() {
        return "Lists files in a directory. Input: {\"path\": \"directory path\"}";
    }

    @Override
    public JsonObject getParametersSchema() {
        return JsonParser.parseString("""
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "Directory path to list, relative to the workspace when possible"
                }
              },
              "required": ["path"]
            }
            """).getAsJsonObject();
    }

    @Override
    public ToolResult execute(String args) {
        String path;
        try {
            path = ToolArguments.parse(args).requiredString("path");
        } catch (IllegalArgumentException e) {
            return ToolResult.error(getName(), "Invalid arguments: " + e.getMessage());
        }

        try {
            // Security: Path traversal prevention
            if (SecurityUtil.hasPathTraversal(path)) {
                return ToolResult.error(getName(), 
                    "Security: Path traversal not allowed");
            }

            // Security: Block dangerous paths
            if (SecurityUtil.isDangerousPath(path)) {
                return ToolResult.error(getName(), 
                    "Security: Cannot list dangerous path: " + path);
            }

            Path absolutePath = SecurityUtil.resolveExistingWorkspacePath(path, workspaceRoot);
            File dir = absolutePath.toFile();

            if (!Files.exists(absolutePath)) {
                return ToolResult.error(getName(), "Path does not exist: " + path);
            }
            if (!Files.isDirectory(absolutePath)) {
                return ToolResult.error(getName(), "Path is not a directory: " + path);
            }
            if (!Files.isReadable(absolutePath)) {
                return ToolResult.error(getName(), "Permission denied reading directory: " + path);
            }

            File[] children = dir.listFiles();
            if (children == null) {
                return ToolResult.error(getName(), "Unable to read directory listing: " + path);
            }

            // Security: Limit number of files
            if (children.length > 1000) {
                return ToolResult.error(getName(), "Too many files (max 1000): " + path);
            }

            String listing = formatListing(children);
            return ToolResult.success(getName(), listing);
        } catch (SecurityException se) {
            return ToolResult.error(getName(), "Security: Cannot list outside workspace. Path: " + path);
        } catch (Exception e) {
            return ToolResult.error(getName(), "Error listing directory: " + e.getMessage());
        }
    }

    private String formatListing(File[] children) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < children.length; i++) {
            File f = children[i];
            String name = f.getName();
            if (f.isDirectory()) {
                name += "/";
            }
            sb.append(name);
            if (i < children.length - 1) sb.append("\n");
        }
        return sb.toString();
    }
}
