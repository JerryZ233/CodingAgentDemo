import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import app.Gateway;
import app.routing.Route;
public class RegressionTest {
 @Test void originalCases() {
  assertEquals("ok", Gateway.dispatch(List.of(new Route("/ok", "ok")), "/ok"));
  assertNull(Gateway.dispatch(List.of(), "/missing"));
  assertNull(Gateway.dispatch(List.of(new Route("/Case", "yes")), "/case"));
  assertEquals("default", Gateway.dispatch(List.of(new Route("*", "default")), "/missing"));
 }
 @Test void unrelatedApisPreserved() {
  assertEquals("ready", Gateway.health());
  assertEquals("old", legacy.Router.resolve(Map.of("/old", "old"), "/old"));
  assertNull(legacy.Router.resolve(Map.of("*", "old"), "/other"));
 }
}
