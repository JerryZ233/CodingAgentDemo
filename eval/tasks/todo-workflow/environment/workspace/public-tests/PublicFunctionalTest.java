import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import todo.TodoStore;

public class PublicFunctionalTest {
    @TempDir Path dir;

    @Test void renamedItemPersists() throws Exception {
        Path file = dir.resolve("todos");
        TodoStore store = new TodoStore(file);
        store.add("before");
        store.rename(1, "after");
        assertEquals("after", new TodoStore(file).list().get(0).text());
    }

    @Test void filteredListsPreserveOrder() throws Exception {
        TodoStore store = new TodoStore(dir.resolve("todos"));
        store.add("first");
        store.add("second");
        store.complete(1);
        assertEquals(List.of(1), store.listByStatus(true).stream().map(TodoStore.Item::id).toList());
        assertEquals(List.of(2), store.listByStatus(false).stream().map(TodoStore.Item::id).toList());
    }
}
