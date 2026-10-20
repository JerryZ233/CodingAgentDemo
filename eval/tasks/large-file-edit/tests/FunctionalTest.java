import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
public class FunctionalTest {
 @Test void shippingThresholds() {
  for (long value : new long[]{0, 1, 9999, 10000, 10001, 1000000000L})
   assertEquals(value >= 10000 ? 0 : 500, PricingRules.shippingFee(value));
 }
 @Test void floorDiscountInCents() {
  for (long value : new long[]{1, 99, 101, 10001, 1000000000L})
   for (int percent : new int[]{1, 7, 33, 99})
    assertEquals(value * (100 - percent) / 100, PricingRules.discountedTotal(value, percent));
 }
}
