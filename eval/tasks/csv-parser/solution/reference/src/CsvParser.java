import java.util.*;
public class CsvParser {
 public static List<List<String>> parse(String csv) {
  List<List<String>> rows=new ArrayList<>();
  if(csv.isEmpty())return rows;
  List<String> row=new ArrayList<>();StringBuilder field=new StringBuilder();boolean quoted=false;
  for(int i=0;i<csv.length();i++) {
   char c=csv.charAt(i);
   if(c=='"') {
    if(quoted && i+1<csv.length() && csv.charAt(i+1)=='"'){field.append('"');i++;}
    else quoted=!quoted;
   } else if(c==',' && !quoted) {row.add(field.toString());field.setLength(0);}
   else if((c=='\n'||c=='\r') && !quoted) {
    if(c=='\r' && i+1<csv.length() && csv.charAt(i+1)=='\n')i++;
    row.add(field.toString());rows.add(row);row=new ArrayList<>();field.setLength(0);
   } else field.append(c);
  }
  if(field.length()>0 || !row.isEmpty() || (csv.charAt(csv.length()-1)!='\n' && csv.charAt(csv.length()-1)!='\r')) {
   row.add(field.toString());rows.add(row);
  }
  return rows;
 }
}
