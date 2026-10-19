package com.demo.tools.builtin;
import com.demo.tools.Tool;
import com.demo.tools.ToolSpec.Capability;
import com.demo.tools.support.*;
import com.demo.tools.fs.*;
import com.demo.model.ToolResult;
import com.google.gson.*;
import java.nio.file.Path;
import java.util.Set;

public final class FileWriteTool implements Tool {
    private final AtomicFileWriter writer;
    public FileWriteTool(Path workspace) { writer = new AtomicFileWriter(new WorkspaceFileAccess(workspace)); }
    public String getName() { return "write_file"; }
    public String getDescription() { return "Writes complete UTF-8 text. content is mandatory (empty string is allowed). For existing files, first read the complete file and supply its sha256 as expected_sha256; omit it for new files."; }
    public Set<Capability> getCapabilities() { return Set.of(Capability.FILE_WRITE); }
    public JsonObject getParametersSchema() {
        return JsonParser.parseString("""
            {"type":"object","properties":{
             "path":{"type":"string","maxLength":1024},"content":{"type":"string"},
             "expected_sha256":{"type":"string","pattern":"^[0-9a-fA-F]{64}$"}},
             "required":["path","content"],"additionalProperties":false}
            """).getAsJsonObject();
    }
    public ToolResult execute(String args) {
        try {
            var arguments = ToolArguments.parse(args, "path", "content", "expected_sha256");
            var result = writer.write(arguments.requiredString("path"), arguments.presentString("content"),
                    arguments.optionalString("expected_sha256"));
            JsonObject data = new JsonObject();
            data.addProperty("path", result.path()); data.addProperty("action", result.action());
            data.addProperty("bytes", result.bytes()); data.addProperty("sha256", result.sha256());
            return ToolOutputs.success(getName(), data);
        } catch (Exception e) { return ToolOutputs.failure(getName(), e); }
    }
}
