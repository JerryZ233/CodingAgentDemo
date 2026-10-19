package com.demo.tools.builtin;
import com.demo.tools.Tool;
import com.demo.tools.ToolSpec.Capability;
import com.demo.tools.support.*;
import com.demo.tools.fs.*;
import com.demo.model.ToolResult;
import com.google.gson.*;
import java.nio.file.Path;
import java.util.Set;

public final class FileListTool implements Tool {
    private final DirectoryListing listing;
    public FileListTool(Path workspace) { listing = new DirectoryListing(new WorkspaceFileAccess(workspace)); }
    public String getName() { return "list_files"; }
    public String getDescription() { return "Lists visible entries sorted by workspace-relative path, with pagination. Optional glob is relative to the query directory. Recursive traversal never follows links. Directory changes invalidate snapshot consistency."; }
    public Set<Capability> getCapabilities() { return Set.of(Capability.DIRECTORY_LIST); }
    public JsonObject getParametersSchema() {
        return JsonParser.parseString("""
            {"type":"object","properties":{
             "path":{"type":"string","maxLength":1024},
             "limit":{"type":"integer","minimum":1,"maximum":1000,"default":100},
             "cursor":{"type":"string"},"glob":{"type":"string"},
             "recursive":{"type":"boolean","default":false},
             "max_depth":{"type":"integer","minimum":1,"maximum":20,"default":10}},
             "required":["path"],"additionalProperties":false}
            """).getAsJsonObject();
    }
    public ToolResult execute(String args) {
        try {
            var a = ToolArguments.parse(args, "path", "limit", "cursor", "glob", "recursive", "max_depth");
            return ToolOutputs.success(getName(), listing.list(a.requiredString("path"),
                    a.integer("limit", 100, 1, 1000), a.optionalString("cursor"), a.optionalString("glob"),
                    a.bool("recursive", false), a.integer("max_depth", 10, 1, 20)));
        } catch (Exception e) { return ToolOutputs.failure(getName(), e); }
    }
}
