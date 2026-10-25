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

    public List<Route> getRoutes() {
        return routes;
    }

    public void setDefaultRoute(Route route) {
        this.defaultRoute = route;
    }

    public Route getDefaultRoute() {
        return defaultRoute;
    }

    public Route resolve(String path) {
        Route bestMatch = null;
        int maxLen = -1;

        for (Route route : routes) {
            if (route.matches(path)) {
                int len = route.getPath().length();
                if (len > maxLen || (len == maxLen && route.isRedirect() && (bestMatch == null || !bestMatch.isRedirect()))) {
                    maxLen = len;
                    bestMatch = route;
                }
            }
        }
        
        if (bestMatch != null) {
            return bestMatch;
        }
        
        return defaultRoute;
    }
}
