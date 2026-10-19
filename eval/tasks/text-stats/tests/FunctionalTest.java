import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

public class FunctionalTest {
 @Test void countsWords() { assertEquals(Map.of("hello",2L,"world",1L), TextStats.frequencies("Hello, HELLO world!")); }
 @Test void empty() { assertTrue(TextStats.frequencies(" ... ").isEmpty()); }
 @Test void numbers() { assertEquals(Map.of("abc12",2L,"42",1L),TextStats.frequencies("AbC12 abc12 42")); }
 @Test void locale() { Locale before=Locale.getDefault();try {Locale.setDefault(Locale.forLanguageTag("tr"));assertEquals(Map.of("i",2L),TextStats.frequencies("I i"));}finally{Locale.setDefault(before);} }
}
