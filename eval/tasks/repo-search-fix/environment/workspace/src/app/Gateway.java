package app;
import app.routing.*;
import java.util.List;
public final class Gateway {
    public static String dispatch(List<Route> routes, String path) { return Router.resolve(routes, path); }
    public static String health() { return "ready"; }
}
