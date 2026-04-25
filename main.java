import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * susess
 * ------
 * Single-file Java companion for the Targoera Solidity contract.
 *
 * Goals:
 * - Serve the "soia" web UI from disk (no bundler required).
 * - Provide a minimal local API to compute EVM-correct Keccak-256, ABI encoding,
 *   EIP-712 typed data payloads for Intent signing, and intent hashes.
 * - Optional JSON-RPC passthrough for eth_call / chain metadata (read-only by default).
 *
 * Run:
 *   javac Susess.java
 *   java Susess
 *
 * Env (optional):
 *   SUSESS_PORT=8788
 *   SUSESS_BIND=127.0.0.1
 *   SUSESS_UI_DIR=../soia
 *   SUSESS_RPC=https://rpc.example
 *   SUSESS_CHAIN_ID=1
 *   SUSESS_CONTRACT=0x....
 */
public final class Susess {

    // --------------------------- configuration ---------------------------

    private static final class Cfg {
        final String bind;
        final int port;
        final String rpcUrl;
        final long chainId;
        final String contract;
        final Path uiDir;

        private Cfg(String bind, int port, String rpcUrl, long chainId, String contract, Path uiDir) {
            this.bind = bind;
            this.port = port;
            this.rpcUrl = rpcUrl;
            this.chainId = chainId;
            this.contract = contract;
            this.uiDir = uiDir;
        }

        static Cfg fromEnv() {
            String bind = Env.get("SUSESS_BIND").orElse("127.0.0.1");
            int port = Env.getInt("SUSESS_PORT").orElse(8788);
            String rpc = Env.get("SUSESS_RPC").orElse("");
            long chainId = Env.getLong("SUSESS_CHAIN_ID").orElse(1L);
            String contract = Env.get("SUSESS_CONTRACT").orElse("");
            Path ui = Paths.get(Env.get("SUSESS_UI_DIR").orElse("../soia")).normalize();
            return new Cfg(bind, port, rpc, chainId, contract, ui);
        }
    }

    private static final class Env {
        static Optional<String> get(String k) {
            String v = System.getenv(k);
            if (v == null) return Optional.empty();
            v = v.trim();
            if (v.isEmpty()) return Optional.empty();
            return Optional.of(v);
        }

        static Optional<Integer> getInt(String k) {
            return get(k).flatMap(s -> {
                try {
                    return Optional.of(Integer.parseInt(s.trim()));
                } catch (RuntimeException e) {
                    return Optional.empty();
                }
            });
        }

        static Optional<Long> getLong(String k) {
            return get(k).flatMap(s -> {
                try {
                    return Optional.of(Long.parseLong(s.trim()));
                } catch (RuntimeException e) {
                    return Optional.empty();
                }
            });
        }
    }

    // --------------------------- lifecycle ---------------------------

    private static final AtomicLong REQ_ID = new AtomicLong(1000);
    private static final SecureRandom RNG = new SecureRandom();

    public static void main(String[] args) throws Exception {
        Cfg cfg = Cfg.fromEnv();
        Log.info("susess starting",
                "bind", cfg.bind,
                "port", String.valueOf(cfg.port),
                "uiDir", cfg.uiDir.toAbsolutePath().toString(),
                "rpc", cfg.rpcUrl.isEmpty() ? "(disabled)" : cfg.rpcUrl,
                "chainId", String.valueOf(cfg.chainId),
                "contract", cfg.contract.isEmpty() ? "(unset)" : cfg.contract);

        HttpServer server = HttpServer.create(new InetSocketAddress(cfg.bind, cfg.port), 0);
        server.setExecutor(Executors.newFixedThreadPool(12, new NamedThreadFactory("susess-http-")));

        Router r = new Router(cfg);
        server.createContext("/", r);

        server.start();
        Log.info("ready", "url", "http://" + cfg.bind + ":" + cfg.port + "/");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("shutdown requested");
            try {
                server.stop(0);
            } catch (RuntimeException ignored) {
            }
        }, "susess-shutdown"));
    }

    // --------------------------- http routing ---------------------------

    private static final class Router implements HttpHandler {
        private final Cfg cfg;
        private final Api api;

        Router(Cfg cfg) {
            this.cfg = cfg;
            this.api = new Api(cfg);
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            long t0 = System.nanoTime();
            String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
            URI uri = ex.getRequestURI();
            String path = Optional.ofNullable(uri.getPath()).orElse("/");

            try {
                if (path.startsWith("/api/")) {
                    api.handle(ex, method, path);
                } else {
                    serveUi(ex, method, path);
                }
