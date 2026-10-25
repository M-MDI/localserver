package server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class HttpServer {
    private final config.ServerConfigurations configurations;
    private Selector selector;
    private volatile boolean running;
    private static final long CONNECTION_TIMEOUT_MS = 30000;

    public HttpServer(config.ServerConfigurations configurations) {
        this.configurations = configurations;
    }

    // Backward compatibility constructor for single-server mode
    public HttpServer(int[] ports, String documentRoot, config.ServerConfig config) {
        this.configurations = new config.ServerConfigurations();
        this.configurations.addServerConfig(config);
        for (int port : ports) {
            this.configurations.setDefaultServerForPort(port, config);
        }
    }

    public void start() throws IOException {
        selector = Selector.open();
        running = true;

        List<Integer> allPorts = configurations.getAllPorts();
        int boundPorts = 0;
        List<String> failedPorts = new java.util.ArrayList<>();
        for (Integer port : allPorts) {
            ServerSocketChannel serverChannel = null;
            try {
                serverChannel = ServerSocketChannel.open();
                serverChannel.configureBlocking(false);
                serverChannel.bind(new InetSocketAddress(port));
                serverChannel.register(selector, SelectionKey.OP_ACCEPT, port);
                boundPorts++;
                System.out.println("Server listening on port " + port);
            } catch (IOException e) {
                failedPorts.add(port + ": " + e.getMessage());
                System.err.println("Unable to bind port " + port + "; continuing with other ports: " + e.getMessage());
                if (serverChannel != null) {
                    try {
                        serverChannel.close();
                    } catch (IOException ignored) {}
                }
            }
        }
        if (boundPorts == 0) {
            running = false;
            selector.close();
            throw new IOException("Unable to bind any configured port (" + String.join("; ", failedPorts) + ")");
        }

        System.out.println("LocalServer started with NIO selector");

        while (running) {
            try {
                // Use a timeout so we can periodically check for idle connections
                selector.select(1000);
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> iter = selectedKeys.iterator();

                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove();

                    if (!key.isValid()) {
                        continue;
                    }

                    if (key.isAcceptable()) {
                        handleAccept(key);
                    } else if (key.isReadable()) {
                        handleRead(key);
                    } else if (key.isWritable()) {
                        handleWrite(key);
                    }
                }
                
                checkTimeouts();
            } catch (IOException e) {
                if (running) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void handleAccept(SelectionKey key) {
        SocketChannel clientChannel = null;
        Connection connection = null;
        try {
            ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
            clientChannel = serverChannel.accept();

            if (clientChannel != null) {
                clientChannel.configureBlocking(false);
                Integer port = (Integer) key.attachment();
                connection = new Connection(clientChannel, configurations, port);
                clientChannel.register(selector, SelectionKey.OP_READ, connection);
            }
        } catch (IOException e) {
            if (connection != null) {
                closeConnection(connection, key);
            } else if (clientChannel != null) {
                try {
                    clientChannel.close();
                } catch (IOException ignored) {}
            }
            e.printStackTrace();
        }
    }

    private void handleRead(SelectionKey key) {
        try {
            Connection connection = (Connection) key.attachment();
            if (connection == null) {
                key.cancel();
                return;
            }

            boolean readComplete = connection.read();

            if (readComplete) {
                connection.prepareResponse();
                key.interestOps(SelectionKey.OP_WRITE);
            }
            // If not complete, keep OP_READ interest to continue reading
        } catch (IOException e) {
            Connection connection = (Connection) key.attachment();
            if (connection != null && !isSocketFailure(e)) {
                connection.prepareErrorResponse(400, "Bad Request");
                key.interestOps(SelectionKey.OP_WRITE);
            } else {
                closeConnection(connection, key);
            }
        } catch (http.PayloadTooLargeException e) {
            Connection connection = (Connection) key.attachment();
            if (connection != null) {
                connection.prepareErrorResponse(413, "Payload Too Large");
                key.interestOps(SelectionKey.OP_WRITE);
            } else {
                key.cancel();
            }
        } catch (Exception e) {
            Connection connection = (Connection) key.attachment();
            if (connection != null) {
                connection.prepareErrorResponse(400, "Bad Request");
                key.interestOps(SelectionKey.OP_WRITE);
            } else {
                key.cancel();
            }
        }
    }

    private void handleWrite(SelectionKey key) {
        try {
            Connection connection = (Connection) key.attachment();
            if (connection == null) {
                key.cancel();
                return;
            }

            boolean writeComplete = connection.write();

            if (writeComplete) {
                connection.close();
                key.cancel();
            }
        } catch (IOException e) {
            closeConnection((Connection) key.attachment(), key);
            e.printStackTrace();
        }
    }

    private boolean isSocketFailure(IOException error) {
        return error instanceof SocketException ||
                error instanceof ClosedChannelException ||
                error instanceof AsynchronousCloseException;
    }

    private void closeConnection(Connection connection, SelectionKey key) {
        if (connection != null) {
            try {
                connection.close();
            } catch (IOException ignored) {}
        } else if (key != null) {
            try {
                key.channel().close();
            } catch (IOException ignored) {}
        }
        if (key != null) {
            key.cancel();
        }
    }

    private void checkTimeouts() {
        long now = System.currentTimeMillis();
        for (SelectionKey key : selector.keys()) {
            if (key.isValid() && key.attachment() instanceof Connection) {
                Connection connection = (Connection) key.attachment();
                if (now - connection.getLastActiveTime() > CONNECTION_TIMEOUT_MS) {
                    closeConnection(connection, key);
                }
            }
        }
    }

    public void stop() {
        running = false;
        if (selector != null) {
            selector.wakeup();
            try {
                for (SelectionKey key : selector.keys()) {
                    if (key.attachment() instanceof Connection) {
                        closeConnection((Connection) key.attachment(), key);
                    } else {
                        try {
                            key.channel().close();
                        } catch (IOException ignored) {}
                    }
                }
                selector.close();
            } catch (IOException ignored) {}
        }
    }
}
