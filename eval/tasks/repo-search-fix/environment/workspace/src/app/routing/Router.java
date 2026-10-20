package app.routing;
import java.util.List;

public final class Router {
    public static String resolve(List<Route> routes, String path) {
        for (Route route : routes) {
            String pattern = route.pattern();
            if (pattern.equals("*") || pattern.equals(path)
                    || (pattern.endsWith("/*") && path.startsWith(pattern.substring(0, pattern.length() - 2)))) {
                return route.handler();
            }
        }
        return null;
    }
}
