package router;

import java.util.ArrayList;
import java.util.List;

public class Router {
    private final List<Route> routes;
    private Route defaultRoute;

    public Router() {
        this.routes = new ArrayList<>();
    }

    public void addRoute(Route route) {
        routes.add(route);
    }

    public void setDefaultRoute(Route route) {
        this.defaultRoute = route;
    }

    public Route resolve(String path) {
        for (Route route : routes) {
            if (route.matches(path)) {
                return route;
            }
        }
        return defaultRoute;
    }
}
