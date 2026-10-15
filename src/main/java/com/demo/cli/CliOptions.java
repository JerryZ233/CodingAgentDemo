package com.demo.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import com.demo.session.SessionSnapshot;

public record CliOptions(Path workspace, String sessionId, Path sessionDirectory, String task, boolean help) {
    public enum Mode { INTERACTIVE, SINGLE_TASK }
    public Mode mode() { return task == null ? Mode.INTERACTIVE : Mode.SINGLE_TASK; }

    public static CliOptions parse(String[] args) {
        Path workspace = null, directory = null;
        String session = null;
        boolean help = false, positionalOnly = false, taskProvided = false;
        List<String> words = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!positionalOnly && arg.equals("--")) { positionalOnly = true; taskProvided = true; continue; }
            if (!positionalOnly && arg.startsWith("-")) {
                switch (arg) {
                    case "--help" -> help = true;
                    case "--workspace", "--session", "--session-dir" -> {
                        if (++i >= args.length || args[i].isBlank() || args[i].startsWith("--"))
                            throw new IllegalArgumentException("缺少参数值：" + arg);
                        switch (arg) {
                            case "--workspace" -> {
                                if (workspace != null) throw new IllegalArgumentException("重复参数：" + arg);
                                workspace = Path.of(args[i]).toAbsolutePath().normalize();
                            }
                            case "--session" -> {
                                if (session != null) throw new IllegalArgumentException("重复参数：" + arg);
                                session = args[i];
                                SessionSnapshot.requireId(session);
                            }
                            default -> {
                                if (directory != null) throw new IllegalArgumentException("重复参数：" + arg);
                                directory = Path.of(args[i]).toAbsolutePath().normalize();
                            }
                        }
                    }
                    default -> throw new IllegalArgumentException("未知参数：" + arg);
                }
            } else { words.add(arg); taskProvided = true; }
        }
        if (workspace != null && session != null) throw new IllegalArgumentException("--workspace 与 --session 不能同时使用");
        String task = taskProvided ? String.join(" ", words).trim() : null;
        if (task != null && task.isBlank()) throw new IllegalArgumentException("任务不能为空");
        return new CliOptions(workspace, session, directory, task, help);
    }
}
