import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

public class FunctionalTest {
 @Test void commaAndEscapes() {
  assertEquals(List.of(List.of("a,b", "say \"hi\"", "")), CsvParser.parse("\"a,b\",\"say \"\"hi\"\"\",\"\""));
 }
 @Test void quotedUnicodeAndCrLf() {
  assertEquals(List.of(List.of("你好,😀", "a\r\nb", "C:\\tmp\\x"), List.of("尾", "", "z")),
    CsvParser.parse("\"你好,😀\",\"a\r\nb\",C:\\tmp\\x\r\n尾,,z\r\n"));
 }
 @Test void combinations() {
  assertEquals(List.of(List.of("\"", "x\ny", "a,b"), List.of("", "终点😀")),
    CsvParser.parse("\"\"\"\",\"x\ny\",\"a,b\"\n,终点😀"));
 }
 @Test void quotedEmptyFinalField() {
  assertEquals(List.of(List.of("a", "")), CsvParser.parse("a,\"\""));
  assertEquals(List.of(List.of("")), CsvParser.parse("\"\""));
 }
}
