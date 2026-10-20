package com.demo.cli;

import java.util.Locale;
import com.demo.session.SessionSnapshot;

public final class CliCommandParser {
    public CliCommand parse(String line) {
        String input = line.strip();
        if (input.isEmpty()) return new CliCommand.Empty();
        if (input.startsWith("//")) return new CliCommand.Task(input.substring(1));
        if (!input.startsWith("/")) return new CliCommand.Task(line);
        int split = 0;
        while (split < input.length() && !Character.isWhitespace(input.charAt(split))) split++;
        String name = input.substring(0, split).toLowerCase(Locale.ROOT);
        String argument = input.substring(split).strip();
        return switch (name) {
            case "/memory" -> memory(argument);
            case "/new" -> new CliCommand.NewSession(argument.isEmpty() ? null : directory(argument));
            case "/resume" -> { requireReference(argument); yield new CliCommand.Resume(argument); }
            case "/retry-save" -> {
                if (!argument.isEmpty()) requireReference(argument);
                yield new CliCommand.RetrySave(argument.isEmpty() ? null : argument);
            }
            case "/revoke", "/sessions", "/history", "/status", "/cancel", "/help", "/exit", "/quit" -> {
                if (!argument.isEmpty()) throw new CliInputException(name + " 不接受参数");
                yield switch (name) {
                    case "/sessions" -> new CliCommand.Sessions();
                    case "/history" -> new CliCommand.History();
                    case "/status" -> new CliCommand.Status();
                    case "/revoke" -> new CliCommand.Revoke();
                    case "/cancel" -> new CliCommand.Cancel();
                    case "/help" -> new CliCommand.Help();
                    default -> new CliCommand.Exit();
                };
            }
            default -> throw new CliInputException("未知命令；使用 /help，或用 // 提交以 / 开头的任务。");
        };
    }

    private static CliCommand memory(String argument) {
        if (argument.isEmpty() || argument.equalsIgnoreCase("help")) return new CliCommand.MemoryHelp();
        String[] parts = argument.split("\\s+", 2);
        if (parts.length == 2 && parts[0].equalsIgnoreCase("search") && !parts[1].isBlank()) {
            return new CliCommand.MemorySearch(parts[1].strip());
        }
        if (parts.length == 2 && parts[0].equalsIgnoreCase("read")) {
            try {
                int index = Integer.parseInt(parts[1].strip());
                if (index >= 1 && index <= 10) return new CliCommand.MemoryRead(index);
            } catch (NumberFormatException ignored) { }
        }
        throw new CliInputException("使用 /memory search <查询> 搜索，再用 /memory read <序号> 查看。");
    }

    private static void requireReference(String reference) {
        if (reference.matches("[0-9]+")) return;
        try { SessionSnapshot.requireId(reference); }
        catch (IllegalArgumentException e) { throw new CliInputException("请输入会话序号；先使用 /sessions 查看可选会话。"); }
    }

    static String directory(String text) {
        String value = text.strip();
        if (value.startsWith("\"") || value.startsWith("'")) {
            char quote = value.charAt(0);
            if (value.length() < 2 || value.charAt(value.length() - 1) != quote)
                throw new CliInputException("目录引号未闭合");
            value = value.substring(1, value.length() - 1);
        }
        if (value.isBlank()) throw new CliInputException("目录不能为空");
        return value;
    }
}
