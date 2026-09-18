import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class Main {
  private static class Entry {
    final String value;
    final Long expiresAt;

    Entry(String value, Long expiresAt) {
      this.value = value;
      this.expiresAt = expiresAt;
    }

    boolean isExpired() {
      return expiresAt != null && System.currentTimeMillis() > expiresAt;
    }
  }

  private static class StreamEntry {
    final String id;
    final Map<String, String> fields;

    StreamEntry(String id, Map<String, String> fields) {
      this.id = id;
      this.fields = fields;
    }
  }

  private static class StreamResult {
    final String key;
    final List<StreamEntry> entries;

    StreamResult(String key, List<StreamEntry> entries) {
      this.key = key;
      this.entries = entries;
    }
  }

  private static class CountingInputStream extends FilterInputStream {
    private long count = 0;

    CountingInputStream(InputStream in) {
      super(in);
    }

    public long getCount() {
      return count;
    }

    @Override
    public int read() throws IOException {
      int b = in.read();
      if (b != -1) {
        count++;
      }
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int n = in.read(b, off, len);
      if (n > 0) {
        count += n;
      }
      return n;
    }

    @Override
    public long skip(long n) throws IOException {
      long skipped = in.skip(n);
      if (skipped > 0) {
        count += skipped;
      }
      return skipped;
    }
  }

  private static class ClientContext {
    final Set<String> watchedKeys = ConcurrentHashMap.newKeySet();
    volatile boolean dirtyCas = false;
  }

  private static final Map<String, Entry> store = new ConcurrentHashMap<>();
  private static final Map<String, List<String>> listStore = new ConcurrentHashMap<>();
  private static final Map<String, List<StreamEntry>> streamStore = new ConcurrentHashMap<>();
  private static final Map<String, Queue<CompletableFuture<String>>> blockedWaiters = new ConcurrentHashMap<>();
  private static final Map<String, Object> keyLocks = new ConcurrentHashMap<>();
  private static final Map<String, Set<ClientContext>> keyWatchers = new ConcurrentHashMap<>();
  private static final Object streamNotifier = new Object();
  private static final Object txExecutionLock = new Object();
  private static int port = 6379;
  private static String role = "master";
  private static String masterHost = null;
  private static int masterPort = -1;
  private static Socket masterSocket = null;
  private static String rdbDir = System.getProperty("user.dir");
  private static String rdbDbFilename = "";
  private static String appendOnly = "no";
  private static String appendDirName = "appendonlydir";
  private static String appendFilename = "appendonly.aof";
  private static String appendFsync = "everysec";

  private static String masterReplId = "8371b4fb1155b71f4a04d3e1bc3e18c4a990aeeb";
  private static volatile long masterReplOffset = 0;
  private static final String EMPTY_RDB_BASE64 =
      "UkVESVMwMDEx+glyZWRpcy12ZXIFNy4yLjD6CnJlZGlzLWJpdHPAQPoFY3RpbWXCbQi8ZfoIdXNlZC1tZW3CsMQQAPoIYW9mLWJhc2XAAP/wbjv+wP9aog==";
  private static final CopyOnWriteArrayList<OutputStream> replicas = new CopyOnWriteArrayList<>();
  private static final Map<OutputStream, Long> replicaAcks = new ConcurrentHashMap<>();
  private static final Object waitLock = new Object();

  private static String getInfoReplication() {
    if ("master".equalsIgnoreCase(role)) {
      return "role:master\r\nmaster_replid:" + masterReplId + "\r\nmaster_repl_offset:" + masterReplOffset;
    }
    return "role:" + role;
  }

  private static synchronized void propagate(String[] parts) {
    if (!"master".equalsIgnoreCase(role)) {
      return;
    }
    StringBuilder sb = new StringBuilder();
    sb.append("*").append(parts.length).append("\r\n");
    for (int i = 0; i < parts.length; i++) {
      String token = (i == 0) ? parts[i].toUpperCase() : parts[i];
      byte[] b = token.getBytes(StandardCharsets.UTF_8);
      sb.append("$").append(b.length).append("\r\n").append(token).append("\r\n");
    }
    byte[] msg = sb.toString().getBytes(StandardCharsets.UTF_8);
    masterReplOffset += msg.length;

    for (OutputStream replicaOut : replicas) {
      try {
        synchronized (replicaOut) {
          replicaOut.write(msg);
          replicaOut.flush();
        }
      } catch (IOException e) {
        replicas.remove(replicaOut);
        replicaAcks.remove(replicaOut);
        synchronized (waitLock) {
          waitLock.notifyAll();
        }
      }
    }
  }

  private static int countAcknowledgedReplicas(long targetOffset) {
    int count = 0;
    for (OutputStream replicaOut : replicas) {
      Long ack = replicaAcks.get(replicaOut);
      if (ack != null && ack >= targetOffset) {
        count++;
      }
    }
    return count;
  }

  private static Object getLock(String key) {
    return keyLocks.computeIfAbsent(key, k -> new Object());
  }

  private static void touchWatchedKey(String key) {
    Set<ClientContext> clients = keyWatchers.get(key);
    if (clients != null) {
      for (ClientContext client : clients) {
        client.dirtyCas = true;
      }
    }
  }

  private static void unwatchAll(ClientContext clientCtx) {
    for (String key : clientCtx.watchedKeys) {
      Set<ClientContext> clients = keyWatchers.get(key);
      if (clients != null) {
        clients.remove(clientCtx);
      }
    }
    clientCtx.watchedKeys.clear();
    clientCtx.dirtyCas = false;
  }

  private static int compareStreamId(long ms1, long seq1, long ms2, long seq2) {
    if (ms1 != ms2) {
      return Long.compare(ms1, ms2);
    }
    return Long.compare(seq1, seq2);
  }

  private static List<StreamResult> queryMatchingEntries(String[] keys, String[] startIds) {
    List<StreamResult> results = new ArrayList<>();
    for (int i = 0; i < keys.length; i++) {
      String key = keys[i];
      String startIdStr = startIds[i];

      long startMs, startSeq;
      if (startIdStr.contains("-")) {
        String[] p = startIdStr.split("-");
        startMs = Long.parseLong(p[0]);
        startSeq = Long.parseLong(p[1]);
      } else {
        startMs = Long.parseLong(startIdStr);
        startSeq = 0;
      }

      List<StreamEntry> stream = streamStore.get(key);
      List<StreamEntry> matching = new ArrayList<>();
      if (stream != null) {
        synchronized (stream) {
          for (StreamEntry entry : stream) {
            String[] partsId = entry.id.split("-");
            long entryMs = Long.parseLong(partsId[0]);
            long entrySeq = Long.parseLong(partsId[1]);

            if (compareStreamId(entryMs, entrySeq, startMs, startSeq) > 0) {
              matching.add(entry);
            }
          }
        }
      }

      if (!matching.isEmpty()) {
        results.add(new StreamResult(key, matching));
      }
    }
    return results;
  }

  private static String readLine(InputStream in) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    int b;
    while ((b = in.read()) != -1) {
      if (b == '\r') {
        int next = in.read();
        if (next == '\n') {
          break;
        }
        baos.write(b);
        if (next != -1) {
          baos.write(next);
        }
      } else if (b == '\n') {
        break;
      } else {
        baos.write(b);
      }
    }
    if (b == -1 && baos.size() == 0) {
      return null;
    }
    return baos.toString(StandardCharsets.UTF_8);
  }

  private static String stripQuotes(String s) {
    if (s == null) return null;
    s = s.trim();
    if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
      if (s.length() >= 2) {
        return s.substring(1, s.length() - 1);
      }
    }
    return s;
  }

  private static boolean matchesPattern(String pattern, String key) {
    if (pattern == null || pattern.equals("*")) return true;
    if (pattern.startsWith("*") && pattern.endsWith("*") && pattern.length() > 2) {
      String sub = pattern.substring(1, pattern.length() - 1);
      return key.contains(sub);
    }
    if (pattern.startsWith("*")) {
      String suffix = pattern.substring(1);
      return key.endsWith(suffix);
    }
    if (pattern.endsWith("*")) {
      String prefix = pattern.substring(0, pattern.length() - 1);
      return key.startsWith(prefix);
    }
    return pattern.equals(key);
  }

  private static void handleCommand(String[] parts, OutputStream out) throws IOException {
    String command = parts[0];

    if (command.equalsIgnoreCase("PING")) {
      out.write("+PONG\r\n".getBytes(StandardCharsets.UTF_8));
    } else if (command.equalsIgnoreCase("ECHO")) {
      String arg = parts[1];
      byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
      String response = "$" + bytes.length + "\r\n" + arg + "\r\n";
      out.write(response.getBytes(StandardCharsets.UTF_8));
    } else if (command.equalsIgnoreCase("SET")) {
      String key = parts[1];
      String value = parts[2];
      Long expiresAt = null;

      if (parts.length >= 5) {
        if (parts[3].equalsIgnoreCase("PX")) {
          long pxMillis = Long.parseLong(parts[4]);
          expiresAt = System.currentTimeMillis() + pxMillis;
        } else if (parts[3].equalsIgnoreCase("EX")) {
          long exSeconds = Long.parseLong(parts[4]);
          expiresAt = System.currentTimeMillis() + (exSeconds * 1000);
        }
      }

      store.put(key, new Entry(value, expiresAt));
      touchWatchedKey(key);
      out.write("+OK\r\n".getBytes(StandardCharsets.UTF_8));
      propagate(parts);
    } else if (command.equalsIgnoreCase("GET")) {
      if (parts.length < 2) {
        out.write("-ERR wrong number of arguments for 'get' command\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
        return;
      }
      String rawKey = parts[1];
      String key = stripQuotes(rawKey);
      Entry entry = store.get(key);
      if (entry == null && !key.equals(rawKey)) {
        entry = store.get(rawKey);
      }

      if (entry != null && !entry.isExpired()) {
        byte[] bytes = entry.value.getBytes(StandardCharsets.UTF_8);
        String response = "$" + bytes.length + "\r\n" + entry.value + "\r\n";
        out.write(response.getBytes(StandardCharsets.UTF_8));
      } else {
        if (entry != null && entry.isExpired()) {
          store.remove(key);
          if (!key.equals(rawKey)) {
            store.remove(rawKey);
          }
        }
        out.write("$-1\r\n".getBytes(StandardCharsets.UTF_8));
      }
      out.flush();
    } else if (command.equalsIgnoreCase("INCR")) {
      if (parts.length < 2) {
        out.write("-ERR wrong number of arguments for 'incr' command\r\n".getBytes(StandardCharsets.UTF_8));
      } else {
        String key = parts[1];
        final long[] resultVal = new long[1];
        final boolean[] isNaN = new boolean[1];

        store.compute(key, (k, old) -> {
          if (old == null || old.isExpired()) {
            resultVal[0] = 1;
            return new Entry("1", null);
          }
          try {
            long current = Long.parseLong(old.value);
            resultVal[0] = current + 1;
            return new Entry(String.valueOf(resultVal[0]), old.expiresAt);
          } catch (NumberFormatException e) {
            isNaN[0] = true;
            return old;
          }
        });

        if (isNaN[0]) {
          out.write("-ERR value is not an integer or out of range\r\n".getBytes(StandardCharsets.UTF_8));
        } else {
          touchWatchedKey(key);
          out.write((":" + resultVal[0] + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        out.flush();
      }
    } else if (command.equalsIgnoreCase("TYPE")) {
      if (parts.length < 2) {
        out.write("-ERR wrong number of arguments for 'type' command\r\n".getBytes(StandardCharsets.UTF_8));
      } else {
        String key = parts[1];
        Entry entry = store.get(key);
        if (entry != null) {
          if (entry.isExpired()) {
            store.remove(key);
            entry = null;
          }
        }

        if (entry != null) {
          out.write("+string\r\n".getBytes(StandardCharsets.UTF_8));
        } else {
          List<String> list = listStore.get(key);
          if (list != null && !list.isEmpty()) {
            out.write("+list\r\n".getBytes(StandardCharsets.UTF_8));
          } else if (streamStore.containsKey(key)) {
            out.write("+stream\r\n".getBytes(StandardCharsets.UTF_8));
          } else {
            out.write("+none\r\n".getBytes(StandardCharsets.UTF_8));
          }
        }
      }
    } else if (command.equalsIgnoreCase("XADD")) {
      if (parts.length < 5 || (parts.length - 3) % 2 != 0) {
        out.write("-ERR wrong number of arguments for 'xadd' command\r\n".getBytes(StandardCharsets.UTF_8));
      } else {
        String key = parts[1];
        String id = parts[2];
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 3; i < parts.length - 1; i += 2) {
          fields.put(parts[i], parts[i + 1]);
        }

        List<StreamEntry> stream = streamStore.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()));

        if (id.equals("*")) {
          long time = System.currentTimeMillis();
          synchronized (stream) {
            long seq;
            if (stream.isEmpty()) {
              seq = 0;
            } else {
              StreamEntry lastEntry = stream.get(stream.size() - 1);
              String[] lastParts = lastEntry.id.split("-");
              long lastTime = Long.parseLong(lastParts[0]);
              long lastSeq = Long.parseLong(lastParts[1]);

              if (time == lastTime) {
                seq = lastSeq + 1;
              } else if (time > lastTime) {
                seq = 0;
              } else {
                time = lastTime;
                seq = lastSeq + 1;
              }
            }

            String generatedId = time + "-" + seq;
            stream.add(new StreamEntry(generatedId, fields));
            touchWatchedKey(key);
            synchronized (streamNotifier) {
              streamNotifier.notifyAll();
            }
            byte[] idBytes = generatedId.getBytes(StandardCharsets.UTF_8);
            String response = "$" + idBytes.length + "\r\n" + generatedId + "\r\n";
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();
          }
        } else if (id.endsWith("-*")) {
          long time = Long.parseLong(id.substring(0, id.length() - 2));
          synchronized (stream) {
            String generatedId = null;
            if (stream.isEmpty()) {
              long seq = (time == 0) ? 1 : 0;
              generatedId = time + "-" + seq;
            } else {
              StreamEntry lastEntry = stream.get(stream.size() - 1);
              String[] lastParts = lastEntry.id.split("-");
              long lastTime = Long.parseLong(lastParts[0]);
              long lastSeq = Long.parseLong(lastParts[1]);

              if (time < lastTime) {
                out.write("-ERR The ID specified in XADD is equal or smaller than the target stream top item\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
              } else if (time == lastTime) {
                long seq = lastSeq + 1;
                generatedId = time + "-" + seq;
              } else {
                long seq = (time == 0) ? 1 : 0;
                generatedId = time + "-" + seq;
              }
            }

            if (generatedId != null) {
              stream.add(new StreamEntry(generatedId, fields));
              touchWatchedKey(key);
              synchronized (streamNotifier) {
                streamNotifier.notifyAll();
              }
              byte[] idBytes = generatedId.getBytes(StandardCharsets.UTF_8);
              String response = "$" + idBytes.length + "\r\n" + generatedId + "\r\n";
              out.write(response.getBytes(StandardCharsets.UTF_8));
              out.flush();
            }
          }
        } else {
          String[] dash = id.split("-");
          long time = Long.parseLong(dash[0]);
          long seq = Long.parseLong(dash[1]);

          if (time <= 0 && seq <= 0) {
            out.write("-ERR The ID specified in XADD must be greater than 0-0\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
          } else {
            synchronized (stream) {
              boolean valid = true;
              if (!stream.isEmpty()) {
                StreamEntry lastEntry = stream.get(stream.size() - 1);
                String[] lastDash = lastEntry.id.split("-");
                long lastTime = Long.parseLong(lastDash[0]);
                long lastSeq = Long.parseLong(lastDash[1]);
                if ((time < lastTime) || (time == lastTime && seq <= lastSeq)) {
                  valid = false;
                  out.write("-ERR The ID specified in XADD is equal or smaller than the target stream top item\r\n".getBytes(StandardCharsets.UTF_8));
                  out.flush();
                }
              }

              if (valid) {
                stream.add(new StreamEntry(id, fields));
                touchWatchedKey(key);
                synchronized (streamNotifier) {
                  streamNotifier.notifyAll();
                }
                byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
                String response = "$" + idBytes.length + "\r\n" + id + "\r\n";
                out.write(response.getBytes(StandardCharsets.UTF_8));
                out.flush();
              }
            }
          }
        }
      }
    } else if (command.equalsIgnoreCase("XRANGE")) {
      if (parts.length < 4) {
        out.write("-ERR wrong number of arguments for 'xrange' command\r\n".getBytes(StandardCharsets.UTF_8));
      } else {
        String key = parts[1];
        String start = parts[2];
        String end = parts[3];

        long startMs, startSeq;
        if (start.equals("-")) {
          startMs = 0;
          startSeq = 0;
        } else if (start.equals("+")) {
          startMs = Long.MAX_VALUE;
          startSeq = Long.MAX_VALUE;
        } else if (start.contains("-")) {
          String[] p = start.split("-");
          startMs = Long.parseLong(p[0]);
          startSeq = Long.parseLong(p[1]);
        } else {
          startMs = Long.parseLong(start);
          startSeq = 0;
        }

        long endMs, endSeq;
        if (end.equals("+")) {
          endMs = Long.MAX_VALUE;
          endSeq = Long.MAX_VALUE;
        } else if (end.equals("-")) {
          endMs = 0;
          endSeq = 0;
        } else if (end.contains("-")) {
          String[] p = end.split("-");
          endMs = Long.parseLong(p[0]);
          endSeq = Long.parseLong(p[1]);
        } else {
          endMs = Long.parseLong(end);
          endSeq = Long.MAX_VALUE;
        }

        List<StreamEntry> stream = streamStore.get(key);
        if (stream == null || stream.isEmpty()) {
          out.write("*0\r\n".getBytes(StandardCharsets.UTF_8));
        } else {
          List<StreamEntry> matching = new ArrayList<>();
          synchronized (stream) {
            for (StreamEntry entry : stream) {
              String[] partsId = entry.id.split("-");
              long entryMs = Long.parseLong(partsId[0]);
              long entrySeq = Long.parseLong(partsId[1]);

              if (compareStreamId(entryMs, entrySeq, startMs, startSeq) >= 0 &&
                  compareStreamId(entryMs, entrySeq, endMs, endSeq) <= 0) {
                matching.add(entry);
              }
            }
          }

          StringBuilder sb = new StringBuilder();
          sb.append("*").append(matching.size()).append("\r\n");
          for (StreamEntry entry : matching) {
            sb.append("*2\r\n");
            byte[] idBytes = entry.id.getBytes(StandardCharsets.UTF_8);
            sb.append("$").append(idBytes.length).append("\r\n").append(entry.id).append("\r\n");
            sb.append("*").append(entry.fields.size() * 2).append("\r\n");
            for (Map.Entry<String, String> field : entry.fields.entrySet()) {
              byte[] kBytes = field.getKey().getBytes(StandardCharsets.UTF_8);
              sb.append("$").append(kBytes.length).append("\r\n").append(field.getKey()).append("\r\n");
              byte[] vBytes = field.getValue().getBytes(StandardCharsets.UTF_8);
              sb.append("$").append(vBytes.length).append("\r\n").append(field.getValue()).append("\r\n");
            }
          }
          out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
      }
    } else if (command.equalsIgnoreCase("XREAD")) {
      int streamsIndex = -1;
      long blockTimeout = -1;

      for (int i = 1; i < parts.length; i++) {
        if (parts[i].equalsIgnoreCase("BLOCK")) {
          if (i + 1 < parts.length) {
            blockTimeout = Long.parseLong(parts[i + 1]);
            i++;
          }
        } else if (parts[i].equalsIgnoreCase("STREAMS")) {
          streamsIndex = i;
          break;
        }
      }

      int rem = (streamsIndex == -1) ? 0 : parts.length - (streamsIndex + 1);
      if (streamsIndex == -1 || rem <= 0 || rem % 2 != 0) {
        out.write("-ERR syntax error\r\n".getBytes(StandardCharsets.UTF_8));
      } else {
        int numStreams = rem / 2;
        String[] keys = new String[numStreams];
        String[] startIds = new String[numStreams];
        for (int i = 0; i < numStreams; i++) {
          keys[i] = parts[streamsIndex + 1 + i];
          startIds[i] = parts[streamsIndex + 1 + numStreams + i];
        }

        for (int i = 0; i < numStreams; i++) {
          if (startIds[i].equals("$")) {
            List<StreamEntry> stream = streamStore.get(keys[i]);
            if (stream != null && !stream.isEmpty()) {
              synchronized (stream) {
                if (!stream.isEmpty()) {
                  startIds[i] = stream.get(stream.size() - 1).id;
                } else {
                  startIds[i] = "0-0";
                }
              }
            } else {
              startIds[i] = "0-0";
            }
          }
        }

        long deadline = (blockTimeout == 0) ? Long.MAX_VALUE : (System.currentTimeMillis() + blockTimeout);
        List<StreamResult> matching = queryMatchingEntries(keys, startIds);

        if (matching.isEmpty() && blockTimeout >= 0) {
          synchronized (streamNotifier) {
            matching = queryMatchingEntries(keys, startIds);
            while (matching.isEmpty()) {
              long remMs = deadline - System.currentTimeMillis();
              if (blockTimeout > 0 && remMs <= 0) break;
              try {
                if (blockTimeout == 0) {
                  streamNotifier.wait();
                } else {
                  streamNotifier.wait(Math.max(1, remMs));
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
              }
              matching = queryMatchingEntries(keys, startIds);
            }
          }
        }

        if (matching.isEmpty()) {
          out.write("*-1\r\n".getBytes(StandardCharsets.UTF_8));
        } else {
          StringBuilder sb = new StringBuilder();
          sb.append("*").append(matching.size()).append("\r\n");
          for (StreamResult sr : matching) {
            sb.append("*2\r\n");
            byte[] keyBytes = sr.key.getBytes(StandardCharsets.UTF_8);
            sb.append("$").append(keyBytes.length).append("\r\n").append(sr.key).append("\r\n");
            sb.append("*").append(sr.entries.size()).append("\r\n");
            for (StreamEntry entry : sr.entries) {
              sb.append("*2\r\n");
              byte[] idBytes = entry.id.getBytes(StandardCharsets.UTF_8);
              sb.append("$").append(idBytes.length).append("\r\n").append(entry.id).append("\r\n");
              sb.append("*").append(entry.fields.size() * 2).append("\r\n");
              for (Map.Entry<String, String> field : entry.fields.entrySet()) {
                byte[] kBytes = field.getKey().getBytes(StandardCharsets.UTF_8);
                sb.append("$").append(kBytes.length).append("\r\n").append(field.getKey()).append("\r\n");
                byte[] vBytes = field.getValue().getBytes(StandardCharsets.UTF_8);
                sb.append("$").append(vBytes.length).append("\r\n").append(field.getValue()).append("\r\n");
              }
            }
          }
          out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
      }
    } else if (command.equalsIgnoreCase("RPUSH")) {
      if (parts.length < 3) {
        out.write("-ERR wrong number of arguments for 'rpush' command\r\n".getBytes(StandardCharsets.UTF_8));
      } else {
        String key = parts[1];
        Object lock = getLock(key);
        int newLength;
        synchronized (lock) {
          List<String> list = listStore.computeIfAbsent(key, k -> new ArrayList<>());
          for (int i = 2; i < parts.length; i++) {
            list.add(parts[i]);
          }
          newLength = list.size();

          Queue<CompletableFuture<String>> waiters = blockedWaiters.get(key);
          if (waiters != null) {
            while (!list.isEmpty() && !waiters.isEmpty()) {
              CompletableFuture<String> waiter = waiters.poll();
              if (waiter != null && !waiter.isDone()) {
                String head = list.get(0);
                if (waiter.complete(head)) {
                  list.remove(0);
                }
              }
            }
          }
          if (list.isEmpty()) {
            listStore.remove(key);
          }
          touchWatchedKey(key);
        }
        String response = ":" + newLength + "\r\n";
        out.write(response.getBytes(StandardCharsets.UTF_8));
      }
    } else if (command.equalsIgnoreCase("LPUSH")) {
      if (parts.length < 3) {
        out.write("-ERR wrong number of arguments for 'lpush' command\r\n".getBytes(StandardCharsets.UTF_8));
      } else {
        String key = parts[1];
        Object lock = getLock(key);
        int newLength;
        synchronized (lock) {
          List<String> list = listStore.computeIfAbsent(key, k -> new ArrayList<>());
          for (int i = 2; i < parts.length; i++) {
            list.add(0, parts[i]);
          }
          newLength = list.size();

          Queue<CompletableFuture<String>> waiters = blockedWaiters.get(key);
          if (waiters != null) {
            while (!list.isEmpty() && !waiters.isEmpty()) {
              CompletableFuture<String> waiter = waiters.poll();
              if (waiter != null && !waiter.isDone()) {
                String head = list.get(0);
                if (waiter.complete(head)) {
                  list.remove(0);
                }
              }
            }
          }
          if (list.isEmpty()) {
            listStore.remove(key);
          }
          touchWatchedKey(key);
        }
        String response = ":" + newLength + "\r\n";
        out.write(response.getBytes(StandardCharsets.UTF_8));
      }
    } else if (command.equalsIgnoreCase("LLEN")) {
      if (parts.length < 2) {
        out.write("-ERR wrong number of arguments for 'llen' command\r\n".getBytes(StandardCharsets.UTF_8));
      } else {
        String key = parts[1];
        Object lock = getLock(key);
        int length = 0;
        synchronized (lock) {
          List<String> list = listStore.get(key);
          if (list != null) {
            length = list.size();
          }
        }
        String response = ":" + length + "\r\n";
        out.write(response.getBytes(StandardCharsets.UTF_8));
      }
    } else if (command.equalsIgnoreCase("LPOP")) {
      if (parts.length < 2) {
        out.write("-ERR wrong number of arguments for 'lpop' command\r\n".getBytes(StandardCharsets.UTF_8));
      } else if (parts.length == 2) {
        String key = parts[1];
        Object lock = getLock(key);
        String removedElement = null;
        synchronized (lock) {
          List<String> list = listStore.get(key);
          if (list != null && !list.isEmpty()) {
            removedElement = list.remove(0);
            if (list.isEmpty()) {
              listStore.remove(key);
            }
          }
        }
        if (removedElement == null) {
          out.write("$-1\r\n".getBytes(StandardCharsets.UTF_8));
        } else {
          touchWatchedKey(key);
          byte[] bytes = removedElement.getBytes(StandardCharsets.UTF_8);
          String response = "$" + bytes.length + "\r\n" + removedElement + "\r\n";
          out.write(response.getBytes(StandardCharsets.UTF_8));
        }
      } else {
        String key = parts[1];
        try {
          int count = Integer.parseInt(parts[2]);
          if (count < 0) {
            out.write("-ERR value is out of range, must be positive\r\n".getBytes(StandardCharsets.UTF_8));
          } else {
            Object lock = getLock(key);
            List<String> removedElements = new ArrayList<>();
            boolean keyExisted = false;
            synchronized (lock) {
              List<String> list = listStore.get(key);
              if (list != null) {
                keyExisted = true;
                int toRemove = Math.min(count, list.size());
                for (int i = 0; i < toRemove; i++) {
                  removedElements.add(list.remove(0));
                }
                if (list.isEmpty()) {
                  listStore.remove(key);
                }
              }
            }
            if (!keyExisted || (removedElements.isEmpty() && count > 0)) {
              out.write("*-1\r\n".getBytes(StandardCharsets.UTF_8));
            } else {
              touchWatchedKey(key);
              StringBuilder sb = new StringBuilder();
              sb.append("*").append(removedElements.size()).append("\r\n");
              for (String elem : removedElements) {
                byte[] elemBytes = elem.getBytes(StandardCharsets.UTF_8);
                sb.append("$").append(elemBytes.length).append("\r\n").append(elem).append("\r\n");
              }
              out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
          }
        } catch (NumberFormatException e) {
          out.write("-ERR value is not an integer or out of range\r\n".getBytes(StandardCharsets.UTF_8));
        }
      }
    } else if (command.equalsIgnoreCase("BLPOP")) {
      if (parts.length < 3) {
        out.write("-ERR wrong number of arguments for 'blpop' command\r\n".getBytes(StandardCharsets.UTF_8));
      } else {
        try {
          String key = parts[1];
          double timeoutSec = Double.parseDouble(parts[2]);
          if (timeoutSec < 0) {
            out.write("-ERR timeout is negative\r\n".getBytes(StandardCharsets.UTF_8));
          } else {
            long timeoutMillis = (long) (timeoutSec * 1000);
            Object lock = getLock(key);
            String popped = null;
            CompletableFuture<String> future = null;

            synchronized (lock) {
              List<String> list = listStore.get(key);
              if (list != null && !list.isEmpty()) {
                popped = list.remove(0);
                if (list.isEmpty()) {
                  listStore.remove(key);
                }
              } else {
                future = new CompletableFuture<>();
                blockedWaiters.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>()).add(future);
              }
            }

            if (popped != null) {
              touchWatchedKey(key);
              byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
              byte[] elemBytes = popped.getBytes(StandardCharsets.UTF_8);
              String response = "*2\r\n$" + keyBytes.length + "\r\n" + key + "\r\n$" + elemBytes.length + "\r\n" + popped + "\r\n";
              out.write(response.getBytes(StandardCharsets.UTF_8));
            } else if (future != null) {
              try {
                if (timeoutMillis > 0) {
                  popped = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
                } else {
                  popped = future.get();
                }
                touchWatchedKey(key);
                byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
                byte[] elemBytes = popped.getBytes(StandardCharsets.UTF_8);
                String response = "*2\r\n$" + keyBytes.length + "\r\n" + key + "\r\n$" + elemBytes.length + "\r\n" + popped + "\r\n";
                out.write(response.getBytes(StandardCharsets.UTF_8));
              } catch (TimeoutException e) {
                Queue<CompletableFuture<String>> waiters = blockedWaiters.get(key);
                if (waiters != null) {
                  waiters.remove(future);
                }
                future.cancel(true);
                out.write("*-1\r\n".getBytes(StandardCharsets.UTF_8));
              } catch (Exception e) {
                Queue<CompletableFuture<String>> waiters = blockedWaiters.get(key);
                if (waiters != null) {
                  waiters.remove(future);
                }
                future.cancel(true);
                out.write("*-1\r\n".getBytes(StandardCharsets.UTF_8));
              }
            }
          }
        } catch (NumberFormatException e) {
          out.write("-ERR timeout is not a float or out of range\r\n".getBytes(StandardCharsets.UTF_8));
        }
      }
    } else if (command.equalsIgnoreCase("LRANGE")) {
      if (parts.length < 4) {
        out.write("-ERR wrong number of arguments for 'lrange' command\r\n".getBytes(StandardCharsets.UTF_8));
      } else {
        String key = parts[1];
        int start = Integer.parseInt(parts[2]);
        int stop = Integer.parseInt(parts[3]);

        Object lock = getLock(key);
        List<String> elementsToReturn;
        synchronized (lock) {
          List<String> list = listStore.get(key);
          if (list == null || list.isEmpty()) {
            elementsToReturn = Collections.emptyList();
          } else {
            int size = list.size();
            if (start < 0) start += size;
            if (stop < 0) stop += size;
            if (start < 0) start = 0;
            if (stop < 0) stop = 0;

            if (start >= size || start > stop) {
              elementsToReturn = Collections.emptyList();
            } else {
              int stopIdx = Math.min(stop, size - 1);
              elementsToReturn = new ArrayList<>(list.subList(start, stopIdx + 1));
            }
          }
        }

        if (elementsToReturn.isEmpty()) {
          out.write("*0\r\n".getBytes(StandardCharsets.UTF_8));
        } else {
          StringBuilder sb = new StringBuilder();
          sb.append("*").append(elementsToReturn.size()).append("\r\n");
          for (String elem : elementsToReturn) {
            byte[] elemBytes = elem.getBytes(StandardCharsets.UTF_8);
            sb.append("$").append(elemBytes.length).append("\r\n").append(elem).append("\r\n");
          }
          out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
      }
    } else if (command.equalsIgnoreCase("REPLCONF")) {
      // Stage 58 & 68: Replication handshake and ACK handling
      if (parts.length >= 3 && parts[1].equalsIgnoreCase("ACK")) {
        try {
          long ackOffset = Long.parseLong(parts[2].trim());
          replicaAcks.put(out, ackOffset);
          synchronized (waitLock) {
            waitLock.notifyAll();
          }
        } catch (NumberFormatException ignored) {}
      } else {
        out.write("+OK\r\n".getBytes(StandardCharsets.UTF_8));
      }
    } else if (command.equalsIgnoreCase("PSYNC")) {
      // Stage 59: Replication handshake - master responds +FULLRESYNC <masterReplId> 0\r\n
      String response = "+FULLRESYNC " + masterReplId + " 0\r\n";
      out.write(response.getBytes(StandardCharsets.UTF_8));

      // Stage 60: Empty RDB transfer - send snapshot formatted as $<length>\r\n<bytes> (no trailing \r\n)
      byte[] rdbBytes = Base64.getDecoder().decode(EMPTY_RDB_BASE64);
      String rdbHeader = "$" + rdbBytes.length + "\r\n";
      out.write(rdbHeader.getBytes(StandardCharsets.UTF_8));
      out.write(rdbBytes);
      out.flush();

      // Track replica connection for subsequent command propagation and ACK tracking
      replicas.addIfAbsent(out);
      replicaAcks.put(out, 0L);
    } else if (command.equalsIgnoreCase("INFO")) {
      String info = getInfoReplication();
      byte[] bytes = info.getBytes(StandardCharsets.UTF_8);
      String response = "$" + bytes.length + "\r\n" + info + "\r\n";
      out.write(response.getBytes(StandardCharsets.UTF_8));
    } else if (command.equalsIgnoreCase("UNWATCH")) {
      out.write("+OK\r\n".getBytes(StandardCharsets.UTF_8));
    } else if (command.equalsIgnoreCase("WAIT")) {
      // Stage 68: WAIT with multiple commands (#na2)
      // Format: WAIT <numreplicas> <timeout>
      int numReplicas = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
      long timeout = parts.length > 2 ? Long.parseLong(parts[2]) : 0;

      if (replicas.isEmpty()) {
        out.write(":0\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
        return;
      }

      if (masterReplOffset == 0 || numReplicas == 0) {
        out.write((":" + replicas.size() + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        return;
      }

      long targetOffset = masterReplOffset;

      // Send REPLCONF GETACK * to all connected replicas
      byte[] getackMsg = "*3\r\n$8\r\nREPLCONF\r\n$6\r\nGETACK\r\n$1\r\n*\r\n".getBytes(StandardCharsets.UTF_8);
      for (OutputStream replicaOut : replicas) {
        try {
          synchronized (replicaOut) {
            replicaOut.write(getackMsg);
            replicaOut.flush();
          }
        } catch (IOException e) {
          replicas.remove(replicaOut);
          replicaAcks.remove(replicaOut);
          synchronized (waitLock) {
            waitLock.notifyAll();
          }
        }
      }

      long deadline = (timeout > 0) ? System.currentTimeMillis() + timeout : Long.MAX_VALUE;
      synchronized (waitLock) {
        while (countAcknowledgedReplicas(targetOffset) < numReplicas) {
          if (replicas.isEmpty()) {
            break;
          }
          if (timeout > 0) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
              break;
            }
            try {
              waitLock.wait(remaining);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              break;
            }
          } else {
            try {
              waitLock.wait();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              break;
            }
          }
        }
      }

      int ackedCount = countAcknowledgedReplicas(targetOffset);
      out.write((":" + ackedCount + "\r\n").getBytes(StandardCharsets.UTF_8));
      out.flush();
    } else if (command.equalsIgnoreCase("CONFIG")) {
      // Stage 69: RDB config - CONFIG GET dir / dbfilename
      if (parts.length >= 3 && parts[1].equalsIgnoreCase("GET")) {
        String param = parts[2].toLowerCase();
        String value;
        String paramName;
        if (param.equals("dir")) {
          paramName = "dir";
          value = (rdbDir != null && !rdbDir.isEmpty()) ? rdbDir : System.getProperty("user.dir");
        } else if (param.equals("dbfilename")) {
          paramName = "dbfilename";
          value = (rdbDbFilename != null && !rdbDbFilename.isEmpty()) ? rdbDbFilename : "dump.rdb";
        } else if (param.equals("appendonly")) {
          paramName = "appendonly";
          value = appendOnly;
        } else if (param.equals("appenddirname")) {
          paramName = "appenddirname";
          value = appendDirName;
        } else if (param.equals("appendfilename")) {
          paramName = "appendfilename";
          value = appendFilename;
        } else if (param.equals("appendfsync")) {
          paramName = "appendfsync";
          value = appendFsync;
        } else {
          out.write("*0\r\n".getBytes(StandardCharsets.UTF_8));
          out.flush();
          return;
        }
        byte[] nameBytes = paramName.getBytes(StandardCharsets.UTF_8);
        byte[] valBytes = value.getBytes(StandardCharsets.UTF_8);
        String response = "*2\r\n$" + nameBytes.length + "\r\n" + paramName + "\r\n$" + valBytes.length + "\r\n" + value + "\r\n";
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();
      } else {
        out.write("-ERR syntax error\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
      }
    } else if (command.equalsIgnoreCase("KEYS")) {
      // Stage 71: KEYS * returns all non-expired keys from the store
      String pattern = parts.length > 1 ? stripQuotes(parts[1]) : "*";
      List<String> keys = new ArrayList<>();
      for (Map.Entry<String, Entry> e : store.entrySet()) {
        if (e.getValue().isExpired()) {
          store.remove(e.getKey());
        } else if (matchesPattern(pattern, e.getKey())) {
          keys.add(e.getKey());
        }
      }
      StringBuilder sb = new StringBuilder();
      sb.append("*").append(keys.size()).append("\r\n");
      for (String k : keys) {
        byte[] kb = k.getBytes(StandardCharsets.UTF_8);
        sb.append("$").append(kb.length).append("\r\n").append(k).append("\r\n");
      }
      out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
      out.flush();
    }
  }

  private static void processReplicaCommand(String[] parts, OutputStream masterOut, OutputStream nullOut, long cmdStartOffset) throws IOException {
    if (parts.length >= 2 && parts[0].equalsIgnoreCase("REPLCONF") && parts[1].equalsIgnoreCase("GETACK")) {
      // Stage 65: Respond to REPLCONF GETACK * with REPLCONF ACK <cmdStartOffset>
      String offsetStr = String.valueOf(cmdStartOffset);
      String ack = "*3\r\n$8\r\nREPLCONF\r\n$3\r\nACK\r\n$" + offsetStr.length() + "\r\n" + offsetStr + "\r\n";
      masterOut.write(ack.getBytes(StandardCharsets.UTF_8));
      masterOut.flush();
    } else {
      handleCommand(parts, nullOut);
    }
  }

  private static long readSizeEncoded(InputStream in) throws IOException {
    int b = in.read();
    if (b == -1) throw new IOException("Unexpected EOF in size encoding");
    int type = (b & 0xC0) >> 6;
    if (type == 0) return b & 0x3F;
    if (type == 1) {
      int b2 = in.read();
      if (b2 == -1) throw new IOException("Unexpected EOF in size encoding");
      return ((b & 0x3F) << 8) | (b2 & 0xFF);
    }
    if (type == 2) {
      long val = 0;
      for (int i = 0; i < 4; i++) {
        int byteVal = in.read();
        if (byteVal == -1) throw new IOException("Unexpected EOF in size encoding");
        val = (val << 8) | (byteVal & 0xFF);
      }
      return val;
    }
    // type == 3: special integer encoding
    int sub = b & 0x3F;
    if (sub == 0) return in.read() & 0xFF;
    if (sub == 1) {
      int lo = in.read() & 0xFF;
      int hi = in.read() & 0xFF;
      return lo | (hi << 8);
    }
    if (sub == 2) {
      long v = 0;
      for (int i = 0; i < 4; i++) v |= (((long) (in.read() & 0xFF)) << (i * 8));
      return v;
    }
    throw new IOException("Unsupported RDB special encoding: " + sub);
  }

  private static String readRdbString(InputStream in) throws IOException {
    int b = in.read();
    if (b == -1) throw new IOException("Unexpected EOF in string read");
    int type = (b & 0xC0) >> 6;
    if (type == 3) {
      int sub = b & 0x3F;
      long val;
      if (sub == 0) {
        int v = in.read();
        if (v == -1) throw new IOException("Unexpected EOF in string int8");
        val = (byte) v;
      } else if (sub == 1) {
        int b1 = in.read();
        int b2 = in.read();
        if (b1 == -1 || b2 == -1) throw new IOException("Unexpected EOF in string int16");
        val = (short) ((b1 & 0xFF) | ((b2 & 0xFF) << 8));
      } else if (sub == 2) {
        long v = 0;
        for (int i = 0; i < 4; i++) {
          int byteVal = in.read();
          if (byteVal == -1) throw new IOException("Unexpected EOF in string int32");
          v |= (((long) (byteVal & 0xFF)) << (i * 8));
        }
        val = (int) v;
      } else {
        throw new IOException("Unsupported RDB integer encoding: " + sub);
      }
      return String.valueOf(val);
    }
    int len;
    if (type == 0) {
      len = b & 0x3F;
    } else if (type == 1) {
      int b2 = in.read();
      if (b2 == -1) throw new IOException("Unexpected EOF in string len14");
      len = ((b & 0x3F) << 8) | (b2 & 0xFF);
    } else {
      len = 0;
      for (int i = 0; i < 4; i++) {
        int byteVal = in.read();
        if (byteVal == -1) throw new IOException("Unexpected EOF in string len32");
        len = (len << 8) | (byteVal & 0xFF);
      }
    }
    byte[] bytes = in.readNBytes(len);
    if (bytes.length < len) {
      throw new IOException("Unexpected EOF reading string of length " + len);
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static void loadRdb() {
    if (rdbDbFilename == null || rdbDbFilename.isEmpty()) return;
    java.io.File rdbFile = (rdbDir != null && !rdbDir.isEmpty())
        ? new java.io.File(rdbDir, rdbDbFilename)
        : new java.io.File(rdbDbFilename);
    if (!rdbFile.exists() || !rdbFile.isFile()) {
      System.out.println("RDB file not found: " + rdbFile.getAbsolutePath());
      return;
    }
    try (InputStream in = new java.io.FileInputStream(rdbFile)) {
      // Skip 9-byte header: "REDIS" + 4-char version (e.g. "0011")
      byte[] header = in.readNBytes(9);
      System.out.println("RDB header: " + new String(header, StandardCharsets.UTF_8));

      while (true) {
        int opcode = in.read();
        if (opcode == -1) break;

        if (opcode == 0xFA) {
          // Auxiliary field: key + value strings — skip both
          readRdbString(in);
          readRdbString(in);
        } else if (opcode == 0xFE) {
          // SELECTDB: size-encoded DB index — skip
          readSizeEncoded(in);
        } else if (opcode == 0xFB) {
          // RESIZEDB: two size-encoded ints (hashtable sizes) — skip
          readSizeEncoded(in);
          readSizeEncoded(in);
        } else if (opcode == 0xFF) {
          // EOF marker — done (followed by 8-byte CRC64, but we stop here)
          break;
        } else if (opcode == 0xFC) {
          // Expiry in milliseconds (8-byte little-endian)
          long expiresAt = 0;
          for (int i = 0; i < 8; i++) {
            int byteVal = in.read();
            if (byteVal == -1) throw new IOException("Unexpected EOF in 0xFC expiry");
            expiresAt |= ((long) (byteVal & 0xFF)) << (i * 8);
          }
          int valueType = in.read();
          String key = readRdbString(in);
          String value = readRdbString(in);
          if (expiresAt <= System.currentTimeMillis()) {
            // Already expired — don't load
          } else {
            store.put(key, new Entry(value, expiresAt));
          }
        } else if (opcode == 0xFD) {
          // Expiry in seconds (4-byte little-endian)
          long expirySec = 0;
          for (int i = 0; i < 4; i++) {
            int byteVal = in.read();
            if (byteVal == -1) throw new IOException("Unexpected EOF in 0xFD expiry");
            expirySec |= ((long) (byteVal & 0xFF)) << (i * 8);
          }
          long expiresAt = expirySec * 1000L;
          int valueType = in.read();
          String key = readRdbString(in);
          String value = readRdbString(in);
          if (expiresAt <= System.currentTimeMillis()) {
            // Already expired — don't load
          } else {
            store.put(key, new Entry(value, expiresAt));
          }
        } else {
          // The opcode byte IS the value type (e.g., 0x00 = string)
          int valueType = opcode;
          String key = readRdbString(in);
          String value = readRdbString(in);
          store.put(key, new Entry(value, null));
        }
      }
      System.out.println("RDB loaded: " + store.size() + " keys");
    } catch (IOException e) {
      System.err.println("Failed to load RDB file: " + e.getMessage());
    }
  }

  public static void main(String[] args) {
    System.out.println("Logs from your program will appear here!");

    for (int i = 0; i < args.length; i++) {
      if ("--port".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
        try {
          port = Integer.parseInt(args[i + 1]);
        } catch (NumberFormatException e) {
          System.err.println("Invalid port number: " + args[i + 1]);
        }
        i++;
      } else if ("--replicaof".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
        role = "slave";
        String nextArg = args[i + 1];
        if (nextArg.trim().contains(" ")) {
          // Format: --replicaof "<HOST> <PORT>"
          String[] hostPort = nextArg.trim().split("\\s+");
          masterHost = hostPort[0];
          try {
            masterPort = Integer.parseInt(hostPort[1]);
          } catch (NumberFormatException e) {
            System.err.println("Invalid master port: " + hostPort[1]);
          }
          i++;
        } else if (i + 2 < args.length) {
          // Format: --replicaof <HOST> <PORT>
          masterHost = nextArg;
          try {
            masterPort = Integer.parseInt(args[i + 2]);
          } catch (NumberFormatException e) {
            System.err.println("Invalid master port: " + args[i + 2]);
          }
          i += 2;
        } else {
          masterHost = nextArg;
          i++;
        }
      } else if ("--dir".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
        rdbDir = stripQuotes(args[i + 1]);
        i++;
      } else if (args[i].toLowerCase().startsWith("--dir=")) {
        rdbDir = stripQuotes(args[i].substring("--dir=".length()));
      } else if ("--dbfilename".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
        rdbDbFilename = stripQuotes(args[i + 1]);
        i++;
      } else if (args[i].toLowerCase().startsWith("--dbfilename=")) {
        rdbDbFilename = stripQuotes(args[i].substring("--dbfilename=".length()));
      } else if ("--appendonly".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
        appendOnly = stripQuotes(args[i + 1]);
        i++;
      } else if (args[i].toLowerCase().startsWith("--appendonly=")) {
        appendOnly = stripQuotes(args[i].substring("--appendonly=".length()));
      } else if ("--appenddirname".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
        appendDirName = stripQuotes(args[i + 1]);
        i++;
      } else if (args[i].toLowerCase().startsWith("--appenddirname=")) {
        appendDirName = stripQuotes(args[i].substring("--appenddirname=".length()));
      } else if ("--appendfilename".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
        appendFilename = stripQuotes(args[i + 1]);
        i++;
      } else if (args[i].toLowerCase().startsWith("--appendfilename=")) {
        appendFilename = stripQuotes(args[i].substring("--appendfilename=".length()));
      } else if ("--appendfsync".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
        appendFsync = stripQuotes(args[i + 1]);
        i++;
      } else if (args[i].toLowerCase().startsWith("--appendfsync=")) {
        appendFsync = stripQuotes(args[i].substring("--appendfsync=".length()));
      }
    }

    // Stage 72: Load RDB file at startup
    loadRdb();

    // Stage 76 & 77: Create append-only directory and initial incremental file if enabled
    if ("yes".equalsIgnoreCase(appendOnly)) {
      String baseDir = (rdbDir != null && !rdbDir.isEmpty()) ? rdbDir : System.getProperty("user.dir");
      java.io.File aofDir = (appendDirName != null && !appendDirName.isEmpty())
          ? new java.io.File(baseDir, appendDirName)
          : new java.io.File(baseDir, "appendonlydir");
      if (!aofDir.exists()) {
        aofDir.mkdirs();
      }
      String baseFileName = (appendFilename != null && !appendFilename.isEmpty())
          ? appendFilename
          : "appendonly.aof";
      java.io.File incrFile = new java.io.File(aofDir, baseFileName + ".1.incr.aof");
      try {
        if (!incrFile.exists()) {
          incrFile.createNewFile();
        }
      } catch (java.io.IOException e) {
        System.err.println("Failed to create incremental AOF file: " + e.getMessage());
      }
    }

    try {
      ServerSocket serverSocket = new ServerSocket(port);
      serverSocket.setReuseAddress(true);

      if (masterHost != null && masterPort != -1) {
        final String host = masterHost;
        final int mPort = masterPort;
        new Thread(() -> {
          try {
            Socket socket = null;
            for (int attempt = 0; attempt < 5; attempt++) {
              try {
                socket = new Socket(host, mPort);
                break;
              } catch (IOException e) {
                try {
                  Thread.sleep(100);
                } catch (InterruptedException ignored) {
                  Thread.currentThread().interrupt();
                  break;
                }
              }
            }
            if (socket == null) {
              socket = new Socket(host, mPort);
            }

            masterSocket = socket;
            OutputStream masterOut = socket.getOutputStream();
            InputStream masterIn = socket.getInputStream();

            // Handshake Step 1: Send PING
            masterOut.write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.UTF_8));
            masterOut.flush();

            String response = readLine(masterIn);
            System.out.println("Master response to PING: " + response);

            // Handshake Step 2: Send REPLCONF listening-port <PORT>
            String portStr = String.valueOf(port);
            String replconfPort = "*3\r\n$8\r\nREPLCONF\r\n$14\r\nlistening-port\r\n$" + portStr.length() + "\r\n" + portStr + "\r\n";
            masterOut.write(replconfPort.getBytes(StandardCharsets.UTF_8));
            masterOut.flush();

            response = readLine(masterIn);
            System.out.println("Master response to REPLCONF listening-port: " + response);

            // Handshake Step 2 (cont.): Send REPLCONF capa psync2
            String replconfCapa = "*3\r\n$8\r\nREPLCONF\r\n$4\r\ncapa\r\n$6\r\npsync2\r\n";
            masterOut.write(replconfCapa.getBytes(StandardCharsets.UTF_8));
            masterOut.flush();

            response = readLine(masterIn);
            System.out.println("Master response to REPLCONF capa: " + response);

            // Handshake Step 3: Send PSYNC ? -1
            String psyncCmd = "*3\r\n$5\r\nPSYNC\r\n$1\r\n?\r\n$2\r\n-1\r\n";
            masterOut.write(psyncCmd.getBytes(StandardCharsets.UTF_8));
            masterOut.flush();

            response = readLine(masterIn);
            System.out.println("Master response to PSYNC: " + response);

            // Stage 63: Parse RDB file transfer from master
            String rdbHeader = readLine(masterIn);
            while (rdbHeader != null && !rdbHeader.startsWith("$")) {
              rdbHeader = readLine(masterIn);
            }
            if (rdbHeader != null && rdbHeader.startsWith("$")) {
              int rdbLen = Integer.parseInt(rdbHeader.substring(1).trim());
              byte[] rdbBytes = masterIn.readNBytes(rdbLen);
              System.out.println("Received RDB file: " + rdbBytes.length + " bytes");
            }

            // Stage 63 & 65: Enter continuous command processing loop with offset tracking
            CountingInputStream countingIn = new CountingInputStream(masterIn);
            OutputStream nullOut = OutputStream.nullOutputStream();
            while (true) {
              long cmdStartOffset = countingIn.getCount();
              String line = readLine(countingIn);
              if (line == null) {
                break; // Master disconnected
              }
              if (line.isEmpty()) {
                continue;
              }
              if (line.startsWith("*")) {
                int numArgs = Integer.parseInt(line.substring(1).trim());
                String[] parts = new String[numArgs];
                boolean complete = true;
                for (int i = 0; i < numArgs; i++) {
                  String lenLine = readLine(countingIn);
                  if (lenLine == null) {
                    complete = false;
                    break;
                  }
                  int argLen = Integer.parseInt(lenLine.substring(1).trim());
                  byte[] argBytes = countingIn.readNBytes(argLen);
                  parts[i] = new String(argBytes, StandardCharsets.UTF_8);
                  int cr = countingIn.read();
                  int lf = countingIn.read();
                  if (cr == -1 || lf == -1) {
                    complete = false;
                    break;
                  }
                }
                if (complete) {
                  try {
                    processReplicaCommand(parts, masterOut, nullOut, cmdStartOffset);
                  } catch (Exception e) {
                    System.err.println("Error processing propagated command: " + e.getMessage());
                  }
                }
              } else {
                String[] parts = line.split("\\s+");
                try {
                  processReplicaCommand(parts, masterOut, nullOut, cmdStartOffset);
                } catch (Exception e) {
                  System.err.println("Error processing inline command: " + e.getMessage());
                }
              }
            }
          } catch (IOException e) {
            System.err.println("Replication handshake failed: " + e.getMessage());
          }
        }).start();
      }

      while (true) {
        Socket clientSocket = serverSocket.accept();

        new Thread(() -> {
          ClientContext clientCtx = new ClientContext();
          OutputStream out = null;
          try {
            out = clientSocket.getOutputStream();
            InputStream in = clientSocket.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
            boolean[] inTx = new boolean[]{false};
            List<String[]> txQueue = new ArrayList<>();

            while (true) {
              String line = reader.readLine();
              if (line == null) break; // client disconnected
              line = line.trim();
              if (line.isEmpty()) continue;

              String[] parts;
              if (line.startsWith("*")) {
                int n = Integer.parseInt(line.substring(1).trim());
                parts = new String[n];
                boolean broken = false;
                for (int i = 0; i < n; i++) {
                  reader.readLine();            // Skip "$<len>"
                  parts[i] = reader.readLine(); // Payload
                  if (parts[i] == null) {
                    broken = true;
                    break;
                  }
                }
                if (broken) break;
              } else {
                parts = line.split("\\s+");
              }

              String command = parts[0];

              if (inTx[0]) {
                if (command.equalsIgnoreCase("EXEC")) {
                  inTx[0] = false;
                  synchronized (txExecutionLock) {
                    if (clientCtx.dirtyCas) {
                      unwatchAll(clientCtx);
                      txQueue.clear();
                      out.write("*-1\r\n".getBytes(StandardCharsets.UTF_8));
                      out.flush();
                      continue;
                    }
                    // Multiple concurrent transactions: emit array header and execute queued commands atomically
                    out.write(("*" + txQueue.size() + "\r\n").getBytes(StandardCharsets.UTF_8));
                    try {
                      for (String[] cmd : txQueue) {
                        handleCommand(cmd, out);
                      }
                    } finally {
                      unwatchAll(clientCtx);
                      out.flush();
                      txQueue.clear();
                    }
                  }
                  continue;
                } else if (command.equalsIgnoreCase("DISCARD")) {
                  inTx[0] = false;
                  txQueue.clear();
                  unwatchAll(clientCtx);
                  out.write("+OK\r\n".getBytes(StandardCharsets.UTF_8));
                  out.flush();
                  continue;
                } else if (command.equalsIgnoreCase("MULTI")) {
                  out.write("-ERR MULTI calls can not be nested\r\n".getBytes(StandardCharsets.UTF_8));
                  out.flush();
                  continue;
                } else if (command.equalsIgnoreCase("WATCH")) {
                  out.write("-ERR WATCH inside MULTI is not allowed\r\n".getBytes(StandardCharsets.UTF_8));
                  out.flush();
                  continue;
                } else {
                  txQueue.add(parts);
                  out.write("+QUEUED\r\n".getBytes(StandardCharsets.UTF_8));
                  out.flush();
                  continue;
                }
              }

              if (command.equalsIgnoreCase("EXEC")) {
                out.write("-ERR EXEC without MULTI\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
              } else if (command.equalsIgnoreCase("DISCARD")) {
                out.write("-ERR DISCARD without MULTI\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
              } else if (command.equalsIgnoreCase("MULTI")) {
                inTx[0] = true;
                out.write("+OK\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
              } else if (command.equalsIgnoreCase("WATCH")) {
                if (parts.length < 2) {
                  out.write("-ERR wrong number of arguments for 'watch' command\r\n".getBytes(StandardCharsets.UTF_8));
                  out.flush();
                } else {
                  for (int i = 1; i < parts.length; i++) {
                    String key = parts[i];
                    clientCtx.watchedKeys.add(key);
                    keyWatchers.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(clientCtx);
                  }
                  out.write("+OK\r\n".getBytes(StandardCharsets.UTF_8));
                  out.flush();
                }
              } else if (command.equalsIgnoreCase("UNWATCH")) {
                unwatchAll(clientCtx);
                out.write("+OK\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
              } else {
                handleCommand(parts, out);
                out.flush();
              }
            }
          } catch (IOException e) {
            System.out.println("IOException: " + e.getMessage());
          } finally {
            if (out != null) {
              replicas.remove(out);
              replicaAcks.remove(out);
              synchronized (waitLock) {
                waitLock.notifyAll();
              }
            }
            unwatchAll(clientCtx);
            try {
              clientSocket.close();
            } catch (IOException ignored) {}
          }
        }).start();
      }
    } catch (IOException e) {
      System.out.println("IOException: " + e.getMessage());
    }
  }
}
