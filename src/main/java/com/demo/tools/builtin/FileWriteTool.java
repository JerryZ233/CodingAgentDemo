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
    public String getDescription() { return "Writes UTF-8 text. mode=overwrite (default): content required, old_text/new_text forbidden; read complete existing file first and supply expected_sha256, omit hash for new files. mode=replace: existing file only, old_text/new_text/expected_sha256 required, content forbidden. Replaces exactly one literal, case-sensitive occurrence (including exact newlines); old_text must be nonempty and differ from new_text; new_text may be empty. Range reads suffice for replace. Use the returned new hash for subsequent edits."; }
    public Set<Capability> getCapabilities() { return Set.of(Capability.FILE_WRITE); }
    public JsonObject getParametersSchema() {
        return JsonParser.parseString("""
            {"type":"object","properties":{
             "path":{"type":"string","maxLength":1024},
             "mode":{"type":"string","enum":["overwrite","replace"],"default":"overwrite"},
             "content":{"type":"string","description":"Required for overwrite; forbidden for replace."},
             "old_text":{"type":"string","minLength":1,"description":"Required for replace; exact unique original text."},
             "new_text":{"type":"string","description":"Required for replace; empty means deletion."},
             "expected_sha256":{"type":"string","pattern":"^[0-9a-fA-F]{64}$"}},
             "required":["path"],"additionalProperties":false}
            """).getAsJsonObject();
    }
    public ToolResult execute(String args) {
        try {
            var arguments = ToolArguments.parse(args, "path", "mode", "content", "expected_sha256", "old_text", "new_text");
            String path = arguments.requiredString("path");
            String mode = arguments.has("mode") ? arguments.requiredString("mode") : "overwrite";
            AtomicFileWriter.Result result;
            if (mode.equals("overwrite")) {
                if (arguments.has("old_text") || arguments.has("new_text")) throw new IllegalArgumentException("Unexpected replacement fields");
                result = writer.write(path, arguments.presentString("content"), arguments.optionalString("expected_sha256"));
            } else if (mode.equals("replace")) {
                if (arguments.has("content")) throw new IllegalArgumentException("Unexpected content");
                result = writer.replace(path, arguments.presentString("old_text"), arguments.presentString("new_text"),
                        arguments.requiredString("expected_sha256"));
            } else throw new IllegalArgumentException("Unknown write mode");
            JsonObject data = new JsonObject();
            data.addProperty("path", result.path()); data.addProperty("action", result.action());
            data.addProperty("bytes", result.bytes()); data.addProperty("sha256", result.sha256());
            data.addProperty("mode", mode);
            if (mode.equals("replace")) data.addProperty("replacements", 1);
            return ToolOutputs.success(getName(), data);
        } catch (Exception e) { return ToolOutputs.failure(getName(), e); }
    }
}
