package com.demo.tools.builtin;

import com.demo.memory.consolidation.ConsolidationService;
import com.demo.memory.model.MemoryEdit;
import com.demo.memory.model.MemoryItem;
import com.demo.memory.model.MemoryProposal;
import com.demo.execution.*;
import com.demo.model.ToolResult;
import com.demo.tools.*;
import com.demo.tools.support.*;
import com.google.gson.*;
import java.util.*;
import java.util.function.Supplier;
import com.demo.session.SessionSnapshot;

/** Proposals never grant global write permission. Sources are selected by the host. */
public final class MemoryProposeTool implements Tool {
    private final ConsolidationService memory;
    private final Supplier<SessionSnapshot> snapshot;
    public MemoryProposeTool(ConsolidationService memory, Supplier<SessionSnapshot> snapshot) { this.memory = memory; this.snapshot = snapshot; }
    public String getName() { return "memory_propose"; }
    public Set<ToolSpec.Capability> getCapabilities() { return Set.of(ToolSpec.Capability.MEMORY_WRITE); }
    public String getDescription() { return "Propose one stable memory item based on the current user request. Host binds source. Always creates a pending candidate, never approval. Global scope needs explicit user approval. No paths."; }
    public JsonObject getParametersSchema() { return JsonParser.parseString("""
        {"type":"object","properties":{"scope":{"type":"string","enum":["PROJECT","GLOBAL"]},
        "action":{"type":"string","enum":["ADD","UPDATE","INVALIDATE"]},"item_id":{"type":"string"},
        "kind":{"type":"string","enum":["FACT","RULE","DECISION","PREFERENCE"]},"body":{"type":"string","maxLength":2000},
        "reason":{"type":"string","maxLength":1000}},"required":["scope","action","reason"],"additionalProperties":false}
        """).getAsJsonObject(); }
    public ToolResult execute(String args) { return execute(args, CancellationToken.none()); }
    public ToolResult execute(String args, CancellationToken token) {
        try {
            token.throwIfCancellationRequested();
            var values = ToolArguments.parse(args, "scope", "action", "item_id", "kind", "body", "reason");
            var raw = JsonParser.parseString(args).getAsJsonObject();
            var action = MemoryEdit.Action.valueOf(values.requiredString("action"));
            if (action == MemoryEdit.Action.IGNORE) throw new IllegalArgumentException("No ignore proposals");
            String id = action == MemoryEdit.Action.ADD ? UUID.randomUUID().toString() : values.requiredString("item_id");
            if (action == MemoryEdit.Action.ADD && raw.has("item_id")) throw new IllegalArgumentException("Host assigns ID");
            var kind = action == MemoryEdit.Action.INVALIDATE ? null : MemoryItem.Kind.valueOf(values.requiredString("kind"));
            String body = action == MemoryEdit.Action.INVALIDATE ? null : values.requiredString("body");
            String reason = values.requiredString("reason");
            if (body != null && body.length() > 2000 || reason.length() > 1000) throw new IllegalArgumentException("Proposal too large");
            var proposal = memory.propose(snapshot.get(), MemoryProposal.Scope.valueOf(values.requiredString("scope")), new MemoryEdit(action,id,kind,body),reason);
            var result = new JsonObject(); result.addProperty("status", "PENDING"); result.addProperty("proposal_id", proposal.id());
            return ToolOutputs.success(getName(),result);
        } catch (OperationCancelledException e) { throw e; }
        catch (Exception e) { return ToolOutputs.failure(getName(), e); }
    }
}
