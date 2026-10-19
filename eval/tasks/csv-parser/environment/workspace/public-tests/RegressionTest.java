import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

public class RegressionTest {
 @Test void simple() {assertEquals(List.of(List.of("a","b"),List.of("c","d")),CsvParser.parse("a,b\nc,d"));}
 @Test void emptyFields() {assertEquals(List.of(List.of("","b","")),CsvParser.parse(",b,"));}
 @Test void empty() {assertTrue(CsvParser.parse("").isEmpty());}
 @Test void crlf() {assertEquals(List.of(List.of("a","b")),CsvParser.parse("a,b\r\n"));}
}
