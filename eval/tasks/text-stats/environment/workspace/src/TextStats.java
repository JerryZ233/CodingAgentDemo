import java.util.*;
public class TextStats {
    public static int lines(String text) { return text.isEmpty() ? 0 : text.split("\\R", -1).length; }
    public static int characters(String text) { return text.length(); }
    public static Map<String, Long> frequencies(String text) { return Map.of(); }
}
