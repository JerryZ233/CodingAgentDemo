package com.demo.tools.builtin;

import com.demo.execution.*;
import com.demo.model.ToolResult;
import com.demo.tools.*;
import com.demo.tools.support.*;
import com.google.gson.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** No candidate ID or approval text may be supplied by the model. */
public final class MemoryResolveLatestTool implements Tool {
    private final BooleanSupplier resolve;
    public MemoryResolveLatestTool(BooleanSupplier resolve) { this.resolve = resolve; }
    public String getName() { return "memory_resolve_latest"; }
    public String getDescription() { return "Resolve only the candidate already shown to this session, using the actual latest user message. No arguments; ambiguous replies or missing explicit global approval cannot write."; }
    public Set<ToolSpec.Capability> getCapabilities() { return Set.of(ToolSpec.Capability.MEMORY_WRITE); }
    public JsonObject getParametersSchema() { return JsonParser.parseString("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}").getAsJsonObject(); }
    public ToolResult execute(String args) { return execute(args,CancellationToken.none()); }
    public ToolResult execute(String args, CancellationToken token) {
        try {
            token.throwIfCancellationRequested(); ToolArguments.parse(args);
            var result = new JsonObject(); result.addProperty("resolved", resolve.getAsBoolean());
            return ToolOutputs.success(getName(),result);
        } catch (OperationCancelledException e) { throw e; }
        catch (Exception e) { return ToolOutputs.failure(getName(),e); }
    }
}
