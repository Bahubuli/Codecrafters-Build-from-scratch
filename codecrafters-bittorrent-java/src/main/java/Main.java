import com.google.gson.Gson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Main {
  private static final Gson gson = new Gson();

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.err.println("Usage: your_program.sh <command> <args>");
      System.exit(1);
    }

    String command = args[0];
    if ("decode".equals(command)) {
      String bencodedValue = args[1];
      Object decoded;
      try {
        decoded = decodeBencode(bencodedValue);
      } catch (RuntimeException e) {
        System.out.println(e.getMessage());
        return;
      }
      System.out.println(gson.toJson(decoded));
    } else if ("info".equals(command)) {
      String torrentFilePath = args[1];
      byte[] torrentBytes = Files.readAllBytes(Path.of(torrentFilePath));
      ByteBencodeParser parser = new ByteBencodeParser(torrentBytes);
      @SuppressWarnings("unchecked")
      Map<String, Object> torrent = (Map<String, Object>) parser.parse();
      String announce = (String) torrent.get("announce");
      @SuppressWarnings("unchecked")
      Map<String, Object> info = (Map<String, Object>) torrent.get("info");
      long length = (Long) info.get("length");

      byte[] rawInfoBytes = parser.getRawInfoBytes();
      MessageDigest md = MessageDigest.getInstance("SHA-1");
      byte[] infoHashBytes = md.digest(rawInfoBytes);
      String infoHash = bytesToHex(infoHashBytes);

      long pieceLength = (Long) info.get("piece length");
      byte[] pieces = (byte[]) info.get("pieces");

      System.out.println("Tracker URL: " + announce);
      System.out.println("Length: " + length);
      System.out.println("Info Hash: " + infoHash);
      System.out.println("Piece Length: " + pieceLength);
      System.out.println("Piece Hashes:");
      for (int i = 0; i < pieces.length; i += 20) {
        byte[] pieceHash = Arrays.copyOfRange(pieces, i, i + 20);
        System.out.println(bytesToHex(pieceHash));
      }
    } else if ("peers".equals(command)) {
      String torrentFilePath = args[1];
      byte[] torrentBytes = Files.readAllBytes(Path.of(torrentFilePath));
      ByteBencodeParser parser = new ByteBencodeParser(torrentBytes);
      @SuppressWarnings("unchecked")
      Map<String, Object> torrent = (Map<String, Object>) parser.parse();
      String announce = (String) torrent.get("announce");
      @SuppressWarnings("unchecked")
      Map<String, Object> info = (Map<String, Object>) torrent.get("info");
      long length = (Long) info.get("length");

      byte[] rawInfoBytes = parser.getRawInfoBytes();
      MessageDigest md = MessageDigest.getInstance("SHA-1");
      byte[] infoHashBytes = md.digest(rawInfoBytes);

      String peerId = "00112233445566778899";
      char separator = announce.contains("?") ? '&' : '?';
      String url = announce + separator
          + "info_hash=" + urlEncodeBytes(infoHashBytes)
          + "&peer_id=" + peerId
          + "&port=6881"
          + "&uploaded=0"
          + "&downloaded=0"
          + "&left=" + length
          + "&compact=1";

      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
      HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

      ByteBencodeParser respParser = new ByteBencodeParser(response.body());
      @SuppressWarnings("unchecked")
      Map<String, Object> trackerResponse = (Map<String, Object>) respParser.parse();

      Object peersObj = trackerResponse.get("peers");
      if (peersObj instanceof byte[]) {
        byte[] peersBytes = (byte[]) peersObj;
        for (int i = 0; i + 6 <= peersBytes.length; i += 6) {
          String ip = (peersBytes[i] & 0xFF) + "." + (peersBytes[i + 1] & 0xFF) + "."
                    + (peersBytes[i + 2] & 0xFF) + "." + (peersBytes[i + 3] & 0xFF);
          int port = ((peersBytes[i + 4] & 0xFF) << 8) | (peersBytes[i + 5] & 0xFF);
          System.out.println(ip + ":" + port);
        }
      } else if (peersObj instanceof List) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> peersList = (List<Map<String, Object>>) peersObj;
        for (Map<String, Object> p : peersList) {
          System.out.println(p.get("ip") + ":" + p.get("port"));
        }
      }
    } else {
      System.out.println("Unknown command: " + command);
    }
  }

  static Object decodeBencode(String bencodedString) {
    ByteBencodeParser parser = new ByteBencodeParser(bencodedString.getBytes(StandardCharsets.UTF_8));
    return parser.parse();
  }

  private static String bytesToHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

  private static String urlEncodeBytes(byte[] bytes) {
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) {
      char c = (char) (b & 0xFF);
      if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
          || c == '.' || c == '-' || c == '_' || c == '~') {
        sb.append(c);
      } else {
        sb.append(String.format("%%%02x", b & 0xFF));
      }
    }
    return sb.toString();
  }

  static class ByteBencodeParser {
    private final byte[] src;
    private int index = 0;
    private byte[] rawInfoBytes = null;

    public ByteBencodeParser(byte[] src) {
      this.src = src;
    }

    public byte[] getRawInfoBytes() {
      return rawInfoBytes;
    }

    public Object parse() {
      if (index >= src.length) {
        throw new RuntimeException("Unexpected end of input");
      }
      byte b = src[index];
      if (b >= '0' && b <= '9') {
        return new String(parseStringBytes(), StandardCharsets.UTF_8);
      } else if (b == 'i') {
        return parseInteger();
      } else if (b == 'l') {
        return parseList();
      } else if (b == 'd') {
        return parseDictionary();
      } else {
        throw new RuntimeException("Unsupported bencode element starting with byte: " + b);
      }
    }

    private byte[] parseStringBytes() {
      int colonIndex = -1;
      for (int i = index; i < src.length; i++) {
        if (src[i] == ':') {
          colonIndex = i;
          break;
        }
      }
      if (colonIndex == -1) {
        throw new RuntimeException("Invalid bencoded string: missing colon");
      }
      int length = Integer.parseInt(new String(src, index, colonIndex - index, StandardCharsets.US_ASCII));
      int start = colonIndex + 1;
      int end = start + length;
      if (end > src.length) {
        throw new RuntimeException("Unexpected end of bencoded string");
      }
      index = end;
      return Arrays.copyOfRange(src, start, end);
    }

    private Long parseInteger() {
      int endIndex = -1;
      for (int i = index + 1; i < src.length; i++) {
        if (src[i] == 'e') {
          endIndex = i;
          break;
        }
      }
      if (endIndex == -1) {
        throw new RuntimeException("Invalid bencoded integer: missing 'e'");
      }
      String numStr = new String(src, index + 1, endIndex - (index + 1), StandardCharsets.US_ASCII);
      index = endIndex + 1;
      return Long.parseLong(numStr);
    }

    private List<Object> parseList() {
      index++; // Consume 'l'
      List<Object> list = new ArrayList<>();
      while (index < src.length && src[index] != 'e') {
        list.add(parse());
      }
      if (index >= src.length || src[index] != 'e') {
        throw new RuntimeException("Invalid bencoded list: missing 'e'");
      }
      index++; // Consume 'e'
      return list;
    }

    private Map<String, Object> parseDictionary() {
      index++; // Consume 'd'
      Map<String, Object> map = new LinkedHashMap<>();
      while (index < src.length && src[index] != 'e') {
        byte[] keyBytes = parseStringBytes();
        String key = new String(keyBytes, StandardCharsets.UTF_8);
        int valStart = index;
        Object value;
        if ("pieces".equals(key) || "peers".equals(key)) {
          value = parseStringBytes();
        } else {
          value = parse();
        }
        int valEnd = index;
        if ("info".equals(key)) {
          this.rawInfoBytes = Arrays.copyOfRange(src, valStart, valEnd);
        }
        map.put(key, value);
      }
      if (index >= src.length || src[index] != 'e') {
        throw new RuntimeException("Invalid bencoded dictionary: missing 'e'");
      }
      index++; // Consume 'e'
      return map;
    }
  }
}
