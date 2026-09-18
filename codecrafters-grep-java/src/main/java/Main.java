import java.io.IOException;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        if (args.length != 2 || !args[0].equals("-E")) {
            System.out.println("Usage: ./your_program.sh -E <pattern>");
            System.exit(1);
        }

        String pattern = args[1];
        Scanner scanner = new Scanner(System.in);
        String inputLine = scanner.nextLine();

        if (matchPattern(inputLine, pattern)) {
            System.exit(0);
        } else {
            System.exit(1);
        }
    }

    interface Token {
        boolean matches(char c);
    }

    static class LiteralToken implements Token {
        private final char ch;
        public LiteralToken(char ch) {
            this.ch = ch;
        }
        @Override
        public boolean matches(char c) {
            return this.ch == c;
        }
    }

    static class DigitToken implements Token {
        @Override
        public boolean matches(char c) {
            return Character.isDigit(c);
        }
    }

    static class WordToken implements Token {
        @Override
        public boolean matches(char c) {
            return Character.isLetterOrDigit(c) || c == '_';
        }
    }

    public static boolean matchPattern(String inputLine, String pattern) {
        Token token;
        if (pattern.equals("\\d")) {
            token = new DigitToken();
        } else if (pattern.equals("\\w")) {
            token = new WordToken();
        } else if (pattern.length() == 1) {
            token = new LiteralToken(pattern.charAt(0));
        } else {
            throw new RuntimeException("Unhandled pattern: " + pattern);
        }

        for (int i = 0; i < inputLine.length(); i++) {
            if (token.matches(inputLine.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
