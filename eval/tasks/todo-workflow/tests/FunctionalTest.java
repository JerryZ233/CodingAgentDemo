import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.io.TempDir;
import todo.TodoStore;

public class FunctionalTest {
 @TempDir Path dir;
 @Test void restartRenameAndFilter() throws Exception {
  Path nested = Files.createDirectories(dir.resolve("中文 工作目录"));
  Path file = nested.resolve("待办 数据.tsv");
  String text = "你好 \"quoted\" C:\\tmp\\😀";
  assertEquals(0, CliSupport.run(file, "add", "first").code());
  assertEquals(0, CliSupport.run(file, "add", "second").code());
  assertEquals(0, CliSupport.run(file, "done", "1").code());
  assertEquals(0, CliSupport.run(file, "rename", "1", text).code());
  assertEquals("1\tdone\t" + text + "\n", CliSupport.run(file, "list", "done").output());
  assertEquals("2\topen\tsecond\n", CliSupport.run(file, "list", "open").output());
  assertEquals(List.of(1), new TodoStore(file).listByStatus(true).stream().map(TodoStore.Item::id).toList());
  assertEquals(List.of(2), new TodoStore(file).listByStatus(false).stream().map(TodoStore.Item::id).toList());
 }
 @Test void renameOpenAndOrdering() throws Exception {
  Path file = dir.resolve("order");
  TodoStore store = new TodoStore(file);
  store.add("one"); store.add("two"); store.add("three"); store.complete(2);
  store.rename(1, "renamed");
  assertEquals(List.of("renamed", "three"), new TodoStore(file).listByStatus(false).stream().map(TodoStore.Item::text).toList());
  assertEquals(0, CliSupport.run(file, "rename", "3", "尾 😀").code());
  assertEquals("1\topen\trenamed\n3\topen\t尾 😀\n", CliSupport.run(file, "list", "open").output());
 }
 @Test void renameFailureDoesNotWrite() throws Exception {
  Path file = dir.resolve("unchanged");
  assertEquals(0, CliSupport.run(file, "add", "one").code());
  byte[] before = Files.readAllBytes(file);
  for (String[] args : List.of(new String[]{"rename", "99", "bad"}, new String[]{"rename", "1"},
       new String[]{"rename", "1", "new", "extra"}, new String[]{"rename", "not-an-id", "bad"})) {
   assertNotEquals(0, CliSupport.run(file, args).code());
   assertArrayEquals(before, Files.readAllBytes(file));
  }
 }
 @Test void emptyFilteredList() throws Exception {
  Path file = dir.resolve("missing");
  var result = CliSupport.run(file, "list", "open");
  assertEquals(0, result.code()); assertEquals("", result.output());
  assertFalse(Files.exists(file));
 }
}
