package todo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class TodoStore {
    public record Item(int id, String text, boolean done) {}
    private final Path file;
    private final List<Item> items = new ArrayList<>();

    public TodoStore(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] fields = line.split("\t", 3);
            items.add(new Item(Integer.parseInt(fields[0]), fields[2], Boolean.parseBoolean(fields[1])));
        }
    }

    private void save() throws IOException {
        List<String> lines = new ArrayList<>();
        for (Item item : items) lines.add(item.id() + "\t" + item.done() + "\t" + item.text());
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    public Item add(String text) throws IOException {
        Item item = new Item(items.stream().mapToInt(Item::id).max().orElse(0) + 1, text, false);
        items.add(item);
        save();
        return item;
    }

    public void complete(int id) throws IOException {
        for (int n = 0; n < items.size(); n++) {
            Item item = items.get(n);
            if (item.id() == id) {
                items.set(n, new Item(id, item.text(), true));
                save();
                return;
            }
        }
        throw new IllegalArgumentException("Unknown id");
    }

    public void rename(int id, String text) throws IOException {
        throw new UnsupportedOperationException("Implement rename");
    }

    public List<Item> list() { return List.copyOf(items); }
    public List<Item> listByStatus(boolean done) { return List.of(); }
}
