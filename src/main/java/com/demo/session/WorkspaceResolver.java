package com.demo.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class WorkspaceResolver {
    Path resolve(Path requested) {
        try {
            if (requested == null) throw new IOException("Missing directory");
            Path real = requested.toRealPath();
            if (!Files.isDirectory(real) || !Files.isReadable(real)) {
                throw new IOException("Not a readable directory");
            }
            return real;
        } catch (IOException | SecurityException e) {
            throw new SessionException(SessionException.Reason.INVALID_WORKSPACE,
                    "Workspace must be an existing readable directory: " + requested, e);
        }
    }

    void validateStored(Path stored) {
        if (!resolve(stored).equals(stored)) {
            throw new SessionException(SessionException.Reason.INVALID_WORKSPACE,
                    "Workspace now points to a different real directory: " + stored);
        }
    }
}
