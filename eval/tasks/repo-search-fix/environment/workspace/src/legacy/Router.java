package legacy;
import java.util.Map;
public final class Router {
    public static String resolve(Map<String, String> routes, String path) { return routes.get(path); }
}
