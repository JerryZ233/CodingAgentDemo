import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

import java.nio.file.*;
import org.junit.jupiter.api.io.TempDir;
public class FunctionalTest {
 @TempDir Path dir;
 @Test void filter() throws Exception {var s=new TodoStore(dir.resolve("todo"));s.add("first");s.add("second");s.complete(1);assertEquals(List.of(2),s.listByStatus(false).stream().map(TodoStore.Item::id).toList());assertEquals(List.of(1),s.listByStatus(true).stream().map(TodoStore.Item::id).toList());}
 @Test void empty() throws Exception {assertTrue(new TodoStore(dir.resolve("empty")).listByStatus(true).isEmpty());}
 @Test void cli() throws Exception {
  Path f=dir.resolve("cli");var s=new TodoStore(f);s.add("first");s.add("second");s.complete(1);
  var previous=System.out;var bytes=new java.io.ByteArrayOutputStream();
  try(var output=new java.io.PrintStream(bytes,true,java.nio.charset.StandardCharsets.UTF_8)) {
   System.setOut(output);TodoCli.main(new String[]{f.toString(),"list","done"});
  }finally{System.setOut(previous);}
  assertEquals("1\tdone\tfirst",bytes.toString(java.nio.charset.StandardCharsets.UTF_8).strip());
 }
}
