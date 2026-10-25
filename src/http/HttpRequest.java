package http;

import java.util.HashMap;
import java.util.Map;
import session.Cookie;
import session.Session;

public class HttpRequest {
    private String method;
    private String path;
    private String version;
    private Map<String, String> headers;
    private String body;
    private Map<String, String> cookies;
    private Session session;
    private int contentLength;

    public HttpRequest() {
        this.headers = new HashMap<>();
        this.cookies = new HashMap<>();
        this.contentLength = -1;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getHeader(String name) {
        if (name == null || headers == null) {
            return null;
        }
        String direct = headers.get(name);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public Map<String, String> getCookies() {
        return cookies;
    }

    public void setCookies(Map<String, String> cookies) {
        this.cookies = cookies;
    }

    public String getCookie(String name) {
        return cookies.get(name);
    }

    public void parseCookies() {
        String cookieHeader = headers.get("Cookie");
        if (cookieHeader != null) {
            this.cookies = Cookie.parseCookieHeader(cookieHeader);
        }
    }

    public Session getSession() {
        return session;
    }

    public void setSession(Session session) {
        this.session = session;
    }

    public int getContentLength() {
        return contentLength;
    }

    public void setContentLength(int contentLength) {
        this.contentLength = contentLength;
    }
}
