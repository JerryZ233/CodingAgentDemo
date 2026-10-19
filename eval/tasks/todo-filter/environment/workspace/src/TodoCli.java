import java.nio.file.*;
import java.util.*;
public class TodoCli {
 public static void main(String[] args) throws Exception {
  TodoStore store=new TodoStore(Path.of(args[0]));
  switch(args[1]) {
   case "add" -> store.add(args[2]);
   case "done" -> store.complete(Integer.parseInt(args[2]));
   case "list" -> {for(var i:store.list())System.out.println(i.id()+"\t"+(i.done()?"done":"open")+"\t"+i.text());}
   default -> throw new IllegalArgumentException("Unknown command");
  }
 }
}
