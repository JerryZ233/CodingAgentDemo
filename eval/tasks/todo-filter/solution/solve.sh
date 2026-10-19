#!/bin/sh
set -eu
cat > /workspace/src/TodoStore.java <<'JAVA'
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
public class TodoStore {
 public record Item(int id,String text,boolean done) {}
 private final Path file;
 private final List<Item> items=new ArrayList<>();
 public TodoStore(Path file) throws IOException {
  this.file=file;
  if(Files.exists(file))for(String line:Files.readAllLines(file)) {
   String[] p=line.split("\t",3);items.add(new Item(Integer.parseInt(p[0]),p[2],Boolean.parseBoolean(p[1])));
  }
 }
 private void save() throws IOException {
  List<String> lines=new ArrayList<>();for(Item i:items)lines.add(i.id()+"\t"+i.done()+"\t"+i.text());
  Files.write(file,lines);
 }
 public Item add(String text) throws IOException {
  Item item=new Item(items.stream().mapToInt(Item::id).max().orElse(0)+1,text,false);items.add(item);save();return item;
 }
 public void complete(int id) throws IOException {
  for(int n=0;n<items.size();n++){Item i=items.get(n);if(i.id()==id){items.set(n,new Item(i.id(),i.text(),true));save();return;}}
  throw new IllegalArgumentException("Unknown id");
 }
 public List<Item> list(){return List.copyOf(items);}
 public List<Item> listByStatus(boolean done){return items.stream().filter(i -> i.done()==done).toList();}
}

JAVA
cat > /workspace/src/TodoCli.java <<'JAVA'
import java.nio.file.*;
import java.util.*;
public class TodoCli {
 public static void main(String[] args) throws Exception {
  TodoStore store=new TodoStore(Path.of(args[0]));
  switch(args[1]) {
   case "add" -> store.add(args[2]);
   case "done" -> store.complete(Integer.parseInt(args[2]));
   case "list" -> {for(var i:args.length>2?store.listByStatus(args[2].equals("done")):store.list())System.out.println(i.id()+"\t"+(i.done()?"done":"open")+"\t"+i.text());}
   default -> throw new IllegalArgumentException("Unknown command");
  }
 }
}

JAVA
