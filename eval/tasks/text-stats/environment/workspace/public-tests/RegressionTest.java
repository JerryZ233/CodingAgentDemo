import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

public class RegressionTest {
 @Test void lines() { assertEquals(0,TextStats.lines(""));assertEquals(2,TextStats.lines("a\nb"));assertEquals(2,TextStats.lines("a\n")); }
 @Test void chars() { assertEquals(3,TextStats.characters("abc"));assertEquals(0,TextStats.characters("")); }
}
