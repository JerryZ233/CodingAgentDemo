package com.demo.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** Finds the current worktree, never the Git common directory. Does not create any files. */
public final class ProjectLocator {
    public Project locate(Path workspace) throws IOException {
        Path real = workspace.toRealPath();
        if (!Files.isDirectory(real) || !Files.isReadable(real)) {
            throw new IOException("Workspace must be a readable directory");
        }
        for (Path candidate = real; candidate != null; candidate = candidate.getParent()) {
            Path marker = candidate.resolve(".git");
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(marker, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (java.nio.file.NoSuchFileException absent) {
                continue;
            }
            if (!attributes.isDirectory() && !attributes.isRegularFile()) {
                throw new IOException("Unsupported Git marker at " + candidate);
            }
            if (attributes.isRegularFile()) {
                // A worktree/submodule marker points outside this tree. Only recognize its syntax;
                // deliberately do not follow the path or use the common Git storage as project root.
                if (attributes.size() > 16_384) throw new IOException("Invalid Git marker at " + candidate);
                String value = Files.readString(marker).strip();
                if (!value.startsWith("gitdir:") || value.substring(7).isBlank()
                        || value.contains("\n") || value.contains("\r")) {
                    throw new IOException("Invalid Git marker at " + candidate);
                }
            }
            return new Project(real, candidate);
        }
        return new Project(real, real);
    }

    public record Project(Path workspace, Path root) {
        public Project {
            if (!workspace.isAbsolute() || !root.isAbsolute() || !workspace.startsWith(root)) {
                throw new IllegalArgumentException("Invalid project/workspace relationship");
            }
        }
        public Path memoryDirectory() { return root.resolve(".coding-agent"); }
    }
}
