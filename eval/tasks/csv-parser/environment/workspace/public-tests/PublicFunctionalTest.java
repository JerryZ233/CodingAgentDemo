import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

public class PublicFunctionalTest {
 @Test void quotedComma() {assertEquals(List.of(List.of("a,b", "c")), CsvParser.parse("\"a,b\",c"));}
 @Test void quotedNewline() {assertEquals(List.of(List.of("a\nb", "c")), CsvParser.parse("\"a\nb\",c"));}
}
