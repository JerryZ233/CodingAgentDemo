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
                /memory              查看项目历史检索用法
                /status              查看会话及执行状态
                /cancel              请求取消当前任务
                /revoke              撤销当前任务的可复用命令授权
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

    public void memoryHelp() {
        terminal.print("/memory search <查询>  搜索当前项目的归档历史\n/memory read <序号>  查看上次搜索结果详情\n"
                + "需开启 memory.enabled；查看结果不会自动加入对话。");
    }

    public void memorySearch(com.google.gson.JsonObject result) {
        int index = 1;
        for (var value : result.getAsJsonArray("results")) {
            var hit = value.getAsJsonObject();
            terminal.print(index++ + " | " + hit.get("created_at").getAsString() + "\n"
                    + hit.get("summary_preview").getAsString());
        }
        if (index == 1) terminal.print("未找到匹配的已提交历史。");
        if (!result.get("complete").getAsBoolean()) terminal.print("结果不完整，请缩小查询范围；未找到结果不能证明没有相关历史。");
        if (index > 1) terminal.print("使用 /memory read <序号> 查看详情。");
    }

    public void memoryRead(com.google.gson.JsonObject result) {
        terminal.print("归档记录：\n" + result.get("archive_summary").getAsString()
                + "\n当时的连续摘要：\n" + result.get("continuation_summary").getAsString());
        if (!result.get("source_status").getAsString().equals("AVAILABLE")) terminal.print("原始来源当前不可用；仍可查看归档摘要。");
        if (!result.get("complete").getAsBoolean()) terminal.print("当前展示受长度或来源限制，不代表完整记录。");
    }
    public void event(SessionEvent event) {
        String text = switch (event.type()) {
            case APPROVAL -> ((SessionEvent.Approval)event.payload()).kind().equals("invalidated")?"执行入口或目录已变化，需要重新审批。":null;
            case COMMAND_OUTPUT -> "命令输出（任务数据）：\n│ "+((SessionEvent.CommandOutput)event.payload()).text().replace("\n","\n│ ");
            case RUN_STARTED -> null;
            case ITERATION_STARTED -> null;
            case CONTEXT_PREPARED -> null; // Available to service consumers; do not clutter terminal progress.
            case MEMORY_COMPACTION -> switch (((SessionEvent.MemoryCompaction) event.payload()).phase()) {
                case STARTED -> "正在整理较早的会话，保留当前要求与未解决问题…";
                case COMPLETED -> "会话摘要已保存。";
                case FAILED -> "本次会话整理未成功；仅在完整上下文仍能容纳时继续。";
            };
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
                case "search_text" -> "正在搜索文件内容…";
                case "memory_search" -> "正在搜索项目历史…";
                case "memory_read" -> "正在读取项目历史…";
                case "run_shell" -> "正在准备命令…";
                default -> "正在执行操作…";
            };
            case TOOL_RESULT -> {
                var tool = (SessionEvent.ToolFinished) event.payload();
                if("run_shell".equals(tool.name()))yield shellSummary(tool);
                yield tool.result().isSuccess() ? writeSummary(tool) : "本次操作未成功，正在继续处理…";
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
    private static String shellSummary(SessionEvent.ToolFinished tool) {
        try {
            var data=com.google.gson.JsonParser.parseString(tool.result().getOutput()).getAsJsonObject();
            String status=data.has("status")?data.get("status").getAsString():"invalid";
            String text=switch(status) {
                case "exited" -> "命令已结束，退出码 "+data.get("exit_code").getAsInt()+"。";
                case "timed_out" -> "命令执行超时，已停止并收集已有输出。";
                case "output_limit_exceeded" -> "命令输出达到硬上限，已停止执行。";
                case "output_read_failed" -> "命令输出读取失败，保留已有输出。";
                case "cancelled" -> data.has("started") && !data.get("started").getAsBoolean()?"命令未启动：任务已取消。":"命令已取消，已完成的影响不会回滚。";
                case "USER_DENIED" -> "命令未启动：用户拒绝。";
                case "APPROVAL_CHANNEL_UNAVAILABLE" -> "命令未启动：当前终端不支持安全交互审批，请在交互控制台运行。";
                case "APPROVAL_LIMIT_EXCEEDED" -> "已达到本轮命令审批次数上限，任务已停止。";
                case "EXECUTION_LIMIT_EXCEEDED" -> "已达到本轮命令执行次数上限，任务已停止。";
                case "START_FAILED" -> data.has("started") && data.get("started").getAsBoolean()?"命令启动初始化失败，可能已经产生影响。":"命令未启动：启动失败。";
                default -> "命令未完成，正在处理执行结果。";
            };
            if(data.has("output_file"))text+="\n输出已保存："+data.get("output_file").getAsString();
            if(data.has("output_save_error"))text+="\n输出文件保存失败。";
            return text;
        }catch(RuntimeException e){return "命令未启动或未完成，正在处理执行结果。";}
    }
    private static String writeSummary(SessionEvent.ToolFinished tool) {
        if (!"write_file".equals(tool.name())) return null;
        try {
            var data = com.google.gson.JsonParser.parseString(tool.result().getOutput()).getAsJsonObject();
            String action = data.get("action").getAsString();
            if (!action.equals("created") && !action.equals("overwritten")) return null;
            return (action.equals("created") ? "已创建文件 " : "已更新文件 ")
                    + new com.google.gson.JsonPrimitive(data.get("path").getAsString())
                    + "（" + data.get("bytes").getAsInt() + " 字节）。";
        } catch (RuntimeException ignored) { return null; } // Older persisted/custom tool payloads remain supported.
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
