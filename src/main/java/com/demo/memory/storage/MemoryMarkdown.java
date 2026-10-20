package com.demo.memory.storage;

import com.demo.memory.model.MemoryEdit;
import com.demo.memory.model.MemoryItem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Preserves unmanaged Markdown exactly; only recognized item blocks are replaced. */
final class MemoryMarkdown {
    private static final Pattern ITEM = Pattern.compile(
            "^<!-- memory:item id=\"([^\"]+)\" kind=\"(fact|rule|decision|preference)\"(?: status=\"(active|inactive)\")? -->\\r?\\n"
                    + "([\\s\\S]*?)^<!-- /memory:item -->[ \\t]*(?:\\r?\\n|$)", Pattern.MULTILINE);
    private record Block(int start, int end, MemoryItem item) { }

    private List<Block> blocks(String text) {
        List<Block> blocks = new ArrayList<>();
        var ids = new HashSet<String>();
        var matcher = ITEM.matcher(text);
        int cursor = 0;
        while (matcher.find()) {
            checkUnmanaged(text.substring(cursor, matcher.start()));
            var item = new MemoryItem(matcher.group(1), MemoryItem.Kind.valueOf(matcher.group(2).toUpperCase(Locale.ROOT)),
                    !"inactive".equals(matcher.group(3)), matcher.group(4));
            if (!ids.add(item.id())) throw new IllegalArgumentException("Duplicate memory item ID");
            blocks.add(new Block(matcher.start(), matcher.end(), item));
            cursor = matcher.end();
        }
        checkUnmanaged(text.substring(cursor));
        return blocks;
    }

    private void checkUnmanaged(String text) {
        if (text.contains("<!-- memory:") || text.contains("<!-- /memory:")) {
            throw new IllegalArgumentException("Malformed memory item marker");
        }
    }

    List<MemoryItem> read(String text) { return blocks(text).stream().map(Block::item).toList(); }

    String apply(String original, List<MemoryEdit> edits) {
        String text = original;
        for (var edit : edits) {
            if (edit.action() == MemoryEdit.Action.IGNORE) continue;
            var blocks = blocks(text);
            var existing = blocks.stream().filter(block -> block.item().id().equals(edit.itemId())).findFirst();
            if (edit.action() == MemoryEdit.Action.ADD) {
                if (existing.isPresent()) throw MemoryStorageException.conflict();
                text += (text.isEmpty() || text.endsWith("\n") ? "" : "\n")
                        + render(new MemoryItem(edit.itemId(), edit.kind(), true, edit.body()));
            } else {
                var block = existing.orElseThrow(MemoryStorageException::conflict);
                var item = edit.action() == MemoryEdit.Action.INVALIDATE
                        ? new MemoryItem(block.item().id(), block.item().kind(), false, block.item().body())
                        : new MemoryItem(edit.itemId(), edit.kind(), true, edit.body());
                text = text.substring(0, block.start()) + render(item) + text.substring(block.end());
            }
        }
        read(text);
        return text;
    }

    private String render(MemoryItem item) {
        return "<!-- memory:item id=\"" + item.id() + "\" kind=\"" + item.kind().name().toLowerCase(Locale.ROOT)
                + "\" status=\"" + (item.active() ? "active" : "inactive") + "\" -->\n"
                + item.body() + "\n<!-- /memory:item -->\n";
    }
}
