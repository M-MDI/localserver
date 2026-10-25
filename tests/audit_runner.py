import sys
import os
import socket
import time
import subprocess
import shutil
import urllib.request
import urllib.error
import threading
from pathlib import Path
import psutil

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(line_buffering=True)

WORKSPACE = Path(r"C:\Users\exe\Desktop\localserver")
BIN_DIR = WORKSPACE / "bin"
WWW_DIR = WORKSPACE / "www"
CONFIG_DIR = WORKSPACE / "config"
ERRORS_DIR = WORKSPACE / "errors"

TEST_PORT_BASE = 9100

results = {}

def record(phase, name, passed, detail=""):
    if phase not in results:
        results[phase] = []
    results[phase].append((name, passed, detail))
    status = "PASS" if passed else "FAIL"
    print(f"[{status}] {phase} -> {name}: {detail}")

def run_java_snippet(code):
    """Run a small java snippet to test ConfigLoader or Session classes directly"""
    tmp_java = WORKSPACE / "TestSnippet.java"
    class_name = "TestSnippet"
    full_code = f"""
import config.*;
import router.*;
import session.*;
import http.*;
import java.io.*;
import java.util.*;

public class {class_name} {{
    public static void main(String[] args) throws Exception {{
        {code}
    }}
}}
"""
    tmp_java.write_text(full_code, encoding="utf-8")
    try:
        compile_res = subprocess.run(["javac", "-cp", str(BIN_DIR), str(tmp_java)], cwd=WORKSPACE, capture_output=True, text=True)
        if compile_res.returncode != 0:
            return False, f"Compile error: {compile_res.stderr}"
        run_res = subprocess.run(["java", "-cp", f"{BIN_DIR};{WORKSPACE}", class_name], cwd=WORKSPACE, capture_output=True, text=True)
        return (run_res.returncode == 0), run_res.stdout + run_res.stderr
    finally:
        if tmp_java.exists():
            tmp_java.unlink()
        tmp_class = WORKSPACE / f"{class_name}.class"
        if tmp_class.exists():
            tmp_class.unlink()

def start_server_with_config(config_text, port=TEST_PORT_BASE):
    cfg_file = WORKSPACE / f"test_run_{port}.conf"
    cfg_file.write_text(config_text, encoding="utf-8")
    proc = subprocess.Popen(
        ["java", "-cp", "bin", "Main", str(cfg_file)],
        cwd=WORKSPACE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True
    )
    time.sleep(1.5)
    return proc, cfg_file

def stop_server(proc, cfg_file):
    try:
        proc.terminate()
        proc.wait(timeout=3)
    except Exception:
        try:
            proc.kill()
        except Exception:
            pass
    if cfg_file and cfg_file.exists():
        cfg_file.unlink()

def send_raw(port, request_bytes, timeout=5):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        s.connect(("127.0.0.1", port))
        s.sendall(request_bytes)
        response = b""
        while True:
            try:
                chunk = s.recv(4096)
                if not chunk:
                    break
                response += chunk
            except socket.timeout:
                break
        return response.decode("utf-8", errors="replace")
    except Exception as e:
        return f"ERROR: {e}"
    finally:
        s.close()

# ==========================================
# PHASE 2: CONFIGURATION AUDIT
# ==========================================
def test_phase_2():
    phase = "Phase 2 - Configuration"
    print(f"\n--- Testing {phase} ---")

    # 1. Single-server load
    ok, out = run_java_snippet("""
        ServerConfigurations sc = ConfigLoader.loadMultiServer("config/server.conf");
        if (sc.getAllConfigs().size() == 1 && sc.getAllPorts().contains(65432)) {
            System.out.println("OK");
        } else {
            throw new RuntimeException("Unexpected single-server config: " + sc.getAllConfigs().size());
        }
    """)
    record(phase, "Single-server config load", ok, out.strip())

    # 2. Multi-server blocks load with multiple ports
    multi_conf = WORKSPACE / "test_multi_audit.conf"
    multi_conf.write_text("""
server {
    listen 9201 9202
    server_name alpha.local
    document_root ./www
    max_body_size 2m
    directory_listing on
}
server {
    listen 9203
    server_name beta.local
    document_root ./www/docs
    max_body_size 500k
    directory_listing off
}
""", encoding="utf-8")
    ok, out = run_java_snippet(f"""
        ServerConfigurations sc = ConfigLoader.loadMultiServer("{multi_conf.as_posix()}");
        if (sc.getAllConfigs().size() == 2 && sc.getAllPorts().size() == 3) {{
            ServerConfig c1 = sc.selectServerConfig(9201, "alpha.local");
            ServerConfig c2 = sc.selectServerConfig(9203, "beta.local");
            if (c1 != null && c2 != null && c1.isDirectoryListing() && !c2.isDirectoryListing()) {{
                System.out.println("OK");
                return;
            }}
        }}
        throw new RuntimeException("Failed multi-server config: " + sc.getAllConfigs().size());
    """)
    if multi_conf.exists(): multi_conf.unlink()
    record(phase, "Multi-server blocks with multiple ports & directives", ok, out.strip())

    # 3. Shared port between virtual servers + Host header matching (case insensitive & with port)
    shared_conf = WORKSPACE / "test_shared_audit.conf"
    shared_conf.write_text("""
server {
    listen 9205
    server_name site1.com
    document_root ./www
}
server {
    listen 9205
    server_name site2.com
    document_root ./www/docs
}
""", encoding="utf-8")
    ok, out = run_java_snippet(f"""
        ServerConfigurations sc = ConfigLoader.loadMultiServer("{shared_conf.as_posix()}");
        ServerConfig s1 = sc.selectServerConfig(9205, "site1.com");
        ServerConfig s1_port = sc.selectServerConfig(9205, "SITE1.COM:9205");
        ServerConfig s2 = sc.selectServerConfig(9205, "Site2.com:9205");
        ServerConfig s_def = sc.selectServerConfig(9205, "unknown.com");
        if (s1 != null && s1.getServerName().equals("site1.com") &&
            s1_port != null && s1_port.getServerName().equals("site1.com") &&
            s2 != null && s2.getServerName().equals("site2.com") &&
            s_def != null && s_def.getServerName().equals("site1.com")) {{
            System.out.println("OK");
        }} else {{
            throw new RuntimeException("Host selection failed");
        }}
    """)
    if shared_conf.exists(): shared_conf.unlink()
    record(phase, "Shared port & case-insensitive Host matching with port", ok, out.strip())

    # 4. Duplicate server_name detection on same port
    dup_conf = WORKSPACE / "test_dup_audit.conf"
    dup_conf.write_text("""
server {
    listen 9206
    server_name dup.local
    document_root ./www
}
server {
    listen 9206
    server_name dup.local
    document_root ./www
}
""", encoding="utf-8")
    ok, out = run_java_snippet(f"""
        try {{
            ConfigLoader.loadMultiServer("{dup_conf.as_posix()}");
            throw new RuntimeException("Should have thrown IOException");
        }} catch (IOException e) {{
            System.out.println("Caught expected: " + e.getMessage());
        }}
    """)
    if dup_conf.exists(): dup_conf.unlink()
    record(phase, "Duplicate server_name detection", ok, out.strip())

    # 5. Duplicate default_server detection on same port
    dup_def_conf = WORKSPACE / "test_dup_def_audit.conf"
    dup_def_conf.write_text("""
server {
    listen 9207
    server_name s1.local
    default_server
    document_root ./www
}
server {
    listen 9207
    server_name s2.local
    default_server
    document_root ./www
}
""", encoding="utf-8")
    ok, out = run_java_snippet(f"""
        try {{
            ConfigLoader.loadMultiServer("{dup_def_conf.as_posix()}");
            throw new RuntimeException("Should have thrown IOException");
        }} catch (IOException e) {{
            System.out.println("Caught expected: " + e.getMessage());
        }}
    """)
    if dup_def_conf.exists(): dup_def_conf.unlink()
    record(phase, "Duplicate default_server detection", ok, out.strip())

    # 6. Invalid directive outside server block
    bad_dir_conf = WORKSPACE / "test_bad_dir.conf"
    bad_dir_conf.write_text("""
listen 9208
server {
    listen 9208
    document_root ./www
}
""", encoding="utf-8")
    ok, out = run_java_snippet(f"""
        try {{
            ConfigLoader.loadMultiServer("{bad_dir_conf.as_posix()}");
            throw new RuntimeException("Should have rejected directive outside block");
        }} catch (IOException e) {{
            System.out.println("Caught expected: " + e.getMessage());
        }}
    """)
    if bad_dir_conf.exists(): bad_dir_conf.unlink()
    record(phase, "Directive outside server block rejected", ok, out.strip())

    # 7. Unclosed server block
    unclosed_conf = WORKSPACE / "test_unclosed.conf"
    unclosed_conf.write_text("""
server {
    listen 9209
    document_root ./www
""", encoding="utf-8")
    ok, out = run_java_snippet(f"""
        try {{
            ConfigLoader.loadMultiServer("{unclosed_conf.as_posix()}");
            throw new RuntimeException("Should have rejected unclosed block");
        }} catch (IOException e) {{
            System.out.println("Caught expected: " + e.getMessage());
        }}
    """)
    if unclosed_conf.exists(): unclosed_conf.unlink()
    record(phase, "Unclosed server block rejected", ok, out.strip())

    # 8. Route and Redirect configuration validation
    route_redir_conf = WORKSPACE / "test_route_redir.conf"
    route_redir_conf.write_text("""
server {
    listen 9210
    document_root ./www
    route /api static ./www/docs
    route /up upload ./www/uploads POST
    redirect /legacy /new 301
}
""", encoding="utf-8")
    ok, out = run_java_snippet(f"""
        ServerConfigurations sc = ConfigLoader.loadMultiServer("{route_redir_conf.as_posix()}");
        ServerConfig c = sc.getAllConfigs().get(0);
        if (c.hasRoutes() && c.hasRedirects() && c.getRoutes().size() == 2 && c.getRedirects().size() == 1) {{
            System.out.println("OK");
        }} else {{
            throw new RuntimeException("Failed route/redirect parsing");
        }}
    """)
    if route_redir_conf.exists(): route_redir_conf.unlink()
    record(phase, "Route and Redirect parsing", ok, out.strip())

    # 9. Header injection in redirect target
    inj_conf = WORKSPACE / "test_inj.conf"
    inj_conf.write_text("""
server {
    listen 9211
    document_root ./www
    redirect /old /new%0d%0aInjected:1 302
}
""", encoding="utf-8")
    ok, out = run_java_snippet(f"""
        try {{
            ConfigLoader.loadMultiServer("{inj_conf.as_posix()}");
            throw new RuntimeException("Should have rejected header injection in redirect");
        }} catch (IOException e) {{
            System.out.println("Caught expected: " + e.getMessage());
        }}
    """)
    if inj_conf.exists(): inj_conf.unlink()
    record(phase, "Header injection in redirect target rejected", ok, out.strip())

# ==========================================
# PHASE 3: HTTP PROTOCOL AUDIT
# ==========================================
def test_phase_3(port=9300):
    phase = "Phase 3 - HTTP Protocol"
    print(f"\n--- Testing {phase} ---")

    cfg = f"""
server {{
    listen {port}
    server_name test.local
    document_root ./www
    max_body_size 50k
    error_page 400=./errors/400.html
    error_page 404=./errors/404.html
    error_page 405=./errors/405.html
    error_page 413=./errors/413.html
    error_page 500=./errors/500.html
}}
"""
    proc, cfg_file = start_server_with_config(cfg, port)
    try:
        # 1. Valid GET
        res = send_raw(port, b"GET /hello.txt HTTP/1.1\r\nHost: test.local\r\n\r\n")
        record(phase, "Standard GET request", "HTTP/1.1 200 OK" in res and "Hello, World!" in res, res.split("\r\n")[0])

        # 2. Case-insensitive header
        res = send_raw(port, b"GET /hello.txt HTTP/1.1\r\nhOsT: test.local\r\nCoNnEcTiOn: close\r\n\r\n")
        record(phase, "Case-insensitive headers", "HTTP/1.1 200 OK" in res, res.split("\r\n")[0])

        # 3. Connection header in response
        res = send_raw(port, b"GET /hello.txt HTTP/1.1\r\nHost: test.local\r\n\r\n")
        record(phase, "Response Connection header", "Connection: close" in res, "Connection: close present")

        # 4. Response Content-Length and Content-Type
        record(phase, "Response Content-Length & Content-Type", "Content-Length:" in res and "Content-Type: text/plain" in res, "Valid framing headers present")

        # 5. Unsupported method PUT
        res = send_raw(port, b"PUT /hello.txt HTTP/1.1\r\nHost: test.local\r\n\r\n")
        record(phase, "Unsupported method PUT (405 + Allow)", "HTTP/1.1 405" in res and "Allow:" in res, res.split("\r\n")[0])

        # 6. Unsupported method TRACE
        res = send_raw(port, b"TRACE /hello.txt HTTP/1.1\r\nHost: test.local\r\n\r\n")
        record(phase, "Unsupported method TRACE (405 + Allow)", "HTTP/1.1 405" in res and "Allow:" in res, res.split("\r\n")[0])

        # 7. Malformed request line
        res = send_raw(port, b"GARBAGE_REQUEST_WITHOUT_SPACES\r\n\r\n")
        record(phase, "Malformed request line (400)", "HTTP/1.1 400" in res, res.split("\r\n")[0] if res else "No response")

        # 8. Malformed Content-Length: negative
        res = send_raw(port, b"POST /upload/neg.txt HTTP/1.1\r\nHost: test.local\r\nContent-Length: -5\r\n\r\nhello")
        record(phase, "Negative Content-Length (400)", "HTTP/1.1 400" in res, res.split("\r\n")[0] if res else "No response")

        # 9. Malformed Content-Length: non-numeric
        res = send_raw(port, b"POST /upload/nan.txt HTTP/1.1\r\nHost: test.local\r\nContent-Length: abc\r\n\r\nhello")
        record(phase, "Non-numeric Content-Length (400)", "HTTP/1.1 400" in res, res.split("\r\n")[0] if res else "No response")

        # 10. Conflicting framing headers: Content-Length AND Transfer-Encoding
        res = send_raw(port, b"POST /upload/conflict.txt HTTP/1.1\r\nHost: test.local\r\nContent-Length: 5\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n")
        # Either 400 or chunked handling
        status_line = res.split("\r\n")[0] if res else ""
        record(phase, "Conflicting framing headers handled safely", ("HTTP/1.1 400" in res or "HTTP/1.1 201" in res), status_line)
        conf_target = WWW_DIR / "uploads" / "conflict.txt"
        if conf_target.exists(): conf_target.unlink()

        # 11. Chunked body upload
        chunked_req = b"POST /upload/chunk_p3.txt HTTP/1.1\r\nHost: test.local\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nHello\r\n6\r\n World\r\n0\r\n\r\n"
        res = send_raw(port, chunked_req)
        chunk_file = WWW_DIR / "uploads" / "chunk_p3.txt"
        content_matches = chunk_file.exists() and chunk_file.read_text(encoding="utf-8") == "Hello World"
        if chunk_file.exists(): chunk_file.unlink()
        record(phase, "Chunked transfer encoding upload", "HTTP/1.1 201" in res and content_matches, res.split("\r\n")[0])

        # 12. Invalid chunk size (non-hex)
        bad_chunk = b"POST /upload/bad_chunk.txt HTTP/1.1\r\nHost: test.local\r\nTransfer-Encoding: chunked\r\n\r\nZZ\r\nHello\r\n0\r\n\r\n"
        res = send_raw(port, bad_chunk)
        record(phase, "Invalid chunk size rejected (400)", "HTTP/1.1 400" in res, res.split("\r\n")[0])

        # 13. Oversized body (> 50k max_body_size)
        oversized = b"A" * 60000
        req = f"POST /upload/over.txt HTTP/1.1\r\nHost: test.local\r\nContent-Length: {len(oversized)}\r\n\r\n".encode("utf-8") + oversized
        res = send_raw(port, req)
        record(phase, "Oversized body rejected (413)", "HTTP/1.1 413" in res, res.split("\r\n")[0])

        # 14. Missing Host header in HTTP/1.1
        res = send_raw(port, b"GET /hello.txt HTTP/1.1\r\n\r\n")
        record(phase, "Missing Host header handled without crash", ("HTTP/1.1 200" in res or "HTTP/1.1 400" in res), res.split("\r\n")[0] if res else "Closed")

    finally:
        stop_server(proc, cfg_file)

# ==========================================
# PHASE 4: STATIC FILES AND ROUTING
# ==========================================
def test_phase_4(port=9310):
    phase = "Phase 4 - Static & Routing"
    print(f"\n--- Testing {phase} ---")

    test_api_dir = WWW_DIR / "test_api_p4"
    test_api_dir.mkdir(exist_ok=True)
    (test_api_dir / "data.json").write_text('{"api": "v1"}', encoding="utf-8")

    test_v2_dir = WWW_DIR / "test_v2_p4"
    test_v2_dir.mkdir(exist_ok=True)
    (test_v2_dir / "data.json").write_text('{"api": "v2"}', encoding="utf-8")

    cfg = f"""
server {{
    listen {port}
    server_name host1.local
    document_root ./www
    directory_listing off
    route / root
    route /api static ./www/test_api_p4
    route /api/v2 static ./www/test_v2_p4
    redirect /old-url /new-url 301
    redirect /temp-url /hello.txt 302
}}
server {{
    listen {port}
    server_name host2.local
    document_root ./www/docs
    directory_listing on
    route / root
    route /secret static ./www/test_v2_p4
}}
"""
    proc, cfg_file = start_server_with_config(cfg, port)
    try:
        # 1. Document root normal file
        res = send_raw(port, b"GET /hello.txt HTTP/1.1\r\nHost: host1.local\r\n\r\n")
        record(phase, "Normal file serving", "HTTP/1.1 200 OK" in res and "Hello, World!" in res, "200 OK")

        # 2. Index file resolution
        res = send_raw(port, b"GET / HTTP/1.1\r\nHost: host1.local\r\n\r\n")
        record(phase, "Directory index resolution", "HTTP/1.1 200 OK" in res and "<title>" in res, "200 OK")

        # 3. Directory listing disabled (host1 on a dir without index)
        no_index_dir = WWW_DIR / "no_index_p4"
        no_index_dir.mkdir(exist_ok=True)
        (no_index_dir / "sample.txt").write_text("sample", encoding="utf-8")
        res = send_raw(port, b"GET /no_index_p4/ HTTP/1.1\r\nHost: host1.local\r\n\r\n")
        record(phase, "Directory listing disabled -> 403", "HTTP/1.1 403" in res, res.split("\r\n")[0])

        # 4. Directory listing enabled (host2 on a dir without index)
        docs_no_index = WWW_DIR / "docs" / "sub_no_index"
        docs_no_index.mkdir(exist_ok=True)
        (docs_no_index / "info.txt").write_text("info", encoding="utf-8")
        res = send_raw(port, b"GET /sub_no_index/ HTTP/1.1\r\nHost: host2.local\r\n\r\n")
        record(phase, "Directory listing enabled -> 200 HTML", "HTTP/1.1 200 OK" in res and "Index of " in res, "200 OK listing")

        # 5. Route path boundary: /api matches /api/data.json
        res = send_raw(port, b"GET /api/data.json HTTP/1.1\r\nHost: host1.local\r\n\r\n")
        record(phase, "Route /api matches /api/data.json", "HTTP/1.1 200 OK" in res and '{"api": "v1"}' in res, "200 OK")

        # 6. Route path boundary: /apix must NOT match /api -> 404
        res = send_raw(port, b"GET /apix HTTP/1.1\r\nHost: host1.local\r\n\r\n")
        record(phase, "Path boundary (/apix does NOT match /api -> 404)", "HTTP/1.1 404" in res, res.split("\r\n")[0])

        # 7. Longest-prefix matching: /api/v2/data.json matches /api/v2
        res = send_raw(port, b"GET /api/v2/data.json HTTP/1.1\r\nHost: host1.local\r\n\r\n")
        record(phase, "Longest-prefix route matching (/api/v2)", "HTTP/1.1 200 OK" in res and '{"api": "v2"}' in res, "200 OK")

        # 8. Route isolation between virtual servers (host1 does not have /secret)
        res = send_raw(port, b"GET /secret/data.json HTTP/1.1\r\nHost: host1.local\r\n\r\n")
        record(phase, "Route isolation (/secret on host1 -> 404)", "HTTP/1.1 404" in res, res.split("\r\n")[0])

        # 9. Route available on host2
        res = send_raw(port, b"GET /secret/data.json HTTP/1.1\r\nHost: host2.local\r\n\r\n")
        record(phase, "Route accessible on configured host2", "HTTP/1.1 200 OK" in res and '{"api": "v2"}' in res, "200 OK")

        # 10. Redirect 301
        res = send_raw(port, b"GET /old-url HTTP/1.1\r\nHost: host1.local\r\n\r\n")
        record(phase, "Permanent redirect 301 + Location", "HTTP/1.1 301" in res and "Location: /new-url" in res, "301 Found")

        # 11. Redirect 302
        res = send_raw(port, b"GET /temp-url HTTP/1.1\r\nHost: host1.local\r\n\r\n")
        record(phase, "Temporary redirect 302 + Location", "HTTP/1.1 302" in res and "Location: /hello.txt" in res, "302 Found")

        # 12. Redirect isolation (host2 does not have /old-url)
        res = send_raw(port, b"GET /old-url HTTP/1.1\r\nHost: host2.local\r\n\r\n")
        record(phase, "Redirect isolation (/old-url on host2 -> 404)", "HTTP/1.1 404" in res, res.split("\r\n")[0])

    finally:
        stop_server(proc, cfg_file)
        shutil.rmtree(test_api_dir, ignore_errors=True)
        shutil.rmtree(test_v2_dir, ignore_errors=True)

# ==========================================
# PHASE 5: SECURITY AUDIT
# ==========================================
def test_phase_5(port=9320):
    phase = "Phase 5 - Security"
    print(f"\n--- Testing {phase} ---")

    cfg = f"""
server {{
    listen {port}
    server_name sec.local
    document_root ./www
}}
"""
    proc, cfg_file = start_server_with_config(cfg, port)
    try:
        # 1. Simple ../ traversal
        res = send_raw(port, b"GET /../config/server.conf HTTP/1.1\r\nHost: sec.local\r\n\r\n")
        record(phase, "Path traversal /../ blocked (403)", "HTTP/1.1 403" in res, res.split("\r\n")[0])

        # 2. Subdirectory ../../ traversal
        res = send_raw(port, b"GET /docs/../../config/server.conf HTTP/1.1\r\nHost: sec.local\r\n\r\n")
        record(phase, "Path traversal /docs/../../ blocked (403)", "HTTP/1.1 403" in res, res.split("\r\n")[0])

        # 3. Repeated slashes traversal attempt
        res = send_raw(port, b"GET //////hello.txt HTTP/1.1\r\nHost: sec.local\r\n\r\n")
        record(phase, "Repeated slashes handled safely within root", ("HTTP/1.1 200 OK" in res or "HTTP/1.1 403" in res), res.split("\r\n")[0])

        # 4. Absolute path attempt
        res = send_raw(port, b"GET /C:/Windows/win.ini HTTP/1.1\r\nHost: sec.local\r\n\r\n")
        record(phase, "Absolute path escape blocked (403 or 404)", ("HTTP/1.1 403" in res or "HTTP/1.1 404" in res) and "for 16-bit app support" not in res, res.split("\r\n")[0])

        # 5. Upload filename traversal attempt
        res = send_raw(port, b"POST /upload/..%2Fhack.txt HTTP/1.1\r\nHost: sec.local\r\nContent-Length: 4\r\n\r\ntest")
        record(phase, "Upload filename traversal blocked (403)", "HTTP/1.1 403" in res, res.split("\r\n")[0])

        # 6. Upload filename with slashes / colons
        res = send_raw(port, b"POST /upload/sub/hack.txt HTTP/1.1\r\nHost: sec.local\r\nContent-Length: 4\r\n\r\ntest")
        record(phase, "Upload filename with slash blocked (403)", "HTTP/1.1 403" in res, res.split("\r\n")[0])

        # 7. DELETE path traversal attempt
        res = send_raw(port, b"DELETE /../config/server.conf HTTP/1.1\r\nHost: sec.local\r\n\r\n")
        record(phase, "DELETE path traversal blocked (403)", "HTTP/1.1 403" in res, res.split("\r\n")[0])

        # 8. CGI script path traversal attempt
        res = send_raw(port, b"GET /cgi-bin/../hello.txt HTTP/1.1\r\nHost: sec.local\r\n\r\n")
        record(phase, "CGI path traversal blocked (403)", "HTTP/1.1 403" in res, res.split("\r\n")[0])

    finally:
        stop_server(proc, cfg_file)

# ==========================================
# PHASE 6: UPLOAD AND BODY HANDLING
# ==========================================
def test_phase_6(port=9330):
    phase = "Phase 6 - Upload & Body"
    print(f"\n--- Testing {phase} ---")

    cfg = f"""
server {{
    listen {port}
    server_name up.local
    document_root ./www
    max_body_size 20k
}}
"""
    proc, cfg_file = start_server_with_config(cfg, port)
    uploads_dir = WWW_DIR / "uploads"
    uploads_dir.mkdir(exist_ok=True)
    try:
        # 1. Small valid upload
        res = send_raw(port, b"POST /upload/p6_small.txt HTTP/1.1\r\nHost: up.local\r\nContent-Length: 12\r\n\r\nHello Upload")
        f = uploads_dir / "p6_small.txt"
        ok = "HTTP/1.1 201" in res and f.exists() and f.read_text(encoding="utf-8") == "Hello Upload"
        record(phase, "Small upload (201 Created)", ok, res.split("\r\n")[0])

        # 2. Overwrite conflict (409)
        res = send_raw(port, b"POST /upload/p6_small.txt HTTP/1.1\r\nHost: up.local\r\nContent-Length: 10\r\n\r\nNew Content")
        record(phase, "Overwrite existing file (409 Conflict)", "HTTP/1.1 409" in res, res.split("\r\n")[0])
        if f.exists(): f.unlink()

        # 3. Empty body (400)
        res = send_raw(port, b"POST /upload/p6_empty.txt HTTP/1.1\r\nHost: up.local\r\nContent-Length: 0\r\n\r\n")
        record(phase, "Empty body upload (400 Bad Request)", "HTTP/1.1 400" in res, res.split("\r\n")[0])

        # 4. Upload exactly at max_body_size (20k = 20480 bytes)
        exact_bytes = b"X" * 20480
        req = f"POST /upload/p6_exact.txt HTTP/1.1\r\nHost: up.local\r\nContent-Length: {len(exact_bytes)}\r\n\r\n".encode("utf-8") + exact_bytes
        res = send_raw(port, req)
        f_exact = uploads_dir / "p6_exact.txt"
        ok = "HTTP/1.1 201" in res and f_exact.exists() and f_exact.stat().st_size == 20480
        if f_exact.exists(): f_exact.unlink()
        record(phase, "Upload exactly at max_body_size (201 Created)", ok, res.split("\r\n")[0])

        # 5. Upload exceeding max_body_size
        over_bytes = b"X" * 20481
        req = f"POST /upload/p6_over.txt HTTP/1.1\r\nHost: up.local\r\nContent-Length: {len(over_bytes)}\r\n\r\n".encode("utf-8") + over_bytes
        res = send_raw(port, req)
        record(phase, "Upload exceeding max_body_size (413 Payload Too Large)", "HTTP/1.1 413" in res, res.split("\r\n")[0])

        # 6. Chunked upload
        chunked = b"POST /upload/p6_chunk.txt HTTP/1.1\r\nHost: up.local\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nTest\r\n6\r\n Chunk\r\n0\r\n\r\n"
        res = send_raw(port, chunked)
        f_chunk = uploads_dir / "p6_chunk.txt"
        ok = "HTTP/1.1 201" in res and f_chunk.exists() and f_chunk.read_text(encoding="utf-8") == "Test Chunk"
        if f_chunk.exists(): f_chunk.unlink()
        record(phase, "Chunked upload (201 Created)", ok, res.split("\r\n")[0])

        # 7. Aborted upload: check no temp files left behind
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.connect(("127.0.0.1", port))
        s.sendall(b"POST /upload/p6_aborted.txt HTTP/1.1\r\nHost: up.local\r\nContent-Length: 1000\r\n\r\nPartialData")
        time.sleep(0.1)
        s.close() # Abort connection
        time.sleep(0.5)

        # Check for any .tmp files
        tmp_files = list(uploads_dir.glob(".*p6_aborted*"))
        record(phase, "Aborted upload leaves no temporary files", len(tmp_files) == 0, f"Tmp files count: {len(tmp_files)}")

    finally:
        stop_server(proc, cfg_file)

# ==========================================
# PHASE 7: CGI AUDIT
# ==========================================
def test_phase_7(port=9340):
    phase = "Phase 7 - CGI"
    print(f"\n--- Testing {phase} ---")

    cgi_dir = WWW_DIR / "cgi-bin"
    cgi_dir.mkdir(exist_ok=True)

    # Helper CGI scripts
    test_env_script = cgi_dir / "env_audit.py"
    test_env_script.write_text("""
import os, sys
query = os.environ.get("QUERY_STRING", "")
path_info = os.environ.get("PATH_INFO", "")
method = os.environ.get("REQUEST_METHOD", "")
body = sys.stdin.read()
print("Content-Type: text/plain")
print("Status: 200 OK")
print()
print(f"METHOD={method}")
print(f"QUERY={query}")
print(f"PATH_INFO={path_info}")
print(f"BODY={body}")
""", encoding="utf-8")

    test_status_script = cgi_dir / "status_audit.py"
    test_status_script.write_text("""
print("Status: 202 Custom Accepted")
print("Content-Type: text/plain")
print()
print("Custom status body")
""", encoding="utf-8")

    test_err_script = cgi_dir / "exit_err.py"
    test_err_script.write_text("""
import sys
sys.exit(42)
""", encoding="utf-8")

    test_timeout_script = cgi_dir / "timeout_audit.py"
    test_timeout_script.write_text("""
import time
time.sleep(6)
print("Content-Type: text/plain\\n\\nDone")
""", encoding="utf-8")

    test_no_headers_script = cgi_dir / "no_headers.py"
    test_no_headers_script.write_text("""
print("Just plain raw text without HTTP headers")
""", encoding="utf-8")

    cfg = f"""
server {{
    listen {port}
    server_name cgi.local
    document_root ./www
}}
"""
    proc, cfg_file = start_server_with_config(cfg, port)
    try:
        # 1. Valid CGI GET + Query string + PATH_INFO
        res = send_raw(port, b"GET /cgi-bin/env_audit.py/test/path?foo=bar&baz=1 HTTP/1.1\r\nHost: cgi.local\r\n\r\n")
        ok = "HTTP/1.1 200 OK" in res and "METHOD=GET" in res and "QUERY=foo=bar&baz=1" in res and "PATH_INFO=/test/path" in res
        record(phase, "CGI GET + PATH_INFO + QUERY_STRING", ok, "200 OK")

        # 2. CGI POST body
        post_data = "alpha=100&beta=200"
        req = f"POST /cgi-bin/env_audit.py HTTP/1.1\r\nHost: cgi.local\r\nContent-Length: {len(post_data)}\r\n\r\n{post_data}".encode("utf-8")
        res = send_raw(port, req)
        ok = "HTTP/1.1 200 OK" in res and "METHOD=POST" in res and f"BODY={post_data}" in res
        record(phase, "CGI POST body passed to stdin", ok, "200 OK")

        # 3. Missing CGI script -> 404
        res = send_raw(port, b"GET /cgi-bin/nonexistent_script.py HTTP/1.1\r\nHost: cgi.local\r\n\r\n")
        record(phase, "Missing CGI script (404 Not Found)", "HTTP/1.1 404" in res, res.split("\r\n")[0])

        # 4. Unsupported script extension -> 403
        unsupported_file = cgi_dir / "script.badext"
        unsupported_file.write_text("dummy", encoding="utf-8")
        res = send_raw(port, b"GET /cgi-bin/script.badext HTTP/1.1\r\nHost: cgi.local\r\n\r\n")
        if unsupported_file.exists(): unsupported_file.unlink()
        record(phase, "Unsupported CGI script extension (403 Forbidden)", "HTTP/1.1 403" in res, res.split("\r\n")[0])

        # 5. Non-zero exit code -> 500
        res = send_raw(port, b"GET /cgi-bin/exit_err.py HTTP/1.1\r\nHost: cgi.local\r\n\r\n")
        record(phase, "CGI script non-zero exit code (500)", "HTTP/1.1 500" in res, res.split("\r\n")[0])

        # 6. Custom CGI Status header
        res = send_raw(port, b"GET /cgi-bin/status_audit.py HTTP/1.1\r\nHost: cgi.local\r\n\r\n")
        record(phase, "CGI custom Status header honored", "HTTP/1.1 202" in res, res.split("\r\n")[0])

        # 7. Headerless CGI output handled
        res = send_raw(port, b"GET /cgi-bin/no_headers.py HTTP/1.1\r\nHost: cgi.local\r\n\r\n")
        record(phase, "Headerless CGI output handled gracefully", "HTTP/1.1 200 OK" in res and "Just plain raw text" in res, res.split("\r\n")[0])

        # 8. CGI timeout (5 seconds) and no hanging process
        t0 = time.time()
        res = send_raw(port, b"GET /cgi-bin/timeout_audit.py HTTP/1.1\r\nHost: cgi.local\r\n\r\n", timeout=10)
        elapsed = time.time() - t0
        ok = "HTTP/1.1 500" in res and 4.8 <= elapsed <= 8.0
        record(phase, "CGI timeout enforcement (5s -> 500)", ok, f"Elapsed: {elapsed:.2f}s, {res.split('\r\n')[0]}")

    finally:
        stop_server(proc, cfg_file)
        for s in [test_env_script, test_status_script, test_err_script, test_timeout_script, test_no_headers_script]:
            if s.exists(): s.unlink()

# ==========================================
# PHASE 8: ERROR HANDLING AUDIT
# ==========================================
def test_phase_8(port=9350):
    phase = "Phase 8 - Error Handling"
    print(f"\n--- Testing {phase} ---")

    cfg = f"""
server {{
    listen {port}
    server_name err.local
    document_root ./www
    max_body_size 1k
    error_page 400=./errors/400.html
    error_page 403=./errors/403.html
    error_page 404=./errors/404.html
    error_page 405=./errors/405.html
    error_page 413=./errors/413.html
    error_page 500=./errors/500.html
}}
"""
    proc, cfg_file = start_server_with_config(cfg, port)
    try:
        # 400 Custom page
        res = send_raw(port, b"INVALID_REQUEST\r\n\r\n")
        record(phase, "400 Bad Request custom page", "HTTP/1.1 400" in res and "400 Bad Request" in res, "Served 400.html")

        # 403 Custom page
        res = send_raw(port, b"GET /../server.conf HTTP/1.1\r\nHost: err.local\r\n\r\n")
        record(phase, "403 Forbidden custom page", "HTTP/1.1 403" in res and "403 Forbidden" in res, "Served 403.html")

        # 404 Custom page
        res = send_raw(port, b"GET /not_found_file.html HTTP/1.1\r\nHost: err.local\r\n\r\n")
        record(phase, "404 Not Found custom page", "HTTP/1.1 404" in res and "404 Not Found" in res, "Served 404.html")

        # 405 Custom page + Allow header
        res = send_raw(port, b"PUT /hello.txt HTTP/1.1\r\nHost: err.local\r\n\r\n")
        record(phase, "405 Method Not Allowed custom page & Allow header", "HTTP/1.1 405" in res and "Allow:" in res and "405 Method Not Allowed" in res, "Served 405.html")

        # 413 Custom page
        big = b"X" * 2000
        req = f"POST /upload/big.txt HTTP/1.1\r\nHost: err.local\r\nContent-Length: {len(big)}\r\n\r\n".encode("utf-8") + big
        res = send_raw(port, req)
        record(phase, "413 Payload Too Large custom page", "HTTP/1.1 413" in res and "413 Payload Too Large" in res, "Served 413.html")

    finally:
        stop_server(proc, cfg_file)

# ==========================================
# PHASE 9: COOKIES AND SESSIONS AUDIT
# ==========================================
def test_phase_9():
    phase = "Phase 9 - Cookies & Sessions"
    print(f"\n--- Testing {phase} ---")

    # 1. Cookie class header generation & url encoding
    ok, out = run_java_snippet("""
        Cookie c = new Cookie("user_token", "abc/123==+");
        c.setPath("/app");
        c.setDomain("example.com");
        c.setMaxAge(3600);
        c.setSecure(true);
        c.setHttpOnly(true);
        String header = c.toSetCookieHeader();
        if (header.contains("user_token=") && header.contains("; Path=/app") && 
            header.contains("; Domain=example.com") && header.contains("; Max-Age=3600") && 
            header.contains("; Secure") && header.contains("; HttpOnly")) {
            System.out.println("OK");
        } else {
            throw new RuntimeException("Malformed Set-Cookie header: " + header);
        }
    """)
    record(phase, "Cookie header generation & encoding", ok, out.strip())

    # 2. Cookie header parsing
    ok, out = run_java_snippet("""
        String raw = "session_id=xyz789; theme=dark; logged_in=true";
        Map<String, String> cookies = Cookie.parseCookieHeader(raw);
        if (cookies.size() == 3 && "xyz789".equals(cookies.get("session_id")) && 
            "dark".equals(cookies.get("theme")) && "true".equals(cookies.get("logged_in"))) {
            System.out.println("OK");
        } else {
            throw new RuntimeException("Failed to parse cookies: " + cookies);
        }
    """)
    record(phase, "Cookie header parsing", ok, out.strip())

    # 3. Session creation, retrieval, and attribute storage
    ok, out = run_java_snippet("""
        SessionManager sm = new SessionManager();
        Session s1 = sm.createSession();
        String id1 = s1.getSessionId();
        s1.setAttribute("userId", 42);
        s1.setAttribute("role", "admin");

        Session retrieved = sm.getSession(id1);
        if (retrieved != null && Integer.valueOf(42).equals(retrieved.getAttribute("userId")) &&
            "admin".equals(retrieved.getAttribute("role"))) {
            System.out.println("OK");
        } else {
            throw new RuntimeException("Session attribute retrieval failed");
        }
    """)
    record(phase, "SessionManager lifecycle & attribute storage", ok, out.strip())

    # 4. Session expiration & cleanup
    ok, out = run_java_snippet("""
        SessionManager sm = new SessionManager();
        Session s = sm.createSession();
        s.setMaxInactiveInterval(50); // 50ms
        Thread.sleep(70);
        if (s.isExpired() && sm.getSession(s.getSessionId()) == null) {
            sm.cleanupExpiredSessions();
            if (sm.getActiveSessionCount() == 0) {
                System.out.println("OK");
                return;
            }
        }
        throw new RuntimeException("Session expiration check failed");
    """)
    record(phase, "Session expiration and cleanup", ok, out.strip())

    # 5. Session isolation
    ok, out = run_java_snippet("""
        SessionManager sm = new SessionManager();
        Session s1 = sm.createSession();
        Session s2 = sm.createSession();
        s1.setAttribute("user", "Alice");
        s2.setAttribute("user", "Bob");
        if ("Alice".equals(s1.getAttribute("user")) && "Bob".equals(s2.getAttribute("user"))) {
            System.out.println("OK");
        } else {
            throw new RuntimeException("Sessions leaked attributes between each other");
        }
    """)
    record(phase, "Session isolation between IDs", ok, out.strip())

# ==========================================
# PHASE 10: CONNECTION LIFECYCLE & CONCURRENCY
# ==========================================
def test_phase_10(port=9360):
    phase = "Phase 10 - Connection Lifecycle"
    print(f"\n--- Testing {phase} ---")

    cfg = f"""
server {{
    listen {port}
    server_name conn.local
    document_root ./www
}}
"""
    proc, cfg_file = start_server_with_config(cfg, port)
    try:
        # 1. Normal connection close
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.connect(("127.0.0.1", port))
        s.sendall(b"GET /hello.txt HTTP/1.1\r\nHost: conn.local\r\n\r\n")
        data = s.recv(4096)
        # Server sends Connection: close and terminates the connection
        extra = s.recv(1024)
        s.close()
        record(phase, "Connection closes after response", len(extra) == 0 and b"HTTP/1.1 200 OK" in data, "Closed cleanly")

        # 2. Concurrent connections on single NIO selector
        threads = []
        concurrency_results = []
        def worker(idx):
            try:
                res = send_raw(port, b"GET /hello.txt HTTP/1.1\r\nHost: conn.local\r\n\r\n", timeout=5)
                concurrency_results.append("HTTP/1.1 200 OK" in res)
            except Exception:
                concurrency_results.append(False)

        for i in range(15):
            t = threading.Thread(target=worker, args=(i,))
            threads.append(t)
            t.start()
        for t in threads:
            t.join()

        ok = len(concurrency_results) == 15 and all(concurrency_results)
        record(phase, "15 simultaneous non-blocking connections", ok, f"Success: {sum(concurrency_results)}/15")

        # 3. Slow drip-feed request (headers sent byte by byte without blocking selector)
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.connect(("127.0.0.1", port))
        req_line = "GET /hello.txt HTTP/1.1\r\nHost: conn.local\r\n\r\n"
        for ch in req_line:
            s.sendall(ch.encode("utf-8"))
            time.sleep(0.01) # 10ms between chars
        res = b""
        while True:
            chunk = s.recv(4096)
            if not chunk: break
            res += chunk
        s.close()
        record(phase, "Slow incremental request read", b"HTTP/1.1 200 OK" in res, "200 OK received")

    finally:
        stop_server(proc, cfg_file)

# ==========================================
# PHASE 11: RESOURCE LEAK TESTING
# ==========================================
def test_phase_11(port=9370):
    phase = "Phase 11 - Resource Leak"
    print(f"\n--- Testing {phase} ---")

    cfg = f"""
server {{
    listen {port}
    server_name leak.local
    document_root ./www
    redirect /r /hello.txt 302
}}
"""
    proc, cfg_file = start_server_with_config(cfg, port)
    p = psutil.Process(proc.pid)

    # Warmup
    for _ in range(50):
        send_raw(port, b"GET /hello.txt HTTP/1.1\r\nHost: leak.local\r\n\r\n")

    time.sleep(1)
    initial_handles = p.num_handles() if hasattr(p, "num_handles") else 0
    initial_mem_mb = p.memory_info().rss / (1024 * 1024)

    # Send 2,000 requests
    total_reqs = 2000
    errors = 0
    endpoints = [
        b"GET /hello.txt HTTP/1.1\r\nHost: leak.local\r\n\r\n",
        b"GET /r HTTP/1.1\r\nHost: leak.local\r\n\r\n",
        b"GET /nonexistent_file.txt HTTP/1.1\r\nHost: leak.local\r\n\r\n",
        b"GET /docs/index.html HTTP/1.1\r\nHost: leak.local\r\n\r\n",
    ]

    t0 = time.time()
    for i in range(total_reqs):
        ep = endpoints[i % len(endpoints)]
        res = send_raw(port, ep, timeout=3)
        if not ("200 OK" in res or "302 Found" in res or "404 Not Found" in res):
            errors += 1

    time.sleep(1.5)
    final_handles = p.num_handles() if hasattr(p, "num_handles") else 0
    final_mem_mb = p.memory_info().rss / (1024 * 1024)
    open_conns = len([c for c in p.net_connections() if c.laddr.port == port])

    stop_server(proc, cfg_file)

    handle_growth = final_handles - initial_handles
    mem_growth_mb = final_mem_mb - initial_mem_mb

    leak_ok = (errors == 0) and (handle_growth < 50) and (mem_growth_mb < 80)
    record(phase, f"2,000 requests stability & leak check", leak_ok,
           f"Errors: {errors}, Memory: {initial_mem_mb:.1f}MB -> {final_mem_mb:.1f}MB (+{mem_growth_mb:.1f}MB), Handles: {initial_handles} -> {final_handles} (+{handle_growth}), Lingering Sockets: {open_conns}")

# ==========================================
# PHASE 12: LOAD AND STRESS TESTING
# ==========================================
def test_phase_12(port=9380):
    phase = "Phase 12 - Load & Stress"
    print(f"\n--- Testing {phase} ---")

    cfg = f"""
server {{
    listen {port}
    server_name load.local
    document_root ./www
}}
"""
    proc, cfg_file = start_server_with_config(cfg, port)

    # Note that siege is not available on Windows, using Python concurrent benchmark
    CONCURRENT_WORKERS = 10
    REQUESTS_PER_WORKER = 100
    TOTAL_REQUESTS = CONCURRENT_WORKERS * REQUESTS_PER_WORKER

    latencies = []
    failed_count = 0
    lock = threading.Lock()

    def worker():
        nonlocal failed_count
        for _ in range(REQUESTS_PER_WORKER):
            t_start = time.time()
            try:
                res = send_raw(port, b"GET /hello.txt HTTP/1.1\r\nHost: load.local\r\n\r\n", timeout=5)
                lat = (time.time() - t_start) * 1000 # ms
                if "200 OK" in res:
                    with lock:
                        latencies.append(lat)
                else:
                    with lock:
                        failed_count += 1
            except Exception:
                with lock:
                    failed_count += 1

    t0 = time.time()
    threads = [threading.Thread(target=worker) for _ in range(CONCURRENT_WORKERS)]
    for t in threads: t.start()
    for t in threads: t.join()
    total_time = time.time() - t0

    stop_server(proc, cfg_file)

    successful = len(latencies)
    availability = (successful / TOTAL_REQUESTS) * 100.0
    throughput = successful / total_time if total_time > 0 else 0
    min_lat = min(latencies) if latencies else 0
    avg_lat = sum(latencies) / len(latencies) if latencies else 0
    max_lat = max(latencies) if latencies else 0

    passed = (availability >= 99.0) and (throughput > 50)
    record(phase, f"Load benchmark ({CONCURRENT_WORKERS} workers, {TOTAL_REQUESTS} reqs)", passed,
           f"Availability: {availability:.2f}%, Throughput: {throughput:.1f} req/s, Latency: min={min_lat:.1f}ms avg={avg_lat:.1f}ms max={max_lat:.1f}ms, Failed: {failed_count}/{TOTAL_REQUESTS}")

    # Store metrics for final report
    results["load_metrics"] = {
        "concurrency": CONCURRENT_WORKERS,
        "total": TOTAL_REQUESTS,
        "success": successful,
        "failed": failed_count,
        "availability": availability,
        "throughput": throughput,
        "min_latency": min_lat,
        "avg_latency": avg_lat,
        "max_latency": max_lat,
        "total_time": total_time
    }

if __name__ == "__main__":
    test_phase_2()
    test_phase_3()
    test_phase_4()
    test_phase_5()
    test_phase_6()
    test_phase_7()
    test_phase_8()
    test_phase_9()
    test_phase_10()
    test_phase_11()
    test_phase_12()

    print("\n================ AUDIT SUMMARY ================")
    all_pass = True
    total_p = 0
    total_f = 0
    for phase, phase_res in results.items():
        if phase == "load_metrics": continue
        p_count = sum(1 for _, p, _ in phase_res if p)
        f_count = sum(1 for _, p, _ in phase_res if not p)
        total_p += p_count
        total_f += f_count
        if f_count > 0: all_pass = False
        print(f"{phase}: {p_count} passed, {f_count} failed")

    print(f"\nOVERALL: {total_p} passed, {total_f} failed")
