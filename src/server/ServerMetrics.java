package server;

import java.util.concurrent.atomic.AtomicLong;

/** Lightweight process-local counters for the /__metrics endpoint. */
public final class ServerMetrics {
    private static final AtomicLong ACTIVE_CONNECTIONS = new AtomicLong();
    private static final AtomicLong TOTAL_CONNECTIONS = new AtomicLong();
    private static final AtomicLong TOTAL_REQUESTS = new AtomicLong();
    private static final long START_TIME_MILLIS = System.currentTimeMillis();

    private ServerMetrics() {}

    static void connectionOpened() {
        ACTIVE_CONNECTIONS.incrementAndGet();
        TOTAL_CONNECTIONS.incrementAndGet();
    }

    static void connectionClosed() {
        ACTIVE_CONNECTIONS.updateAndGet(current -> Math.max(0, current - 1));
    }

    static void requestReceived() {
        TOTAL_REQUESTS.incrementAndGet();
    }

    public static String renderPrometheus() {
        long uptimeSeconds = Math.max(0, (System.currentTimeMillis() - START_TIME_MILLIS) / 1000);
        return "# HELP localserver_active_connections Currently open client connections.\n" +
                "# TYPE localserver_active_connections gauge\n" +
                "localserver_active_connections " + ACTIVE_CONNECTIONS.get() + "\n" +
                "# HELP localserver_connections_total Accepted client connections.\n" +
                "# TYPE localserver_connections_total counter\n" +
                "localserver_connections_total " + TOTAL_CONNECTIONS.get() + "\n" +
                "# HELP localserver_requests_total Parsed HTTP requests.\n" +
                "# TYPE localserver_requests_total counter\n" +
                "localserver_requests_total " + TOTAL_REQUESTS.get() + "\n" +
                "# HELP localserver_sessions_active Active server sessions.\n" +
                "# TYPE localserver_sessions_active gauge\n" +
                "localserver_sessions_active " + Connection.getSessionManager().getActiveSessionCount() + "\n" +
                "# HELP localserver_uptime_seconds Server process uptime in seconds.\n" +
                "# TYPE localserver_uptime_seconds counter\n" +
                "localserver_uptime_seconds " + uptimeSeconds + "\n";
    }
}
