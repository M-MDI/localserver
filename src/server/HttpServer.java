package server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Set;

public class HttpServer {
    private final int[] ports;
    private Selector selector;
    private volatile boolean running;
    private static final long CONNECTION_TIMEOUT_MS = 30000;

    public HttpServer(int[] ports) {
        this.ports = ports;
    }

    public void start() throws IOException {
        selector = Selector.open();
        running = true;

        for (int port : ports) {
            ServerSocketChannel serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.bind(new InetSocketAddress(port));
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
            System.out.println("Server listening on port " + port);
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
        try {
            ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
            SocketChannel clientChannel = serverChannel.accept();

            if (clientChannel != null) {
                clientChannel.configureBlocking(false);
                Connection connection = new Connection(clientChannel);
                clientChannel.register(selector, SelectionKey.OP_READ, connection);
            }
        } catch (IOException e) {
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
        } catch (IOException e) {
            key.cancel();
            e.printStackTrace();
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
            key.cancel();
            e.printStackTrace();
        }
    }

    private void checkTimeouts() {
        long now = System.currentTimeMillis();
        for (SelectionKey key : selector.keys()) {
            if (key.isValid() && key.attachment() instanceof Connection) {
                Connection connection = (Connection) key.attachment();
                if (now - connection.getLastActiveTime() > CONNECTION_TIMEOUT_MS) {
                    try {
                        connection.close();
                        key.cancel();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    public void stop() {
        running = false;
        if (selector != null) {
            selector.wakeup();
        }
    }
}
