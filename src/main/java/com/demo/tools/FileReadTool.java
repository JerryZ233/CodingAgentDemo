package com.demo.tools;

import com.demo.model.ToolResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tool for reading files from the filesystem.
 * 
 * Security features:
 * - Workspace confinement
 * - Path traversal prevention
 * - Dangerous path blocking
 * - File size limit (max 1MB)
 */
public class FileReadTool implements Tool {

    private final Path workspaceRoot;

    public FileReadTool() {
        this(SecurityUtil.getWorkspaceRoot());
    }

    public FileReadTool(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    @Override
    public String getName() {
        return "read_file";
    }

    @Override
    public String getDescription() {
        return "Reads content from a file. Input: {\"path\": \"file path\"}";
    }

    @Override
    public JsonObject getParametersSchema() {
        return JsonParser.parseString("""
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "Path to the file to read, relative to the workspace when possible"
                }
              },
              "required": ["path"]
            }
            """).getAsJsonObject();
    }

    @Override
    public ToolResult execute(String args) {
        String path = JsonUtil.getString(args, "path");
        if (path == null || path.isEmpty()) {
            return ToolResult.error(getName(), "Missing 'path' in arguments");
        }

        try {
            // Security checks
            if (SecurityUtil.hasPathTraversal(path)) {
                return ToolResult.error(getName(), "Security: Path traversal not allowed");
            }
            if (SecurityUtil.isDangerousPath(path)) {
                return ToolResult.error(getName(), "Security: Cannot read dangerous path: " + path);
            }
            Path absolutePath = SecurityUtil.resolveExistingWorkspacePath(path, workspaceRoot);
            if (!Files.isRegularFile(absolutePath)) {
                return ToolResult.error(getName(), "Path is not a file: " + path);
            }

            // File size limit (1MB)
            long fileSize = Files.size(absolutePath);
            if (fileSize > 1024 * 1024) {
                return ToolResult.error(getName(), "File too large (max 1MB): " + path);
            }

            String content = Files.readString(absolutePath);
            return ToolResult.success(getName(), content);

        } catch (java.nio.file.NoSuchFileException e) {
            return ToolResult.error(getName(), "File not found: " + path);
        } catch (java.nio.file.AccessDeniedException e) {
            return ToolResult.error(getName(), "Permission denied: " + path);
        } catch (SecurityException e) {
            return ToolResult.error(getName(), "Security: Cannot read outside workspace. Path: " + path);
        } catch (Exception e) {
            return ToolResult.error(getName(), "Error reading file: " + e.getMessage());
        }
    }
}
