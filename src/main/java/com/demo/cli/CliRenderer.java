package com.demo.cli;

import com.demo.config.Config;
import com.demo.model.Message;
import com.demo.session.*;

public final class CliRenderer {
    private final TerminalIO terminal;
    public CliRenderer(TerminalIO terminal) { this.terminal = terminal; }
    public void welcome(Config config) {
        terminal.print("=== Coding Agent ===\n" + (config.isConfigured() ? "模型：" + config.getModel() : "Dummy 模式：未配置 API Key，不会调用真实模型。"));
        if (terminal instanceof PlainTerminalIO)
            terminal.print("普通行模式：使用 /cancel 取消任务；Ctrl+C 按操作系统退出信号处理。");
    }
    public static String usage() {
        return """
                用法：CodingAgentDemo [--workspace <目录> | --session <UUID>] [--session-dir <目录>] [--] [任务]
                无任务：进入交互会话；有任务：执行一轮、自动保存并退出。
                --help 显示此帮助。
                """;
    }
    public void help(boolean selecting) {
        terminal.print("""
                /new [目录]          创建并切换会话（省略目录使用配置默认值）
                /sessions            列出历史会话
                /resume <完整UUID>   恢复会话
                /history             查看完整历史
                /status              查看会话及执行状态
                /cancel              请求取消当前任务
                /retry-save [UUID]   重试保存（不会继续原任务）
                /exit 或 /quit       关闭并退出
                普通文本提交任务；// 转义任务开头的 /。
                """ + (terminal instanceof PlainTerminalIO ? "普通行模式：用 /cancel 取消；输入 EOF 退出。\n"
                        : "Ctrl+C：运行中取消，空闲时清空输入；Ctrl+D：退出。\n")
                        + (selecting ? "当前请选择目录，回车使用默认目录；也可使用 /sessions 和 /resume。"
                                  : "执行期间不接受新任务或切换会话。"));
    }
    public void selecting(java.nio.file.Path defaultDirectory) {
        terminal.print("默认工作目录：" + defaultDirectory + "\n输入目录或按 Enter 使用默认值；/sessions 查看历史，/resume <id> 恢复，/help 查看帮助。");
    }
    public void session(SessionSnapshot snapshot) {
        terminal.print("会话：" + snapshot.id() + "\n标题：" + snapshot.title() + "\n工作目录：" + snapshot.workspace()
                + "\n最近状态：" + (snapshot.lastRun() == null ? "尚未运行" : snapshot.lastRun().status())
                + (snapshot.unsaved() ? "\n存在未保存状态，请使用 /retry-save。" : ""));
    }
    public void catalog(SessionCatalog catalog) {
        if (catalog.sessions().isEmpty()) terminal.print("暂无历史会话。");
        for (SessionSummary summary : catalog.sessions()) terminal.print(summary.id() + " | " + summary.title()
                + " | " + summary.workspace() + " | " + summary.updatedAt() + " | " + summary.lastRunStatus()
                + (summary.unsaved() ? " | 未保存" : ""));
        for (SessionCatalog.LoadIssue issue : catalog.issues())
            error("无法读取 " + issue.fileName() + "：" + issue.reason() + "；" + issue.message());
    }
    public void history(SessionSnapshot snapshot) {
        if (snapshot.messages().isEmpty()) terminal.print("暂无消息。");
        for (Message message : snapshot.messages()) {
            terminal.print("[" + message.getRole() + "]" + (message.getToolCallId() == null ? "" :
                    " " + message.getToolName() + " id=" + message.getToolCallId() + " success=" + message.getToolSuccess())
                    + "\n" + (message.getContent() == null ? "" : message.getContent()));
            if (message.getToolCalls() != null) for (var call : message.getToolCalls())
                terminal.print("调用 " + call.getToolName() + " id=" + call.getId() + "\n" + call.getArguments());
        }
    }
    public void status(SessionSnapshot snapshot, RunHandle handle) {
        session(snapshot);
        if (handle != null) terminal.print("运行：" + handle.runId() + "；实时状态：" + handle.state());
        else terminal.print("当前空闲。");
    }
    public void event(SessionEvent event) {
        String text = switch (event.type()) {
            case RUN_STARTED -> "任务已提交，运行 ID：" + event.runId();
            case ITERATION_STARTED -> "第 " + ((SessionEvent.Progress) event.payload()).iteration() + " 次迭代";
            case LLM_REQUEST_STARTED -> "正在请求模型…";
            case LLM_RETRY -> com.demo.llm.LLMErrorFormatter.format(((SessionEvent.ModelFailure) event.payload()).failure());
            case LLM_FAILED -> null; // Final persisted diagnostic is rendered once with RUN_FINISHED.
            case ASSISTANT_MESSAGE -> {
                String answer = ((SessionEvent.AssistantMessage) event.payload()).text();
                yield answer == null || answer.isEmpty() ? null : answer;
            }
            case TOOL_STARTED -> "正在执行工具：" + ((SessionEvent.ToolStarted) event.payload()).name();
            case TOOL_RESULT -> {
                var tool = (SessionEvent.ToolFinished) event.payload();
                yield "工具 " + tool.name() + (tool.result().isSuccess() ? " 成功：" : " 失败：") + abbreviate(tool.result().getOutput());
            }
            case CANCEL_REQUESTED -> "已请求取消，等待当前操作及资源清理完成…";
            case RUN_FINISHED -> {
                var result = (SessionEvent.RunFinished) event.payload();
                yield "任务结束：" + result.status() + "；迭代 " + result.iterations()
                        + (result.unsaved() ? "；未保存，请修复存储并使用 /retry-save" : "")
                        + (result.diagnostic() == null ? "" : "\n" + result.diagnostic());
            }
        };
        if (text != null) terminal.printAbove(text);
    }
    static String abbreviate(String text) {
        if (text == null) return "";
        int count = text.codePointCount(0, text.length());
        return count <= 2000 ? text : text.substring(0, text.offsetByCodePoints(0, 2000)) + "…（完整结果见 /history）";
    }
    public void cancellation(RunHandle.CancelResult result) {
        terminal.print(switch (result) {
            case REQUESTED -> "取消请求已接受；完成清理前不能开始下一轮。";
            case ALREADY_REQUESTED -> "已请求取消，仍在等待结束。";
            case TOO_LATE -> "任务正在保存最终状态，已无法取消。";
            case ALREADY_FINISHED -> "任务已经结束。";
        });
    }
    public void info(String message) { terminal.print(message); }
    public void error(String message) { terminal.error("错误：" + message); }
    public void failure(Throwable error) {
        if (error instanceof com.demo.session.storage.SessionStorageException)
            error("存储失败：" + error.getMessage() + "；修复后使用 /retry-save。");
        else if (error instanceof SessionException session)
            error(session.getReason() + "：" + error.getMessage());
        else error("操作失败：" + error.getClass().getSimpleName());
    }
}
