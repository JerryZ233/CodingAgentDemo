import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import app.Gateway;
import app.routing.Route;
public class FunctionalTest {
 @Test void exactWinsRegardlessOfOrder() {
  var routes = List.of(new Route("*", "default"), new Route("/api/*", "prefix"), new Route("/api/users", "exact"));
  assertEquals("exact", Gateway.dispatch(routes, "/api/users"));
 }
 @Test void longestPrefixAndSegmentBoundary() {
  var routes = List.of(new Route("/api/*", "api"), new Route("/api/v1/*", "v1"), new Route("*", "default"));
  assertEquals("v1", Gateway.dispatch(routes, "/api/v1/users"));
  assertEquals("v1", Gateway.dispatch(routes, "/api/v1"));
  assertEquals("api", Gateway.dispatch(routes, "/api/v10/users"));
  assertEquals("default", Gateway.dispatch(routes, "/apiculture"));
 }
 @Test void wildcardCanBeFirstAndRootPrefixWorks() {
  var routes = List.of(new Route("*", "default"), new Route("/*", "root"), new Route("/中文/*", "中文"));
  assertEquals("root", Gateway.dispatch(routes, "/elsewhere"));
  assertEquals("中文", Gateway.dispatch(routes, "/中文/😀"));
 }
}
