package com.demo.cli;

import com.demo.session.*;
import com.demo.session.storage.SessionStorageException;

/** Maps typed backend outcomes to product copy; backend diagnostics never reach the terminal. */
final class ChineseMessages {
    private ChineseMessages() { }
    static String status(SessionRun.Status status) {
        if (status == null) return "尚未运行";
        return switch (status) {
            case RUNNING -> "处理中";
            case CANCELLING -> "正在取消";
            case CANCELLED -> "已取消";
            case COMPLETED -> "本轮已结束";
            case LLM_ERROR -> "模型请求失败";
            case MAX_ITERATIONS -> "达到处理上限";
            case TOOL_ERROR -> "操作失败";
            case CONTEXT_BLOCKED -> "上下文准备受阻";
            case FAILED -> "处理失败";
            case INTERRUPTED -> "上次运行已中断";
            case STORAGE_ERROR -> "保存失败";
        };
    }
    static String state(RunHandle.State state) {
        return switch (state) {
            case WAITING_APPROVAL -> "等待命令批准";
            case STARTING -> "正在准备";
            case RUNNING -> "处理中";
            case CANCELLING -> "正在取消";
            case FINISHING -> "正在保存和清理";
            case FINISHED -> "本轮已结束";
        };
    }
    static String failure(Throwable failure) {
        if (failure instanceof SessionStorageException storage) return switch (storage.getReason()) {
            case NOT_FOUND -> "未找到该会话，请使用 /sessions 查看可选会话。";
            case MALFORMED_DATA -> "会话文件已损坏，无法读取。详细原因已记录到日志。";
            case UNSUPPORTED_VERSION -> "当前程序无法读取此版本的会话文件。";
            case IO_FAILURE -> "存储失败，请检查存储目录权限和磁盘空间，再使用 /retry-save。已完成的文件修改会保留。";
            case CONFLICT -> "会话正在被其他操作修改，请稍后重新加载并重试。";
        };
        if (failure instanceof SessionException session) return switch (session.getReason()) {
            case NOT_FOUND -> "未找到该会话，请使用 /sessions 查看可选会话。";
            case INVALID_WORKSPACE -> "工作目录不可用，请选择已存在且可读的目录。";
            case BUSY -> "当前任务尚未结束，请等待完成或使用 /cancel。";
            case INVALID_INPUT -> "输入无效，请使用 /help 查看用法。";
            case UNSAVED_SESSION -> "存在未保存的会话，请先使用 /retry-save。";
            case CLOSED -> "会话服务已关闭。";
            case CLOSING -> "正在关闭服务，请等待资源释放后重试。";
            case SHUTDOWN_TIMEOUT -> "关闭尚未完成，请稍后重试 /exit。";
            case RESOURCE_FAILURE -> "资源尚未释放，请使用 /exit 重试清理。";
        };
        if (failure instanceof java.nio.file.InvalidPathException) return "目录格式无效，请重新输入。";
        return "操作失败，详细原因已记录到日志。";
    }
    static String finished(SessionRun.Status status) {
        return switch (status) {
            case COMPLETED -> null;
            case CANCELLED -> "任务已取消，已完成的文件修改会保留。";
            case LLM_ERROR -> "模型请求失败，本轮已停止。详细原因已记录到日志。";
            case MAX_ITERATIONS -> "已达到本轮处理上限，任务可能尚未完成。";
            case CONTEXT_BLOCKED -> "上下文准备受阻，本轮已暂停；请检查上下文预算、记忆文件及归档完整性后重试。原始消息已保留。";
            case STORAGE_ERROR -> "保存失败，请修复存储并使用 /retry-save。已完成的文件修改会保留。";
            case INTERRUPTED -> "上次运行已中断，已保留已有记录。";
            case CANCELLING -> "正在取消任务…";
            case RUNNING -> "正在处理任务…";
            case TOOL_ERROR, FAILED -> "本轮处理失败，详细原因已记录到日志。";
        };
    }
}
