import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;
public class RegressionTest {
 @Test void existingPricingApis() {
  assertEquals("CNY", PricingRules.currency());
  assertEquals(0, PricingRules.shippingFee(20000));
  assertEquals(12345, PricingRules.discountedTotal(12345, 0));
  assertEquals(0, PricingRules.discountedTotal(12345, 100));
 }
 @Test void bytesOutsideEditRegionsUnchanged() throws Exception {
  String text = Files.readString(Path.of(System.getProperty("eval.source.dir"), "PricingRules.java"), StandardCharsets.UTF_8);
  for (String name : List.of("shippingFee", "discountedTotal")) {
   String begin = "    // BEGIN EDIT " + name + "\n";
   String end = "    // END EDIT " + name + "\n";
   assertEquals(1, text.split(Pattern.quote(begin), -1).length - 1);
   assertEquals(1, text.split(Pattern.quote(end), -1).length - 1);
   int from = text.indexOf(begin) + begin.length(), to = text.indexOf(end);
   assertTrue(to >= from); text = text.substring(0, from) + text.substring(to);
  }
  String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
  assertEquals("35a61dfe2a7892b3d782a3c739335fdd7e07ffe4e5b6d79145f98172379a5e5f", digest);
 }
}
