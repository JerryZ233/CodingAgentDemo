package com.demo.tools.builtin;
import com.demo.tools.Tool;
import com.demo.tools.ToolSpec.Capability;
import com.demo.tools.support.*;
import com.demo.tools.fs.*;
import com.demo.model.ToolResult;
import com.google.gson.*;
import java.nio.file.Path;
import java.util.Set;

public final class FileReadTool implements Tool {
    private final WorkspaceFileAccess access;
    public FileReadTool(Path workspace) { access = new WorkspaceFileAccess(workspace); }
    public String getName() { return "read_file"; }
    public String getDescription() { return "Reads complete UTF-8 file content and SHA-256. Fails if the complete JSON result exceeds the message budget; no range reading."; }
    public Set<Capability> getCapabilities() { return Set.of(Capability.FILE_READ); }
    public JsonObject getParametersSchema() {
        return JsonParser.parseString("""
            {"type":"object","properties":{"path":{"type":"string","maxLength":1024}},
             "required":["path"],"additionalProperties":false}
            """).getAsJsonObject();
    }
    public ToolResult execute(String args) {
        try {
            String path = ToolArguments.parse(args, "path").requiredString("path");
            var snapshot = access.read(path);
            JsonObject data = new JsonObject();
            data.addProperty("path", snapshot.path());
            data.addProperty("content", WorkspaceFileAccess.decode(snapshot.bytes()));
            data.addProperty("bytes", snapshot.bytes().length);
            data.addProperty("sha256", snapshot.sha256());
            if (data.toString().length() > ToolLimits.MESSAGE_CHARS)
                return ToolOutputs.error(getName(), "FILE_EXCEEDS_MESSAGE_BUDGET", "Complete file result exceeds message budget; no content returned");
            return ToolOutputs.success(getName(), data);
        } catch (Exception e) { return ToolOutputs.failure(getName(), e); }
    }
}
