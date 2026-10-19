package com.demo.cli;

import com.demo.session.*;
import com.demo.model.Message;
import com.demo.model.ToolResult;
import com.demo.llm.*;
import java.io.*;
import java.time.Instant;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CliRendererTest {
    private final StringWriter text = new StringWriter();
    private final CliRenderer renderer = new CliRenderer(new PlainTerminalIO(new StringReader(""), new PrintWriter(text), new PrintWriter(text)));
    private void event(SessionEvent.Type type, SessionEvent.Payload payload) {
        renderer.event(new SessionEvent("secret-session", "secret-run", 1, Instant.now(), type, payload));
    }
    @Test void retryAndTerminalFailureShowChineseWithoutDiagnosticFields() {
        var error = new LLMError(LLMError.Source.SERVER, 429, Map.of("request_id", "\"secret-request\""), true, 0);
        var retry = new LLMFailure(error, 1, 11, LLMFailure.State.RETRY_SCHEDULED, 2001L);
        event(SessionEvent.Type.LLM_RETRY, new SessionEvent.ModelFailure(7, retry));
        event(SessionEvent.Type.LLM_FAILED, new SessionEvent.ModelFailure(7, retry));
        event(SessionEvent.Type.RUN_FINISHED, new SessionEvent.RunFinished(SessionRun.Status.LLM_ERROR, 7, false, LLMErrorFormatter.format(retry)));
        assertTrue(text.toString().contains("重新连接...（1/10）"));
        assertTrue(text.toString().contains("模型请求失败"));
        for (String hidden : List.of("secret-", "[server]", "[client]", "429", "LLM_ERROR", "迭代"))
            assertFalse(text.toString().contains(hidden), text.toString());
    }
    @Test void intermediateAndFinalAnswersAppearOnceButToolPayloadsNeverAppear() {
        event(SessionEvent.Type.RUN_STARTED, new SessionEvent.Progress(0));
        event(SessionEvent.Type.ITERATION_STARTED, new SessionEvent.Progress(88));
        event(SessionEvent.Type.ASSISTANT_MESSAGE, new SessionEvent.AssistantMessage(1, "我会先检查目录", false));
        event(SessionEvent.Type.ASSISTANT_MESSAGE, new SessionEvent.AssistantMessage(2, "最终结果😀", true));
        event(SessionEvent.Type.ASSISTANT_MESSAGE, new SessionEvent.AssistantMessage(2, "   ", true));
        event(SessionEvent.Type.TOOL_STARTED, new SessionEvent.ToolStarted(1, "secret-call", "read_file", "secret-arguments"));
        event(SessionEvent.Type.TOOL_RESULT, new SessionEvent.ToolFinished(1, "secret-call", "read_file", ToolResult.success("read_file", "secret-output")));
        event(SessionEvent.Type.TOOL_RESULT, new SessionEvent.ToolFinished(1, "secret-call", "read_file", ToolResult.error("read_file", "secret-error")));
        event(SessionEvent.Type.RUN_FINISHED, new SessionEvent.RunFinished(SessionRun.Status.COMPLETED, 88, false, "最终结果😀"));
        assertEquals(1, text.toString().split("我会先检查目录", -1).length - 1);
        assertEquals(1, text.toString().split("最终结果😀", -1).length - 1);
        assertTrue(text.toString().contains("正在读取文件"));
        assertTrue(text.toString().contains("正在继续处理"));
        assertFalse(text.toString().contains("secret-"));
        assertFalse(text.toString().contains("88"));
        assertFalse(text.toString().contains("任务已提交"));
        assertFalse(text.toString().contains("本轮处理已结束"));
    }
    @Test void everyStatusAndFailureReasonHasChineseCopy() {
        for (var status : SessionRun.Status.values()) {
            event(SessionEvent.Type.RUN_FINISHED, new SessionEvent.RunFinished(status, 2, true, "secret-diagnostic"));
            assertFalse(text.toString().contains(status.name()));
        }
        for (var reason : SessionException.Reason.values()) renderer.failure(new SessionException(reason, "secret-backend"));
        for (var reason : com.demo.session.storage.SessionStorageException.Reason.values())
            renderer.failure(new com.demo.session.storage.SessionStorageException(reason, "secret-storage"));
        for (var result : RunHandle.CancelResult.values()) renderer.cancellation(result);
        renderer.failure(new IllegalStateException("secret-exception"));
        assertFalse(text.toString().contains("secret-"));
        assertTrue(text.toString().contains("/retry-save"));
    }
    @Test void historyAndStatusHideTechnicalRecordsAndUseChineseRoles() {
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString(), run = UUID.randomUUID().toString();
        var snapshot = new SessionSnapshot(id, "测试会话", Path.of(".").toAbsolutePath(), now, now,
                List.of(Message.user("请读取文件"), Message.assistant("正在分析"), Message.assistant("[server]\nsecret-diagnostic")),
                new SessionRun(run, now, now, SessionRun.Status.LLM_ERROR, 9, "[server]\nsecret-diagnostic"), false);
        renderer.history(snapshot);
        renderer.status(snapshot, null);
        assertTrue(text.toString().contains("用户："));
        assertTrue(text.toString().contains("助手："));
        assertTrue(text.toString().contains("工作目录："));
        assertTrue(text.toString().contains("模型："));
        for (String hidden : List.of(id, run, "secret-", "LLM_ERROR")) assertFalse(text.toString().contains(hidden));
    }
}
