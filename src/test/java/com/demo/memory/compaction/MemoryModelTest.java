package com.demo.memory.compaction;

import com.demo.memory.context.TokenEstimator;
import com.demo.config.MemoryConfig;
import com.demo.execution.*;
import com.demo.llm.*;
import com.demo.model.Message;
import com.demo.model.ToolCall;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MemoryModelTest {
    private final MemoryConfig config = MemoryConfig.defaults().withEnabled(true);

    @Test void boundedFragmentsPreserveEverySourceCharacterAndCarryPriorSummariesWithoutTools() {
        var first = Message.user("Do not modify public API");
        var second = Message.assistant("😀中文".repeat(25000));
        var messages = List.of(first, second);
        var reconstructed = new LinkedHashMap<String, StringBuilder>();
        var calls = new AtomicInteger();
        var model = new MemoryModel(SummaryFixtures.client((request, purpose) -> {
            assertEquals(RequestPurpose.COMPACTION, purpose);
            assertTrue(TokenEstimator.REQUEST_OVERHEAD + new TokenEstimator().messages(request) <= config.inputBudgetTokens(2048));
            var payload = JsonParser.parseString(request.get(1).getContent()).getAsJsonObject();
            if (calls.getAndIncrement() > 0) {
                assertTrue(payload.get("previousContinuationSummary").getAsString().contains("Do not modify public API"));
                assertFalse(payload.get("previousArchiveSummary").getAsString().isBlank());
            }
            for (var fragment : payload.getAsJsonArray("fragments")) {
                var part = fragment.getAsJsonObject();
                var builder = reconstructed.computeIfAbsent(part.get("messageId").getAsString(), key -> new StringBuilder());
                assertEquals(builder.length(), part.get("offset").getAsInt());
                String text = part.get("text").getAsString();
                assertFalse(Character.isLowSurrogate(text.charAt(0)));
                assertFalse(Character.isHighSurrogate(text.charAt(text.length() - 1)));
                builder.append(text);
            }
            return new LLMClient.LLMResponse(SummaryFixtures.json(SummaryFixtures.sources(request)), null);
        }), config, 2048);
        var summary = model.summarize(messages, "Earlier constraint", CancellationToken.none(), ignored -> {});
        assertTrue(calls.get() > 2);
        assertEquals(first.toJson(), reconstructed.get(first.getId()).toString());
        assertEquals(second.toJson(), reconstructed.get(second.getId()).toString());
        assertEquals(Set.of(first.getId(), second.getId()), new HashSet<>(summary.sourceRefs()));
        assertTrue(summary.continuationSummary().contains("Tests remain pending"));
    }

    @Test void malformedOrUnsafeOutputsGetOnlyOneRepairAndNeverExecuteReturnedTools() {
        var source = Message.user("work");
        var valid = SummaryFixtures.json(List.of(source.getId()));
        var badSource = JsonParser.parseString(valid).getAsJsonObject();
        badSource.add("sourceRefs", new Gson().toJsonTree(List.of(UUID.randomUUID().toString())));
        var oversized = JsonParser.parseString(valid).getAsJsonObject();
        oversized.addProperty("archiveSummary", "中".repeat(2000));
        List<String> invalid = List.of("not json", "```json\n" + valid + "\n```", valid + " trailing",
                badSource.toString(), oversized.toString(), "{\"archiveSummary\":\"a\",\"archiveSummary\":\"b\"}",
                valid.replace("\"keywords\":[\"API\",\"tests\"]", "\"keywords\":[1]"));
        for (String output : invalid) {
            var calls = new AtomicInteger();
            var model = new MemoryModel(SummaryFixtures.client((request, purpose) -> {
                int attempt = calls.incrementAndGet();
                if (attempt == 2) assertTrue(request.get(0).getContent().contains("previous response failed validation"));
                return new LLMClient.LLMResponse(attempt == 1 ? output : valid, null);
            }), config, 2048);
            assertFalse(model.summarize(List.of(source), "", CancellationToken.none(), ignored -> {}).continuationSummary().isBlank());
            assertEquals(2, calls.get(), output);
        }
        var toolCalls = new AtomicInteger();
        var toolModel = new MemoryModel(SummaryFixtures.client((request, purpose) -> {
            toolCalls.incrementAndGet();
            return new LLMClient.LLMResponse(valid, List.of(new ToolCall("forbidden", "run_shell", "{}")));
        }), config, 2048);
        assertEquals(CompactionException.Reason.INVALID_OUTPUT, assertThrows(CompactionException.class,
                () -> toolModel.summarize(List.of(source), "", CancellationToken.none(), ignored -> {})).reason());
        assertEquals(2, toolCalls.get());
    }

    @Test void errorsAndCancellationDoNotRetryFormatOrPublishPartialResults() {
        var source = Message.user("work");
        var calls = new AtomicInteger();
        var error = new MemoryModel(SummaryFixtures.client((request, purpose) -> {
            calls.incrementAndGet();
            return LLMClient.LLMResponse.error("offline failure");
        }), config, 2048);
        assertEquals(CompactionException.Reason.MODEL_FAILED, assertThrows(CompactionException.class,
                () -> error.summarize(List.of(source), "", CancellationToken.none(), ignored -> {})).reason());
        assertEquals(1, calls.get());
        var cancellation = new CancellationSource();
        var cancelled = new MemoryModel(SummaryFixtures.client((request, purpose) -> {
            cancellation.cancel();
            return new LLMClient.LLMResponse(SummaryFixtures.json(List.of(source.getId())), null);
        }), config, 2048);
        assertThrows(OperationCancelledException.class,
                () -> cancelled.summarize(List.of(source), "", cancellation.token(), ignored -> {}));
    }
}
