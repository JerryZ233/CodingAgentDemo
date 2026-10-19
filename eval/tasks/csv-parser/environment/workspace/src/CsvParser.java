import java.util.*;
public class CsvParser {
 public static List<List<String>> parse(String csv) {
  List<List<String>> rows = new ArrayList<>();
  if (csv.isEmpty()) return rows;
  String[] lines = csv.split("\\r?\\n", -1);
  for (int i=0; i<lines.length; i++) {
   if (i==lines.length-1 && lines[i].isEmpty()) break;
   rows.add(Arrays.asList(lines[i].split(",", -1)));
  }
  return rows;
 }
}
