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
    public String getDescription() { return "Reads complete UTF-8 file content and SHA-256 by default. Supply start_line or max_lines for 1-based range reading (defaults: line 1, 100 lines; at most 1000). Range results preserve original newlines without line-number prefixes, and shrink to fit the JSON budget. sha256 and bytes always describe the complete file (at most 1 MiB). Optional expected_sha256 rejects changed versions between pages. A single over-budget line cannot be split. Partial content must never be used to reconstruct a complete overwrite."; }
    public Set<Capability> getCapabilities() { return Set.of(Capability.FILE_READ); }
    public JsonObject getParametersSchema() {
        return JsonParser.parseString("""
            {"type":"object","properties":{"path":{"type":"string","maxLength":1024},
             "start_line":{"type":"integer","minimum":1},
             "max_lines":{"type":"integer","minimum":1,"maximum":1000,"default":100},
             "expected_sha256":{"type":"string","pattern":"^[0-9a-fA-F]{64}$"}},
             "required":["path"],"additionalProperties":false}
            """).getAsJsonObject();
    }
    public ToolResult execute(String args) {
        try {
            var arguments = ToolArguments.parse(args, "path", "start_line", "max_lines", "expected_sha256");
            String path = arguments.requiredString("path");
            boolean range = arguments.has("start_line") || arguments.has("max_lines");
            int start = arguments.integer("start_line", 1, 1, Integer.MAX_VALUE);
            int limit = arguments.integer("max_lines", ToolLimits.READ_DEFAULT_LINES, 1, ToolLimits.READ_MAX_LINES);
            String expected = arguments.optionalString("expected_sha256");
            if (expected != null && !expected.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("Invalid hash");
            var snapshot = access.read(path);
            if (expected != null && !expected.equalsIgnoreCase(snapshot.sha256()))
                throw new FileAccessException("FILE_CHANGED", "File version changed; restart range reading");
            String text = WorkspaceFileAccess.decode(snapshot.bytes());
            if (range) return ToolOutputs.success(getName(), range(snapshot, new TextFileView(text), start, limit));
            JsonObject data = new JsonObject();
            data.addProperty("path", snapshot.path());
            data.addProperty("content", text);
            data.addProperty("bytes", snapshot.bytes().length);
            data.addProperty("sha256", snapshot.sha256());
            if (data.toString().length() > ToolLimits.MESSAGE_CHARS)
                return ToolOutputs.error(getName(), "FILE_EXCEEDS_MESSAGE_BUDGET", "Complete file result exceeds message budget; use start_line/max_lines");
            return ToolOutputs.success(getName(), data);
        } catch (Exception e) { return ToolOutputs.failure(getName(), e); }
    }

    private JsonObject range(WorkspaceFileAccess.Snapshot snapshot, TextFileView view, int start, int limit) throws FileAccessException {
        if (start > view.totalLines() + 1) throw new IllegalArgumentException("Line is past EOF");
        int requested = Math.min(limit, view.totalLines() - start + 1);
        JsonObject result = page(snapshot, view, start, requested, false);
        if (result.toString().length() <= ToolLimits.MESSAGE_CHARS) return result;
        int low = 1, high = requested - 1;
        JsonObject best = null;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            JsonObject candidate = page(snapshot, view, start, middle, true);
            if (candidate.toString().length() <= ToolLimits.MESSAGE_CHARS) { best = candidate; low = middle + 1; }
            else high = middle - 1;
        }
        if (best == null) throw new FileAccessException("LINE_EXCEEDS_MESSAGE_BUDGET", "One complete line exceeds the JSON budget; use targeted shell diagnostics");
        return best;
    }

    private JsonObject page(WorkspaceFileAccess.Snapshot snapshot, TextFileView view, int start, int count, boolean budgetLimited) {
        int next = start + count;
        boolean more = next <= view.totalLines();
        JsonObject data = new JsonObject();
        data.addProperty("path", snapshot.path()); data.addProperty("content", view.slice(start, count));
        data.addProperty("bytes", snapshot.bytes().length); data.addProperty("sha256", snapshot.sha256());
        data.addProperty("start_line", start);
        if (count > 0) data.addProperty("end_line", next - 1); else data.add("end_line", JsonNull.INSTANCE);
        data.addProperty("total_lines", view.totalLines());
        data.addProperty("complete", start == 1 && !more);
        data.addProperty("has_more", more);
        if (more) data.addProperty("next_start_line", next); else data.add("next_start_line", JsonNull.INSTANCE);
        if (budgetLimited) data.addProperty("limited_by", "message_budget");
        else if (more) data.addProperty("limited_by", "max_lines");
        else data.add("limited_by", JsonNull.INSTANCE);
        return data;
    }
}
