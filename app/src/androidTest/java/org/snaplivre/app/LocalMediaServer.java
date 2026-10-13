package org.snaplivre.app;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Device-local HTTP server. No public website or host forwarding is involved. */
final class LocalMediaServer implements AutoCloseable {
  static final class Request {
    final String method;
    final String path;
    final long rangeStart;

    Request(String method, String path, long rangeStart) {
      this.method = method;
      this.path = path;
      this.rangeStart = rangeStart;
    }
  }

  private static final class Route {
    final byte[] content;
    final boolean throttled;

    Route(byte[] content, boolean throttled) {
      this.content = content;
      this.throttled = throttled;
    }
  }

  private final ServerSocket listener;
  private final ExecutorService clients = Executors.newCachedThreadPool();
  private final Map<String, Route> routes = Collections.synchronizedMap(new HashMap<>());
  private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
  private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());
  private volatile boolean closed;

  LocalMediaServer() throws IOException {
    listener = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
    Thread acceptor =
        new Thread(
            () -> {
              while (!closed) {
                try {
                  Socket socket = listener.accept();
                  socket.setSoTimeout(15000);
                  sockets.add(socket);
                  clients.execute(() -> serve(socket));
                } catch (IOException ignored) {
                  if (!closed)
                    throw new IllegalStateException("Local HTTP listener failed.", ignored);
                }
              }
            },
            "fixture-http");
    acceptor.setDaemon(true);
    acceptor.start();
  }

  String register(String path, byte[] content, boolean throttled) {
    routes.put(path, new Route(content, throttled));
    return "http://127.0.0.1:" + listener.getLocalPort() + path;
  }

  List<Request> requests(String path) {
    List<Request> result = new ArrayList<>();
    synchronized (requests) {
      for (Request request : requests) if (path.equals(request.path)) result.add(request);
    }
    return result;
  }

  private void serve(Socket socket) {
    try (Socket connection = socket) {
      BufferedReader input =
          new BufferedReader(
              new InputStreamReader(connection.getInputStream(), StandardCharsets.ISO_8859_1));
      String first = input.readLine();
      if (first == null) return;
      String[] line = first.split(" ", 3);
      if (line.length < 2) return;
      String path = URI.create(line[1]).getPath();
      String range = null;
      for (int n = 0; n < 100; n++) {
        String header = input.readLine();
        if (header == null || header.isEmpty()) break;
        int colon = header.indexOf(':');
        if (colon > 0 && "range".equals(header.substring(0, colon).toLowerCase(Locale.ROOT))) {
          range = header.substring(colon + 1).trim();
        }
      }
      Route route = routes.get(path);
      OutputStream output = connection.getOutputStream();
      if (route == null) {
        output.write(
            "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                .getBytes(StandardCharsets.ISO_8859_1));
        return;
      }
      long start = 0;
      long end = route.content.length - 1L;
      boolean partial = false;
      if (range != null) {
        Matcher parsed = Pattern.compile("bytes=(\\d+)-(\\d*)").matcher(range);
        if (parsed.matches()) {
          partial = true;
          start = Long.parseLong(parsed.group(1));
          if (!parsed.group(2).isEmpty()) end = Math.min(end, Long.parseLong(parsed.group(2)));
        }
      }
      requests.add(new Request(line[0], path, start));
      if (start > end) {
        output.write(
            ("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */"
                    + route.content.length
                    + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        return;
      }
      String headers =
          "HTTP/1.1 "
              + (partial ? "206 Partial Content" : "200 OK")
              + "\r\n"
              + "Content-Type: video/mp4\r\nAccept-Ranges: bytes\r\nContent-Length: "
              + (end - start + 1)
              + "\r\n"
              + (partial
                  ? "Content-Range: bytes "
                      + start
                      + "-"
                      + end
                      + "/"
                      + route.content.length
                      + "\r\n"
                  : "")
              + "Connection: close\r\n\r\n";
      output.write(headers.getBytes(StandardCharsets.ISO_8859_1));
      output.flush();
      if ("HEAD".equals(line[0])) return;
      int cursor = (int) start;
      while (cursor <= end && !closed && !Thread.currentThread().isInterrupted()) {
        int size = (int) Math.min(8192, end - cursor + 1);
        output.write(route.content, cursor, size);
        output.flush();
        cursor += size;
        if (route.throttled) Thread.sleep(80);
      }
    } catch (IOException ignored) {
      // yt-dlp intentionally closes its initial sniffing request and paused downloads.
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    } finally {
      sockets.remove(socket);
    }
  }

  static byte[] fixture(InputStream asset) throws IOException {
    try (InputStream input = asset;
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[16384];
      int size;
      while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
      return output.toByteArray();
    }
  }

  static byte[] paddedMp4(byte[] original) throws IOException {
    // A valid top-level ISO-BMFF free box extends transfer time without corrupting media.
    int freeSize = 1024 * 1024;
    ByteArrayOutputStream output = new ByteArrayOutputStream(original.length + freeSize);
    output.write(original);
    output.write(
        ByteBuffer.allocate(8).putInt(freeSize).put(new byte[] {'f', 'r', 'e', 'e'}).array());
    output.write(new byte[freeSize - 8]);
    return output.toByteArray();
  }

  @Override
  public void close() throws IOException {
    closed = true;
    listener.close();
    synchronized (sockets) {
      for (Socket socket : sockets)
        try {
          socket.close();
        } catch (IOException ignored) {
        }
    }
    clients.shutdownNow();
  }
}
