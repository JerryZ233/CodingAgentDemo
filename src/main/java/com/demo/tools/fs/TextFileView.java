package com.demo.tools.fs;

import java.util.ArrayList;
import java.util.List;

/** Line offsets into the original text; CRLF, LF and CR are preserved exactly. */
public final class TextFileView {
    private final String text;
    private final List<Integer> starts = new ArrayList<>();
    private final List<Integer> ends = new ArrayList<>();

    public TextFileView(String text) {
        this.text = text;
        for (int start = 0, position = 0; position < text.length();) {
            starts.add(start);
            while (position < text.length() && text.charAt(position) != '\r' && text.charAt(position) != '\n') position++;
            ends.add(position);
            if (position < text.length()) {
                char separator = text.charAt(position++);
                if (separator == '\r' && position < text.length() && text.charAt(position) == '\n') position++;
            }
            start = position;
        }
    }

    public int totalLines() { return starts.size(); }
    public String line(int number) { return text.substring(starts.get(number - 1), ends.get(number - 1)); }
    public String slice(int startLine, int count) {
        if (count == 0) return "";
        int following = startLine - 1 + count;
        return text.substring(starts.get(startLine - 1), following < starts.size() ? starts.get(following) : text.length());
    }
}
