import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
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

  static class Peer {
    final String ip;
    final int port;

    Peer(String ip, int port) {
      this.ip = ip;
      this.port = port;
    }
  }

  static class PeerMessage {
    final int id;
    final byte[] payload;

    PeerMessage(int id, byte[] payload) {
      this.id = id;
      this.payload = payload;
    }
  }

  static class MagnetLink {
    String infoHash;
    String trackerUrl;
    String exactTopic;
    String displayName;
  }

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

      List<Peer> peers = getPeers(announce, infoHashBytes, length);
      for (Peer p : peers) {
        System.out.println(p.ip + ":" + p.port);
      }
    } else if ("handshake".equals(command)) {
      String torrentFilePath = args[1];
      String peerAddress = args[2];
      int colonIdx = peerAddress.lastIndexOf(':');
      String peerIp = peerAddress.substring(0, colonIdx);
      int peerPort = Integer.parseInt(peerAddress.substring(colonIdx + 1));

      byte[] torrentBytes = Files.readAllBytes(Path.of(torrentFilePath));
      ByteBencodeParser parser = new ByteBencodeParser(torrentBytes);
      parser.parse();

      byte[] rawInfoBytes = parser.getRawInfoBytes();
      MessageDigest md = MessageDigest.getInstance("SHA-1");
      byte[] infoHashBytes = md.digest(rawInfoBytes);

      byte[] handshake = new byte[68];
      handshake[0] = 19;
      byte[] protocolBytes = "BitTorrent protocol".getBytes(StandardCharsets.ISO_8859_1);
      System.arraycopy(protocolBytes, 0, handshake, 1, 19);
      // bytes 20..27 are 8 zero reserved bytes
      System.arraycopy(infoHashBytes, 0, handshake, 28, 20);
      byte[] myPeerId = generatePeerId();
      System.arraycopy(myPeerId, 0, handshake, 48, 20);

      try (Socket socket = new Socket(peerIp, peerPort)) {
        socket.getOutputStream().write(handshake);
        socket.getOutputStream().flush();

        byte[] response = socket.getInputStream().readNBytes(68);
        if (response.length < 68) {
          throw new RuntimeException("Expected 68 bytes in handshake response, got: " + response.length);
        }
        byte[] peerId = Arrays.copyOfRange(response, 48, 68);
        System.out.println("Peer ID: " + bytesToHex(peerId));
      }
    } else if ("download_piece".equals(command)) {
      String outputPath = null;
      String torrentFilePath = null;
      int pieceIndex = -1;
      for (int i = 1; i < args.length; i++) {
        if ("-o".equals(args[i]) && i + 1 < args.length) {
          outputPath = args[++i];
        } else if (torrentFilePath == null) {
          torrentFilePath = args[i];
        } else {
          pieceIndex = Integer.parseInt(args[i]);
        }
      }

      byte[] torrentBytes = Files.readAllBytes(Path.of(torrentFilePath));
      ByteBencodeParser parser = new ByteBencodeParser(torrentBytes);
      @SuppressWarnings("unchecked")
      Map<String, Object> torrent = (Map<String, Object>) parser.parse();
      String announce = (String) torrent.get("announce");
      @SuppressWarnings("unchecked")
      Map<String, Object> info = (Map<String, Object>) torrent.get("info");
      long totalLength = (Long) info.get("length");
      long pieceLength = (Long) info.get("piece length");
      byte[] pieces = (byte[]) info.get("pieces");

      byte[] rawInfoBytes = parser.getRawInfoBytes();
      MessageDigest md = MessageDigest.getInstance("SHA-1");
      byte[] infoHashBytes = md.digest(rawInfoBytes);

      int totalPieces = (int) Math.ceil((double) totalLength / pieceLength);
      int pieceSize;
      if (pieceIndex == totalPieces - 1) {
        pieceSize = (int) (totalLength - (long) pieceIndex * pieceLength);
      } else {
        pieceSize = (int) pieceLength;
      }

      List<Peer> peers = getPeers(announce, infoHashBytes, totalLength);
      if (peers.isEmpty()) {
        throw new RuntimeException("No peers discovered from tracker");
      }

      byte[] pieceData = null;
      for (Peer peer : peers) {
        try {
          pieceData = downloadPieceFromPeer(peer, pieceIndex, pieceSize, infoHashBytes);
          if (pieceData != null) {
            break;
          }
        } catch (Exception e) {
          // Try next peer
        }
      }

      if (pieceData == null) {
        throw new RuntimeException("Failed to download piece " + pieceIndex + " from any peer");
      }

      // Verify SHA-1 hash of the piece
      byte[] expectedPieceHash = Arrays.copyOfRange(pieces, pieceIndex * 20, (pieceIndex + 1) * 20);
      byte[] actualPieceHash = MessageDigest.getInstance("SHA-1").digest(pieceData);
      if (!Arrays.equals(expectedPieceHash, actualPieceHash)) {
        throw new RuntimeException("Piece SHA-1 hash mismatch!");
      }

      Path outPath = Path.of(outputPath);
      if (outPath.getParent() != null) {
        Files.createDirectories(outPath.getParent());
      }
      Files.write(outPath, pieceData);
      System.out.println("Piece " + pieceIndex + " downloaded to " + outputPath + ".");
    } else if ("download".equals(command)) {
      String outputPath = null;
      String torrentFilePath = null;
      for (int i = 1; i < args.length; i++) {
        if ("-o".equals(args[i]) && i + 1 < args.length) {
          outputPath = args[++i];
        } else if (torrentFilePath == null) {
          torrentFilePath = args[i];
        }
      }

      byte[] torrentBytes = Files.readAllBytes(Path.of(torrentFilePath));
      ByteBencodeParser parser = new ByteBencodeParser(torrentBytes);
      @SuppressWarnings("unchecked")
      Map<String, Object> torrent = (Map<String, Object>) parser.parse();
      String announce = (String) torrent.get("announce");
      @SuppressWarnings("unchecked")
      Map<String, Object> info = (Map<String, Object>) torrent.get("info");
      long totalLength = (Long) info.get("length");
      long pieceLength = (Long) info.get("piece length");
      byte[] pieces = (byte[]) info.get("pieces");

      byte[] rawInfoBytes = parser.getRawInfoBytes();
      MessageDigest md = MessageDigest.getInstance("SHA-1");
      byte[] infoHashBytes = md.digest(rawInfoBytes);

      List<Peer> peers = getPeers(announce, infoHashBytes, totalLength);
      if (peers.isEmpty()) {
        throw new RuntimeException("No peers discovered from tracker");
      }

      byte[] fullFile = downloadFile(peers, infoHashBytes, totalLength, pieceLength, pieces);

      Path outPath = Path.of(outputPath);
      if (outPath.getParent() != null) {
        Files.createDirectories(outPath.getParent());
      }
      Files.write(outPath, fullFile);
      System.out.println("Downloaded " + torrentFilePath + " to " + outputPath + ".");
    } else if ("magnet_parse".equals(command)) {
      String magnetLink = args[1];
      MagnetLink parsed = parseMagnetLink(magnetLink);
      System.out.println("Tracker URL: " + parsed.trackerUrl);
      System.out.println("Info Hash: " + parsed.infoHash);
    } else if ("magnet_handshake".equals(command)) {
      String magnetLink = args[1];
      MagnetLink parsed = parseMagnetLink(magnetLink);
      byte[] infoHashBytes = hexToBytes(parsed.infoHash);

      List<Peer> peers = getPeers(parsed.trackerUrl, infoHashBytes, 999);
      if (peers.isEmpty()) {
        throw new RuntimeException("No peers discovered from tracker");
      }

      byte[] handshake = new byte[68];
      handshake[0] = 19;
      byte[] protocolBytes = "BitTorrent protocol".getBytes(StandardCharsets.ISO_8859_1);
      System.arraycopy(protocolBytes, 0, handshake, 1, 19);
      // 8 reserved bytes: set 20th bit from right (reserved[5] = 0x10 -> index 25)
      handshake[25] = 0x10;
      System.arraycopy(infoHashBytes, 0, handshake, 28, 20);
      byte[] myPeerId = generatePeerId();
      System.arraycopy(myPeerId, 0, handshake, 48, 20);

      boolean connected = false;
      for (Peer peer : peers) {
        try (Socket socket = new Socket(peer.ip, peer.port)) {
          socket.setSoTimeout(10000);
          OutputStream out = socket.getOutputStream();
          InputStream in = socket.getInputStream();

          out.write(handshake);
          out.flush();

          byte[] response = in.readNBytes(68);
          if (response.length < 68) {
            continue;
          }
          byte[] peerId = Arrays.copyOfRange(response, 48, 68);
          System.out.println("Peer ID: " + bytesToHex(peerId));

          // Check if peer supports extensions (bit 20 from right -> byte index 25, mask 0x10)
          boolean peerSupportsExtensions = (response[25] & 0x10) != 0;

          // Receive bitfield message if sent
          PeerMessage msg = readMessage(in);

          if (peerSupportsExtensions) {
            // Send extension handshake message:
            // Message ID: 20 (extension)
            // Payload: extension message ID: 0 (handshake), followed by bencoded dict: d1:md11:ut_metadatai16eee
            byte[] bencodedDict = "d1:md11:ut_metadatai16eee".getBytes(StandardCharsets.UTF_8);
            int extPayloadLen = 1 + bencodedDict.length;
            ByteBuffer extMsg = ByteBuffer.allocate(4 + 1 + extPayloadLen);
            extMsg.putInt(1 + extPayloadLen); // length prefix = 1 (msg id) + extPayloadLen
            extMsg.put((byte) 20); // message id = 20
            extMsg.put((byte) 0); // extension message id = 0 (handshake)
            extMsg.put(bencodedDict);

            out.write(extMsg.array());
            out.flush();
          }

          connected = true;
          break;
        } catch (Exception e) {
          // Try next peer
        }
      }
      if (!connected) {
        throw new RuntimeException("Failed to connect and handshake with any peer");
      }
    } else {
      System.out.println("Unknown command: " + command);
    }
  }

  static MagnetLink parseMagnetLink(String uri) {
    MagnetLink magnet = new MagnetLink();
    int qIndex = uri.indexOf('?');
    if (qIndex == -1) {
      return magnet;
    }
    String queryString = uri.substring(qIndex + 1);
    String[] params = queryString.split("&");
    for (String param : params) {
      int eqIndex = param.indexOf('=');
      if (eqIndex == -1) continue;
      String key = param.substring(0, eqIndex);
      String rawVal = param.substring(eqIndex + 1);
      String val = URLDecoder.decode(rawVal, StandardCharsets.UTF_8);
      if ("xt".equals(key)) {
        magnet.exactTopic = val;
        if (val.startsWith("urn:btih:")) {
          magnet.infoHash = val.substring("urn:btih:".length()).toLowerCase();
        }
      } else if ("tr".equals(key)) {
        magnet.trackerUrl = val;
      } else if ("dn".equals(key)) {
        magnet.displayName = val;
      }
    }
    return magnet;
  }

  static byte[] hexToBytes(String hex) {
    byte[] bytes = new byte[hex.length() / 2];
    for (int i = 0; i < bytes.length; i++) {
      int index = i * 2;
      bytes[i] = (byte) Integer.parseInt(hex.substring(index, index + 2), 16);
    }
    return bytes;
  }

  static byte[] generatePeerId() {
    long time = System.currentTimeMillis();
    String s = String.format("-PC0001-%012d", time % 1000000000000L);
    return s.getBytes(StandardCharsets.ISO_8859_1);
  }

  static byte[] downloadFile(List<Peer> peers, byte[] infoHashBytes, long totalLength, long pieceLength, byte[] pieces) throws Exception {
    int totalPieces = (int) Math.ceil((double) totalLength / pieceLength);
    byte[] fullFile = new byte[(int) totalLength];

    for (Peer peer : peers) {
      try (Socket socket = new Socket(peer.ip, peer.port)) {
        socket.setSoTimeout(15000);
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        // Handshake
        byte[] handshake = new byte[68];
        handshake[0] = 19;
        byte[] protocolBytes = "BitTorrent protocol".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(protocolBytes, 0, handshake, 1, 19);
        System.arraycopy(infoHashBytes, 0, handshake, 28, 20);
        byte[] myPeerId = generatePeerId();
        System.arraycopy(myPeerId, 0, handshake, 48, 20);

        out.write(handshake);
        out.flush();

        byte[] peerHandshake = in.readNBytes(68);
        if (peerHandshake.length < 68) {
          continue;
        }

        // Send interested
        byte[] interestedMsg = new byte[] { 0, 0, 0, 1, 2 };
        out.write(interestedMsg);
        out.flush();

        // Wait for unchoke
        while (true) {
          PeerMessage m = readMessage(in);
          if (m.id == 1) {
            break;
          }
        }

        boolean success = true;
        for (int pieceIndex = 0; pieceIndex < totalPieces; pieceIndex++) {
          int pieceSize = (pieceIndex == totalPieces - 1)
              ? (int) (totalLength - (long) pieceIndex * pieceLength)
              : (int) pieceLength;
          byte[] expectedPieceHash = Arrays.copyOfRange(pieces, pieceIndex * 20, (pieceIndex + 1) * 20);

          byte[] pieceData = downloadPieceOnSocket(out, in, pieceIndex, pieceSize);
          byte[] actualHash = MessageDigest.getInstance("SHA-1").digest(pieceData);
          if (!Arrays.equals(expectedPieceHash, actualHash)) {
            success = false;
            break;
          }
          System.arraycopy(pieceData, 0, fullFile, (int) (pieceIndex * pieceLength), pieceSize);
        }

        if (success) {
          return fullFile;
        }
      } catch (Exception e) {
        // Peer failed, try next peer
      }
    }

    throw new RuntimeException("Failed to download all pieces from any peer");
  }

  static byte[] downloadPieceFromPeer(Peer peer, int pieceIndex, int pieceSize, byte[] infoHashBytes) throws Exception {
    try (Socket socket = new Socket(peer.ip, peer.port)) {
      socket.setSoTimeout(15000);
      OutputStream out = socket.getOutputStream();
      InputStream in = socket.getInputStream();

      byte[] handshake = new byte[68];
      handshake[0] = 19;
      byte[] protocolBytes = "BitTorrent protocol".getBytes(StandardCharsets.ISO_8859_1);
      System.arraycopy(protocolBytes, 0, handshake, 1, 19);
      System.arraycopy(infoHashBytes, 0, handshake, 28, 20);
      byte[] myPeerId = generatePeerId();
      System.arraycopy(myPeerId, 0, handshake, 48, 20);

      out.write(handshake);
      out.flush();

      byte[] peerHandshake = in.readNBytes(68);
      if (peerHandshake.length < 68) {
        throw new IOException("Handshake failed: expected 68 bytes, got " + peerHandshake.length);
      }

      byte[] interestedMsg = new byte[] { 0, 0, 0, 1, 2 };
      out.write(interestedMsg);
      out.flush();

      while (true) {
        PeerMessage m = readMessage(in);
        if (m.id == 1) {
          break;
        }
      }

      return downloadPieceOnSocket(out, in, pieceIndex, pieceSize);
    }
  }

  static byte[] downloadPieceOnSocket(OutputStream out, InputStream in, int pieceIndex, int pieceSize) throws IOException {
    byte[] pieceData = new byte[pieceSize];
    int blockSize = 16384;
    int numBlocks = (int) Math.ceil((double) pieceSize / blockSize);

    for (int b = 0; b < numBlocks; b++) {
      int begin = b * blockSize;
      int blockLen = Math.min(blockSize, pieceSize - begin);

      ByteBuffer req = ByteBuffer.allocate(17);
      req.putInt(13); // length prefix: 13
      req.put((byte) 6); // message ID: 6 (request)
      req.putInt(pieceIndex);
      req.putInt(begin);
      req.putInt(blockLen);

      out.write(req.array());
      out.flush();

      PeerMessage pieceMsg;
      while (true) {
        pieceMsg = readMessage(in);
        if (pieceMsg.id == 7) {
          break;
        }
      }

      int pBegin = ByteBuffer.wrap(pieceMsg.payload, 4, 4).getInt();
      int dataLen = pieceMsg.payload.length - 8;
      System.arraycopy(pieceMsg.payload, 8, pieceData, pBegin, dataLen);
    }

    return pieceData;
  }

  static PeerMessage readMessage(InputStream in) throws IOException {
    while (true) {
      byte[] lenBytes = in.readNBytes(4);
      if (lenBytes.length < 4) {
        throw new IOException("Unexpected EOF reading message length");
      }
      int length = ((lenBytes[0] & 0xFF) << 24)
                 | ((lenBytes[1] & 0xFF) << 16)
                 | ((lenBytes[2] & 0xFF) << 8)
                 | (lenBytes[3] & 0xFF);
      if (length == 0) {
        // Keep-alive message
        continue;
      }
      int id = in.read();
      if (id == -1) {
        throw new IOException("Unexpected EOF reading message ID");
      }
      byte[] payload = in.readNBytes(length - 1);
      if (payload.length < length - 1) {
        throw new IOException("Unexpected EOF reading message payload");
      }
      return new PeerMessage(id, payload);
    }
  }

  static List<Peer> getPeers(String announce, byte[] infoHashBytes, long length) throws Exception {
    String peerId = new String(generatePeerId(), StandardCharsets.ISO_8859_1);
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

    List<Peer> peers = new ArrayList<>();
    Object peersObj = trackerResponse.get("peers");
    if (peersObj instanceof byte[]) {
      byte[] peersBytes = (byte[]) peersObj;
      for (int i = 0; i + 6 <= peersBytes.length; i += 6) {
        String ip = (peersBytes[i] & 0xFF) + "." + (peersBytes[i + 1] & 0xFF) + "."
                  + (peersBytes[i + 2] & 0xFF) + "." + (peersBytes[i + 3] & 0xFF);
        int port = ((peersBytes[i + 4] & 0xFF) << 8) | (peersBytes[i + 5] & 0xFF);
        peers.add(new Peer(ip, port));
      }
    } else if (peersObj instanceof List) {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> peersList = (List<Map<String, Object>>) peersObj;
      for (Map<String, Object> p : peersList) {
        peers.add(new Peer((String) p.get("ip"), ((Long) p.get("port")).intValue()));
      }
    }
    return peers;
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
