package com.demo.tools.builtin;

import com.demo.execution.CancellationToken;
import com.demo.execution.OperationCancelledException;
import com.demo.model.ToolResult;
import com.demo.tools.Tool;
import com.demo.tools.ToolSpec.Capability;
import com.demo.tools.fs.TextSearch;
import com.demo.tools.fs.WorkspaceFileAccess;
import com.demo.tools.support.*;
import com.google.gson.*;
import java.nio.file.Path;
import java.util.Set;

public final class SearchTextTool implements Tool {
    private final TextSearch search;
    public SearchTextTool(Path workspace) { search = new TextSearch(new WorkspaceFileAccess(workspace)); }
    public String getName() { return "search_text"; }
    public Set<Capability> getCapabilities() { return Set.of(Capability.FILE_READ, Capability.DIRECTORY_LIST); }
    public String getDescription() { return "Searches single-line literal UTF-8 text (no regex) in a workspace file or directory. One result per matching line, with workspace-relative path and 1-based line number. Directory glob uses the platform PathMatcher relative to the query root. Skips protected paths and links; skips .git/.gradle/build/node_modules below the query root (explicitly querying such a directory is allowed). At most 1 MiB/file, 32 MiB total, 100000 entries and 5 seconds. Previews may be cropped around the first match. complete=false and limit_reasons/skipped_files indicate omissions; zero returned matches then does not prove absence. Narrow the query to continue; no cursor. Read the located file before editing."; }
    public JsonObject getParametersSchema() {
        return JsonParser.parseString("""
            {"type":"object","properties":{
             "path":{"type":"string","maxLength":1024,"default":"."},
             "text":{"type":"string","minLength":1,"maxLength":1024},
             "glob":{"type":"string","maxLength":1024,"description":"Directories only; platform glob relative to path."},
             "case_sensitive":{"type":"boolean","default":true},
             "max_results":{"type":"integer","minimum":1,"maximum":200,"default":50},
             "max_depth":{"type":"integer","minimum":1,"maximum":20,"default":20,"description":"Directories only."}},
             "required":["text"],"additionalProperties":false}
            """).getAsJsonObject();
    }
    public ToolResult execute(String args) { return execute(args, CancellationToken.none()); }
    public ToolResult execute(String args, CancellationToken token) {
        try {
            token.throwIfCancellationRequested();
            var a = ToolArguments.parse(args, "path", "text", "glob", "case_sensitive", "max_results", "max_depth");
            String path = a.has("path") ? a.requiredString("path") : ".";
            String text = a.presentString("text");
            if (text.isEmpty() || text.length() > ToolLimits.SEARCH_QUERY_CHARS || text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0)
                throw new IllegalArgumentException("Expected a bounded single-line literal");
            String glob = a.optionalString("glob");
            if (glob != null && glob.length() > ToolLimits.PATH_CHARS) throw new IllegalArgumentException("Glob too long");
            return ToolOutputs.success(getName(), search.search(path, text, glob, a.bool("case_sensitive", true),
                    a.integer("max_results", ToolLimits.SEARCH_DEFAULT_RESULTS, 1, ToolLimits.SEARCH_MAX_RESULTS),
                    a.integer("max_depth", 20, 1, 20), a.has("max_depth"), token));
        } catch (OperationCancelledException e) { throw e; }
        catch (Exception e) { return ToolOutputs.failure(getName(), e); }
    }
}
