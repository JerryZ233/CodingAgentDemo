#!/bin/sh
set -eu
cat > /workspace/src/TextStats.java <<'JAVA'
import java.util.*;
public class TextStats {
    public static int lines(String text) { return text.isEmpty() ? 0 : text.split("\\R", -1).length; }
    public static int characters(String text) { return text.length(); }
    public static Map<String, Long> frequencies(String text) { Map<String, Long> counts = new TreeMap<>();
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!word.isEmpty()) counts.merge(word, 1L, Long::sum);
        }
        return counts; }
}

JAVA
