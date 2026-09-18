import com.google.gson.Gson;
import java.util.ArrayList;
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
    } else {
      System.out.println("Unknown command: " + command);
    }
  }

  static Object decodeBencode(String bencodedString) {
    BencodeParser parser = new BencodeParser(bencodedString);
    return parser.parse();
  }

  static class BencodeParser {
    private final String src;
    private int index = 0;

    public BencodeParser(String src) {
      this.src = src;
    }

    public Object parse() {
      if (index >= src.length()) {
        throw new RuntimeException("Unexpected end of input");
      }
      char ch = src.charAt(index);
      if (Character.isDigit(ch)) {
        return parseString();
      } else if (ch == 'i') {
        return parseInteger();
      } else if (ch == 'l') {
        return parseList();
      } else if (ch == 'd') {
        return parseDictionary();
      } else {
        throw new RuntimeException("Unsupported bencode element starting with: " + ch);
      }
    }

    private String parseString() {
      int firstColonIndex = src.indexOf(':', index);
      if (firstColonIndex == -1) {
        throw new RuntimeException("Invalid bencoded string: missing colon");
      }
      int length = Integer.parseInt(src.substring(index, firstColonIndex));
      int start = firstColonIndex + 1;
      int end = start + length;
      if (end > src.length()) {
        throw new RuntimeException("Unexpected end of bencoded string");
      }
      index = end;
      return src.substring(start, end);
    }

    private Long parseInteger() {
      int endIndex = src.indexOf('e', index);
      if (endIndex == -1) {
        throw new RuntimeException("Invalid bencoded integer: missing 'e'");
      }
      String numStr = src.substring(index + 1, endIndex);
      index = endIndex + 1;
      return Long.parseLong(numStr);
    }

    private List<Object> parseList() {
      // Consume 'l'
      index++;
      List<Object> list = new ArrayList<>();
      while (index < src.length() && src.charAt(index) != 'e') {
        list.add(parse());
      }
      if (index >= src.length() || src.charAt(index) != 'e') {
        throw new RuntimeException("Invalid bencoded list: missing 'e'");
      }
      // Consume 'e'
      index++;
      return list;
    }

    private Map<String, Object> parseDictionary() {
      // Consume 'd'
      index++;
      Map<String, Object> map = new LinkedHashMap<>();
      while (index < src.length() && src.charAt(index) != 'e') {
        String key = parseString();
        Object value = parse();
        map.put(key, value);
      }
      if (index >= src.length() || src.charAt(index) != 'e') {
        throw new RuntimeException("Invalid bencoded dictionary: missing 'e'");
      }
      // Consume 'e'
      index++;
      return map;
    }
  }
}
