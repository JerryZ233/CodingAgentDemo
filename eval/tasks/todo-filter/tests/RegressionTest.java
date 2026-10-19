import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

import java.nio.file.*;
import org.junit.jupiter.api.io.TempDir;
public class RegressionTest {
 @TempDir Path dir;
 @Test void persist() throws Exception {Path f=dir.resolve("todo");var s=new TodoStore(f);assertEquals(1,s.add("first").id());assertEquals(2,s.add("second").id());s.complete(1);var reloaded=new TodoStore(f);assertEquals(2,reloaded.list().size());assertTrue(reloaded.list().get(0).done());assertEquals("second",reloaded.list().get(1).text());}
 @Test void missing() throws Exception {assertTrue(new TodoStore(dir.resolve("missing")).list().isEmpty());}
 @Test void cli() throws Exception {Path f=dir.resolve("cli");TodoCli.main(new String[]{f.toString(),"add","task"});TodoCli.main(new String[]{f.toString(),"done","1"});assertTrue(new TodoStore(f).list().get(0).done());}
}
