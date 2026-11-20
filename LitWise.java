import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * LitWise - plain Java (no Spring, no Maven, no jars).
 * Run:  java LitWise.java      then open http://localhost:8080
 * PDF text is read in the browser (pdf.js) and sent here; Java does the
 * section extraction, gap detection, topic and citation logic.
 */
public class LitWise {

    static class Paper {
        long id;
        String name, objective, method, dataset, findings, limitations, futureScope;
    }

    static final List<Paper> papers = new ArrayList<>();
    static long nextId = 1;

    static final Set<String> STOP = Set.of(
            "the", "and", "for", "with", "that", "this", "are", "was", "were", "not", "can", "has", "have",
            "been", "into", "from", "than", "also", "such", "their", "its", "paper", "study");

    public static void main(String[] args) throws Exception {
        int port = 8080;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", ex -> {
            try {
                route(ex);
            } catch (Exception e) {
                e.printStackTrace();
                send(ex, 500, "application/json", "{\"error\":" + q(String.valueOf(e.getMessage())) + "}");
            }
        });
        server.start();
        System.out.println("LitWise running at http://localhost:" + port + "  (Ctrl+C to stop)");
    }

    // ---------- routing ----------
    static void route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String m = ex.getRequestMethod();

        if (m.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
            Path f = Paths.get("index.html");
            if (!Files.exists(f)) {
                send(ex, 404, "text/plain", "index.html must be in the same folder you ran the command from.");
                return;
            }
            send(ex, 200, "text/html; charset=utf-8", Files.readString(f));

        } else if (m.equals("POST") && path.equals("/api/topics/suggest")) {
            send(ex, 200, "application/json", suggestTopics(form(ex).get("area")));

        } else if (m.equals("POST") && path.equals("/api/papers")) {
            Map<String, String> f = form(ex);
            Paper p = extractFields(f.getOrDefault("text", ""), f.getOrDefault("name", "paper.pdf"));
            synchronized (papers) {
                p.id = nextId++;
                papers.add(p);
            }
            send(ex, 200, "application/json", paperJson(p));

        } else if (m.equals("GET") && path.equals("/api/papers")) {
            synchronized (papers) {
                send(ex, 200, "application/json",
                        papers.stream().map(LitWise::paperJson).collect(Collectors.joining(",", "[", "]")));
            }

        } else if (m.equals("GET") && path.equals("/api/papers/gaps")) {
            synchronized (papers) {
                send(ex, 200, "application/json", gapsJson(new ArrayList<>(papers)));
            }

        } else if (m.equals("DELETE") && path.startsWith("/api/papers/")) {
            long id = Long.parseLong(path.substring("/api/papers/".length()));
            synchronized (papers) {
                papers.removeIf(p -> p.id == id);
            }
            send(ex, 200, "application/json", "{}");

        } else if (m.equals("POST") && path.equals("/api/citations/generate")) {
            send(ex, 200, "application/json", citation(form(ex)));

        } else {
            send(ex, 404, "text/plain", "Not found");
        }
    }

    // ---------- topics ----------
    static String suggestTopics(String areaIn) {
        String area = (areaIn == null || areaIn.isBlank()) ? "your field" : areaIn.trim();
        String cap = Character.toUpperCase(area.charAt(0)) + area.substring(1);
        String[][] t = {
                {cap + " — constraint-focused angle",
                        "How can " + area + " be improved under limited data, hardware, or connectivity constraints?"},
                {cap + " — comparative angle",
                        "How do existing approaches to " + area + " compare in accuracy, cost, and usability?"},
                {cap + " — applied / deployment angle",
                        "What are the practical barriers to deploying " + area + " in a real-world setting?"}
        };
        return Arrays.stream(t)
                .map(x -> "{\"title\":" + q(x[0]) + ",\"question\":" + q(x[1]) + "}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    // ---------- extraction (heuristic, keyword based) ----------
    static Paper extractFields(String text, String name) {
        Paper p = new Paper();
        p.name = name;
        p.objective = grab(text, List.of("objective", "aim of this", "this paper proposes", "we propose"),
                List.of("method", "introduction"), 300);
        p.method = grab(text, List.of("methodology", "method", "approach used", "we use"),
                List.of("result", "dataset", "limitation"), 300);
        p.dataset = grab(text, List.of("dataset", "data set", "data used"),
                List.of("method", "result"), 200);
        p.findings = grab(text, List.of("result", "findings", "we found", "experiments show"),
                List.of("limitation", "conclusion"), 300);
        p.limitations = grab(text, List.of("limitation", "drawback", "shortcoming"),
                List.of("future", "conclusion"), 300);
        p.futureScope = grab(text, List.of("future work", "future scope", "future research"),
                List.of("reference", "acknowledg"), 250);
        return p;
    }

    static String grab(String text, List<String> keywords, List<String> stopWords, int maxLen) {
        String lower = text.toLowerCase();
        for (String kw : keywords) {
            int idx = lower.indexOf(kw);
            if (idx >= 0) {
                int end = Math.min(text.length(), idx + maxLen);
                String seg = text.substring(idx, end);
                seg = seg.replaceFirst("(?i)^" + Pattern.quote(kw), "").replaceFirst("^[:\\-\\s]+", "");
                for (String sw : stopWords) {
                    int si = seg.toLowerCase().indexOf(sw);
                    if (si > 40) seg = seg.substring(0, si);
                }
                String[] words = seg.trim().split("\\s+");
                String out = String.join(" ", Arrays.copyOfRange(words, 0, Math.min(words.length, 45)));
                return out.isBlank() ? "—" : out;
            }
        }
        return "—";
    }

    // ---------- gaps ----------
    static String gapsJson(List<Paper> list) {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Set<String>> sources = new HashMap<>();
        Pattern word = Pattern.compile("[a-z]{4,}");

        for (Paper p : list) {
            String lim = p.limitations == null ? "" : p.limitations.toLowerCase();
            Set<String> seen = new HashSet<>();
            Matcher m = word.matcher(lim);
            while (m.find()) {
                String w = m.group();
                if (STOP.contains(w) || !seen.add(w)) continue;
                counts.merge(w, 1, (a, b) -> a + b);
                sources.computeIfAbsent(w, k -> new LinkedHashSet<>()).add(p.name);
            }
        }
        return counts.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(6)
                .map(e -> "{\"term\":" + q(e.getKey()) + ",\"count\":" + e.getValue()
                        + ",\"sources\":" + sources.get(e.getKey()).stream().map(LitWise::q)
                        .collect(Collectors.joining(",", "[", "]"))
                        + ",\"note\":\"SUGGESTED, NOT CONFIRMED\"}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    // ---------- citations ----------
    static String citation(Map<String, String> f) {
        String a = or(f.get("authors"), "Author, A.");
        String y = or(f.get("year"), "n.d.");
        String t = or(f.get("title"), "Title of paper");
        String j = or(f.get("journal"), "Journal name");
        String v = f.getOrDefault("volume", "").trim();
        String ieee = "[1] " + a + ", \"" + t + ",\" " + j + (v.isEmpty() ? "" : ", " + v) + ", " + y + ".";
        String apa = a + " (" + y + "). " + t + ". " + j + (v.isEmpty() ? "" : ", " + v) + ".";
        return "{\"ieee\":" + q(ieee) + ",\"apa\":" + q(apa) + "}";
    }

    static String or(String s, String def) {
        return (s == null || s.isBlank()) ? def : s.trim();
    }

    // ---------- helpers ----------
    static String paperJson(Paper p) {
        return "{\"id\":" + p.id + ",\"name\":" + q(p.name) + ",\"objective\":" + q(p.objective)
                + ",\"method\":" + q(p.method) + ",\"dataset\":" + q(p.dataset)
                + ",\"findings\":" + q(p.findings) + ",\"limitations\":" + q(p.limitations)
                + ",\"futureScope\":" + q(p.futureScope) + "}";
    }

    /** JSON-quote a string. */
    static String q(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Parse an application/x-www-form-urlencoded request body. */
    static Map<String, String> form(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> map = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            String v = i < 0 ? "" : pair.substring(i + 1);
            map.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return map;
    }

    static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}
