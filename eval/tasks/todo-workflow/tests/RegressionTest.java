import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.io.TempDir;
import todo.TodoStore;

public class RegressionTest {
 @TempDir Path dir;
 @Test void originalCommandsAndIdsPersist() throws Exception {
  Path file = dir.resolve("todos");
  assertEquals(0, CliSupport.run(file, "add", "first").code());
  assertEquals(0, CliSupport.run(file, "add", "second").code());
  assertEquals(0, CliSupport.run(file, "done", "1").code());
  assertEquals("1\tdone\tfirst\n2\topen\tsecond\n", CliSupport.run(file, "list").output());
  TodoStore store = new TodoStore(file);
  assertEquals(2, store.list().size()); assertEquals(3, store.add("third").id());
  assertEquals(List.of(1, 2, 3), new TodoStore(file).list().stream().map(TodoStore.Item::id).toList());
 }
 @Test void invalidCommandsDoNotChangeFile() throws Exception {
  Path file = dir.resolve("todos");
  CliSupport.run(file, "add", "kept"); byte[] before = Files.readAllBytes(file);
  for (String[] args : List.of(new String[]{"done", "99"}, new String[]{"list", "invalid"},
       new String[]{"add"}, new String[]{"add", "x", "extra"}, new String[]{"done", "1", "extra"},
       new String[]{"list", "open", "extra"}, new String[]{"unknown"}, new String[]{})) {
   assertNotEquals(0, CliSupport.run(file, args).code()); assertArrayEquals(before, Files.readAllBytes(file));
  }
 }
 @Test void missingFileAndEmptyList() throws Exception {
  Path file = dir.resolve("missing"); assertTrue(new TodoStore(file).list().isEmpty());
  assertEquals("", CliSupport.run(file, "list").output()); assertFalse(Files.exists(file));
 }
}
