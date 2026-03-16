package com.demo.tools;

import com.demo.model.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tool for writing content to files.
 * 
 * Security features (via SecurityUtil):
 * - Workspace confinement
 * - Path traversal prevention
 * - Dangerous path blocking
 * - File extension whitelist
 */
public class FileWriteTool implements Tool {

    public FileWriteTool() {
    }

    @Override
    public String getName() {
        return "write_file";
    }

    @Override
    public String getDescription() {
        return "Writes content to a file. Input: {\"path\": \"file path\", \"content\": \"text\"}";
    }

    @Override
    public ToolResult execute(String args) {
        if (args == null) {
            return ToolResult.error(getName(), "Null arguments");
        }

        try {
            String pathStr = JsonUtil.getString(args, "path");
            String content = JsonUtil.getString(args, "content");

            if (pathStr == null || pathStr.trim().isEmpty()) {
                return ToolResult.error(getName(), "Missing 'path' field in arguments");
            }

            if (content == null) {
                content = "";
            }

            Path path = Path.of(pathStr);
            Path absolutePath = path.toAbsolutePath().normalize();

            // Security: Workspace confinement
            if (!SecurityUtil.isWithinWorkspace(absolutePath)) {
                return ToolResult.error(getName(), 
                    "Security: Cannot write outside workspace. Path: " + pathStr);
            }

            // Security: Path traversal prevention
            if (SecurityUtil.hasPathTraversal(pathStr)) {
                return ToolResult.error(getName(), 
                    "Security: Path traversal not allowed");
            }

            // Security: Block dangerous paths
            if (SecurityUtil.isDangerousPath(absolutePath.toString())) {
                return ToolResult.error(getName(), 
                    "Security: Cannot write to dangerous path: " + pathStr);
            }

            // Security: File extension whitelist
            if (!SecurityUtil.isAllowedExtension(absolutePath.toString())) {
                return ToolResult.error(getName(), 
                    "Security: File extension not allowed. Allowed: " + SecurityUtil.getAllowedExtensions());
            }

            // Warn on overwrite
            if (Files.exists(absolutePath)) {
                System.out.println("WARNING: Overwriting existing file: " + absolutePath);
            }

            // Create parent directories if needed
            Path parent = absolutePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            // Write the file
            Files.writeString(absolutePath, content);

            return ToolResult.success(getName(), "File written: " + pathStr);

        } catch (Exception ex) {
            return ToolResult.error(getName(), "Error writing file: " + ex.getMessage());
        }
    }
}
