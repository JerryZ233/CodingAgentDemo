package com.demo.memory.compaction;

import com.demo.execution.CancellationToken;
import com.demo.llm.*;
import com.demo.model.Message;
import com.google.gson.*;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;

public final class SummaryFixtures {
    private SummaryFixtures() { }
    public static String json(List<String> sources) {
        var root = new JsonObject();
        root.addProperty("archiveSummary", "Inspected code; tests remain pending.");
        var continuation = new JsonObject();
        for (String key : List.of("constraints", "decisions", "completed", "currentState", "failedAttempts", "unresolved")) {
            var array = new JsonArray();
            if (key.equals("constraints")) array.add("Do not modify public API");
            if (key.equals("unresolved")) array.add("Tests remain pending");
            continuation.add(key, array);
        }
        root.add("continuationSummary", continuation);
        root.add("keywords", new Gson().toJsonTree(List.of("API", "tests")));
        root.add("sourceRefs", new Gson().toJsonTree(sources));
        return root.toString();
    }

    public static LLMClient client(BiFunction<List<Message>, RequestPurpose, LLMClient.LLMResponse> responder) {
        return new LLMClient() {
            public LLMResponse sendMessage(List<Message> messages, String tools) {
                return responder.apply(messages, RequestPurpose.AGENT);
            }
            public LLMResponse sendMessage(List<Message> messages, String tools, CancellationToken token,
                                           Consumer<LLMFailure> retries, RequestPurpose purpose) {
                if (purpose == RequestPurpose.COMPACTION && tools != null) throw new AssertionError("Compaction has tools");
                return responder.apply(messages, purpose);
            }
        };
    }

    public static List<String> sources(List<Message> request) {
        return JsonParser.parseString(request.get(1).getContent()).getAsJsonObject().getAsJsonArray("fragments")
                .asList().stream().map(fragment -> fragment.getAsJsonObject().get("messageId").getAsString()).distinct().toList();
    }
}
