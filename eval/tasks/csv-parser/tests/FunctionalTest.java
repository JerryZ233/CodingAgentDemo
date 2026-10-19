import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

public class FunctionalTest {
 @Test void comma() { assertEquals(List.of(List.of("a,b","c")), CsvParser.parse("\"a,b\",c")); }
 @Test void escaped() { assertEquals(List.of(List.of("say \"hi\"","x")),CsvParser.parse("\"say \"\"hi\"\"\",x")); }
 @Test void multiline() { assertEquals(List.of(List.of("a\nb","c")),CsvParser.parse("\"a\nb\",c")); }
}
