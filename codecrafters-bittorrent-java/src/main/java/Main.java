import com.google.gson.Gson;

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
    if (Character.isDigit(bencodedString.charAt(0))) {
      int firstColonIndex = bencodedString.indexOf(':');
      if (firstColonIndex == -1) {
        throw new RuntimeException("Invalid bencoded string: missing colon");
      }
      int length = Integer.parseInt(bencodedString.substring(0, firstColonIndex));
      return bencodedString.substring(firstColonIndex + 1, firstColonIndex + 1 + length);
    } else {
      throw new RuntimeException("Only strings are supported at the moment");
    }
  }
}
