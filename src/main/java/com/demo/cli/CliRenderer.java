package com.demo.cli;

import com.demo.config.Config;
import com.demo.model.Message;
import com.demo.session.*;

public final class CliRenderer {
    private final TerminalIO terminal;
    private String model = "模拟模式（未调用真实模型）";
    public CliRenderer(TerminalIO terminal) { this.terminal = terminal; }
    public void welcome(Config config) {
        model = config.isConfigured() ? config.getModel() : "模拟模式（未调用真实模型）";
        terminal.print("=== 编程助手 ===" + (config.isConfigured() ? "" : "\n未配置模型密钥：不会调用真实模型或执行文件操作。"));
        if (terminal instanceof PlainTerminalIO)
            terminal.print("普通行模式：使用 /cancel 取消任务；Ctrl+C 按操作系统退出信号处理。");
    }
    public static String usage() {
        return """
                用法：CodingAgentDemo [--workspace <目录> | --session <会话标识>] [--session-dir <目录>] [--] [任务]
                无任务：进入交互会话；有任务：执行一轮、自动保存并退出。
                --help 显示此帮助。
                """;
    }
    public void help(boolean selecting) {
        terminal.print("""
                /new [目录]          创建并切换会话（省略目录使用配置默认值）
                /sessions            列出历史会话
                /resume <序号>       恢复列表中的会话（先执行 /sessions）
                /history             查看对话历史
                /status              查看会话及执行状态
                /cancel              请求取消当前任务
                /retry-save [序号]   重试保存（省略序号保存当前会话，不会继续原任务）
                /exit 或 /quit       关闭并退出
                普通文本提交任务；// 转义任务开头的 /。
                """ + (terminal instanceof PlainTerminalIO ? "普通行模式：用 /cancel 取消；输入 EOF 退出。\n"
                        : "Ctrl+C：运行中取消，空闲时清空输入；Ctrl+D：退出。\n")
                        + (selecting ? "当前请选择目录，回车使用默认目录；也可使用 /sessions 和 /resume。"
                                  : "执行期间不接受新任务或切换会话。"));
    }
    public void selecting(java.nio.file.Path defaultDirectory) {
        terminal.print("默认工作目录：" + defaultDirectory + "\n输入目录或按回车使用默认值；/sessions 查看历史，/resume <序号> 恢复，/help 查看帮助。");
    }
    public void session(SessionSnapshot snapshot) {
        terminal.print("会话：" + snapshot.title() + "\n模型：" + model + "\n工作目录：" + snapshot.workspace()
                + "\n最近状态：" + ChineseMessages.status(snapshot.lastRun() == null ? null : snapshot.lastRun().status())
                + (snapshot.unsaved() ? "\n存在未保存状态，请使用 /retry-save。" : ""));
    }
    public void catalog(SessionCatalog catalog) {
        if (catalog.sessions().isEmpty()) terminal.print("暂无历史会话。");
        int index = 1;
        for (SessionSummary summary : catalog.sessions()) terminal.print(index++ + " | " + summary.title()
                + " | " + summary.workspace() + " | " + java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                        .withZone(java.time.ZoneId.systemDefault()).format(summary.updatedAt())
                + " | " + ChineseMessages.status(summary.lastRunStatus()) + (summary.unsaved() ? " | 未保存" : ""));
        if (!catalog.issues().isEmpty()) error("部分历史会话无法读取，详细原因已记录到日志。");
    }
    public void history(SessionSnapshot snapshot) {
        boolean displayed = false;
        for (Message message : snapshot.messages()) {
            boolean user = "user".equals(message.getRole());
            if (!user && !"assistant".equals(message.getRole())) continue;
            String content = message.getContent();
            if (content == null || content.isBlank() || (!user && isDiagnostic(content, snapshot))) continue;
            terminal.print((user ? "用户：" : "助手：") + "\n" + content);
            displayed = true;
        }
        if (!displayed) terminal.print("暂无对话消息。");
    }
    public void status(SessionSnapshot snapshot, RunHandle handle) {
        session(snapshot);
        if (handle != null) terminal.print("当前状态：" + ChineseMessages.state(handle.state()));
        else terminal.print("当前空闲。");
        terminal.print(snapshot.unsaved() ? "保存情况：尚未保存。" : "保存情况：已保存。");
    }
    public void event(SessionEvent event) {
        String text = switch (event.type()) {
            case RUN_STARTED -> null;
            case ITERATION_STARTED -> null;
            case LLM_REQUEST_STARTED -> "正在请求模型…";
            case LLM_RETRY -> {
                var failure = ((SessionEvent.ModelFailure) event.payload()).failure();
                yield "重新连接...（" + failure.attempt() + "/" + (failure.maxAttempts() - 1) + "）";
            }
            case LLM_FAILED -> null; // RUN_FINISHED renders the user-facing failure once.
            case ASSISTANT_MESSAGE -> {
                String answer = ((SessionEvent.AssistantMessage) event.payload()).text();
                yield answer == null || answer.isBlank() ? null : answer;
            }
            case TOOL_STARTED -> switch (((SessionEvent.ToolStarted) event.payload()).name()) {
                case "read_file" -> "正在读取文件…";
                case "write_file" -> "正在写入文件…";
                case "list_files" -> "正在查看目录…";
                case "run_shell" -> "正在执行命令…";
                default -> "正在执行操作…";
            };
            case TOOL_RESULT -> {
                var tool = (SessionEvent.ToolFinished) event.payload();
                yield tool.result().isSuccess() ? null : "本次操作未成功，正在继续处理…";
            }
            case CANCEL_REQUESTED -> "已请求取消，等待当前操作及资源清理完成…";
            case RUN_FINISHED -> {
                var result = (SessionEvent.RunFinished) event.payload();
                String message = ChineseMessages.finished(result.status());
                if (result.unsaved() && result.status() != SessionRun.Status.STORAGE_ERROR)
                    message = (message == null ? "" : message + "\n")
                            + "尚未保存，请修复存储并使用 /retry-save。已完成的文件修改会保留。";
                yield message;
            }
        };
        if (text != null) terminal.printAbove(text);
    }
    private static boolean isDiagnostic(String text, SessionSnapshot snapshot) {
        return text.startsWith("[server]\n") || text.startsWith("[client]\n")
                || text.startsWith("Error: ") || text.equals("Failed to get response from LLM.")
                || (snapshot.lastRun() != null && snapshot.lastRun().status() == SessionRun.Status.LLM_ERROR
                        && text.equals(snapshot.lastRun().message()));
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
        error(ChineseMessages.failure(error));
    }
}
