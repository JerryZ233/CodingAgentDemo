package todo;

import java.io.IOException;
import java.nio.file.Path;

public final class TodoService {
    public void execute(String[] args) throws IOException {
        if (args.length < 2) throw new IllegalArgumentException("Expected file and command");
        String command = args[1];
        int expected = switch (command) {
            case "add", "done" -> 3;
            case "list" -> 2;
            default -> throw new IllegalArgumentException("Unknown command");
        };
        if (args.length != expected) throw new IllegalArgumentException("Wrong argument count");
        TodoStore store = new TodoStore(Path.of(args[0]));
        switch (command) {
            case "add" -> store.add(args[2]);
            case "done" -> store.complete(Integer.parseInt(args[2]));
            case "list" -> {
                for (var item : store.list()) print(item);
            }
        }
    }

    private void print(TodoStore.Item item) {
        System.out.println(item.id() + "\t" + (item.done() ? "done" : "open") + "\t" + item.text());
    }
}
