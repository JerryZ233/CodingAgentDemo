package com.demo.cli;

import com.demo.session.*;
import com.demo.model.ToolResult;
import java.io.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CliRendererTest {
    @Test void retryFieldsAreRenderedWithoutAdviceAndFinalErrorAppearsOnce() {
        StringWriter text = new StringWriter();
        var renderer = new CliRenderer(new PlainTerminalIO(new StringReader(""), new PrintWriter(text), new PrintWriter(text)));
        var error = new com.demo.llm.LLMError(com.demo.llm.LLMError.Source.SERVER, 429,
                java.util.Map.of("error.code", "\"1302\"", "request_id", "\"req_1\""), true, 0);
        var retry = new com.demo.llm.LLMFailure(error, 1, 6, com.demo.llm.LLMFailure.State.RETRY_SCHEDULED, 2000L);
        renderer.event(new SessionEvent("s", "r", 1, Instant.now(), SessionEvent.Type.LLM_RETRY,
                new SessionEvent.ModelFailure(1, retry)));
        assertEquals(com.demo.llm.LLMErrorFormatter.format(retry), text.toString().strip());
        text.getBuffer().setLength(0);
        var failure = new com.demo.llm.LLMFailure(error, 6, 6, com.demo.llm.LLMFailure.State.RETRIES_EXHAUSTED, null);
        renderer.event(new SessionEvent("s", "r", 2, Instant.now(), SessionEvent.Type.LLM_FAILED,
                new SessionEvent.ModelFailure(1, failure)));
        assertEquals("", text.toString());
        renderer.event(new SessionEvent("s", "r", 3, Instant.now(), SessionEvent.Type.RUN_FINISHED,
                new SessionEvent.RunFinished(SessionRun.Status.LLM_ERROR, 1, false, com.demo.llm.LLMErrorFormatter.format(failure))));
        assertEquals(1, text.toString().split("\\[server\\]", -1).length - 1);
        assertFalse(text.toString().contains("建议"));
        assertFalse(text.toString().contains("请充值"));
    }
    @Test void finalAnswerOnlyAppearsInAssistantEventAndToolSummaryUsesCodePoints() {
        StringWriter text = new StringWriter();
        var renderer = new CliRenderer(new PlainTerminalIO(new StringReader(""), new PrintWriter(text), new PrintWriter(text)));
        String id = UUID.randomUUID().toString(), run = UUID.randomUUID().toString();
        renderer.event(new SessionEvent(id, run, 1, Instant.now(), SessionEvent.Type.ASSISTANT_MESSAGE,
                new SessionEvent.AssistantMessage(1, "unique answer", true)));
        renderer.event(new SessionEvent(id, run, 2, Instant.now(), SessionEvent.Type.RUN_FINISHED,
                new SessionEvent.RunFinished(SessionRun.Status.COMPLETED, 1, false, null)));
        assertEquals(1, text.toString().split("unique answer", -1).length - 1);
        String unicode = "😀".repeat(2001);
        String summary = CliRenderer.abbreviate(unicode);
        assertTrue(summary.startsWith("😀".repeat(2000) + "…"));
        renderer.event(new SessionEvent(id, run, 3, Instant.now(), SessionEvent.Type.TOOL_RESULT,
                new SessionEvent.ToolFinished(1, "call", "read_file", ToolResult.success("read_file", unicode))));
        assertTrue(text.toString().contains("完整结果见 /history"));
    }
    @Test void everyTerminalStatusAndCancelResultCanBeRendered() {
        StringWriter text = new StringWriter();
        var renderer = new CliRenderer(new PlainTerminalIO(new StringReader(""), new PrintWriter(text), new PrintWriter(text)));
        for (var status : SessionRun.Status.values()) {
            renderer.event(new SessionEvent("session", "run", 1, Instant.now(), SessionEvent.Type.RUN_FINISHED,
                    new SessionEvent.RunFinished(status, 2, true, "diagnostic")));
            assertTrue(text.toString().contains(status.name()));
        }
        for (var result : RunHandle.CancelResult.values()) renderer.cancellation(result);
        assertTrue(text.toString().contains("/retry-save"));
    }
}
