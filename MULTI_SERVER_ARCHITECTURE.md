# Current Architecture Documentation

## Single-Server Configuration (Current)

### Configuration Flow:
1. **ConfigLoader.load()** reads `config/server.conf`
2. Parses flat key=value pairs into a single ServerConfig object
3. ServerConfig contains:
   - ports (List<Integer>)
   - documentRoot (String)
   - maxBodySize (int)
   - errorPages (Map<Integer, String>)
   - directoryListing (boolean)
4. No server names, no host-based routing, no default server selection

### HttpServer:
- Constructor: `HttpServer(int[] ports, String documentRoot, ServerConfig config)`
- Creates one ServerSocketChannel per port
- Each connection gets the same documentRoot and config
- No server selection logic
- No port sharing

### Connection:
- Constructor: `Connection(SocketChannel channel, String documentRoot, ServerConfig config)`
- Receives documentRoot and ServerConfig at construction
- No Host header parsing for server selection
- Uses same config for all requests

### Current Config Format:
```
port=65432
document_root=./www
max_body_size=10m
directory_listing=on
error_page 400=./errors/400.html
```

## Proposed Multi-Server Architecture

### Changes Required:

1. **ServerConfig** - Add server name and host names
2. **ServerConfigurations** - New class to hold multiple ServerConfig instances
3. **ConfigLoader** - Parse server blocks with { } syntax, maintain backward compatibility
4. **HttpServer** - Track which servers listen on which ports, allow shared ports
5. **Connection** - Parse Host header, select appropriate server based on host name
6. **Port mapping** - Map port -> list of ServerConfig instances (for shared ports)

### New Configuration Format:

```
# Single-server mode (backward compatible)
port=65432
document_root=./www
max_body_size=10m
directory_listing=on
error_page 400=./errors/400.html

# Multi-server mode (new)
server {
    name server1
    listen 65432
    server_name localhost example.com
    document_root ./www
    max_body_size 10m
    directory_listing on
    default_server
    error_page 400=./errors/400.html
}

server {
    name server2
    listen 65432
    server_name api.example.com
    document_root ./www-api
    max_body_size 5m
    directory_listing off
    error_page 404=./errors/404.html
}
```

### Server Selection Logic:

1. For each port, collect all ServerConfig instances listening on that port
2. When request arrives:
   - Parse Host header (remove port if present)
   - Match against server_name of servers on that port
   - If match found, use that server's config
   - If no match, use default_server for that port
   - If no default_server, use first server as fallback (deterministic)

### Default Server Selection:

- Explicit: marked with `default_server` directive
- Fallback: first server listening on that port (deterministic)
- Validation: Only one default_server per port, error if multiple

### Port Sharing:

- Multiple servers can listen on same port
- HttpServer creates only one ServerSocketChannel per unique port
- Each socket channel has a list of ServerConfig instances
- Connection selects appropriate config based on Host header
