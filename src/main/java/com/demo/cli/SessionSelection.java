package com.demo.cli;

import com.demo.session.SessionCatalog;
import com.demo.session.SessionSnapshot;
import java.util.List;

/** Frozen until the user explicitly refreshes /sessions. Never persisted. */
final class SessionSelection {
    private List<String> ids = List.of();
    void refresh(SessionCatalog catalog) { ids = catalog.sessions().stream().map(s -> s.id()).toList(); }
    String resolve(String reference) {
        if (reference != null && reference.matches("[0-9]+")) {
            try {
                int index = Integer.parseInt(reference) - 1;
                if (index >= 0 && index < ids.size()) return ids.get(index);
            } catch (NumberFormatException ignored) { }
            throw new CliInputException("请先使用 /sessions 查看可选会话，再输入有效序号。");
        }
        try { SessionSnapshot.requireId(reference); return reference; }
        catch (IllegalArgumentException | NullPointerException e) {
            throw new CliInputException("请输入有效会话序号；请先使用 /sessions 查看可选会话。");
        }
    }
}
