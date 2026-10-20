package app.routing;
import java.util.List;

public final class Router {
    public static String resolve(List<Route> routes, String path) {
        String fallback = null, prefixHandler = null;
        int longest = -1;
        for (Route route : routes) {
            String pattern = route.pattern();
            if (pattern.equals(path)) return route.handler();
            if (pattern.equals("*")) fallback = route.handler();
            else if (pattern.endsWith("/*")) {
                String base = pattern.substring(0, pattern.length() - 2);
                if ((path.equals(base) || path.startsWith(base + "/")) && base.length() > longest) {
                    longest = base.length();
                    prefixHandler = route.handler();
                }
            }
        }
        return longest >= 0 ? prefixHandler : fallback;
    }
}
