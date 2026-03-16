package com.demo.tools;

import com.demo.model.ToolResult;
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

    @Override
    public String getName() {
        return "read_file";
    }

    @Override
    public String getDescription() {
        return "Reads content from a file. Input: {\"path\": \"file path\"}";
    }

    @Override
    public ToolResult execute(String args) {
        String path = JsonUtil.getString(args, "path");
        if (path == null || path.isEmpty()) {
            return ToolResult.error(getName(), "Missing 'path' in arguments");
        }

        try {
            Path absolutePath = Path.of(path).toAbsolutePath().normalize();

            // Security checks
            if (SecurityUtil.hasPathTraversal(path)) {
                return ToolResult.error(getName(), "Security: Path traversal not allowed");
            }
            if (!SecurityUtil.isWithinWorkspace(absolutePath)) {
                return ToolResult.error(getName(), "Security: Cannot read outside workspace. Path: " + path);
            }
            if (SecurityUtil.isDangerousPath(path)) {
                return ToolResult.error(getName(), "Security: Cannot read dangerous path: " + path);
            }
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
        } catch (Exception e) {
            return ToolResult.error(getName(), "Error reading file: " + e.getMessage());
        }
    }
}
