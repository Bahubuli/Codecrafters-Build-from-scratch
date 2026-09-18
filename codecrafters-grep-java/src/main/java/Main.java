import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

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

    static class PositiveGroupToken implements Token {
        private final Set<Character> chars;
        public PositiveGroupToken(String characters) {
            this.chars = new HashSet<>();
            for (char c : characters.toCharArray()) {
                this.chars.add(c);
            }
        }
        @Override
        public boolean matches(char c) {
            return chars.contains(c);
        }
    }

    static class NegativeGroupToken implements Token {
        private final Set<Character> chars;
        public NegativeGroupToken(String characters) {
            this.chars = new HashSet<>();
            for (char c : characters.toCharArray()) {
                this.chars.add(c);
            }
        }
        @Override
        public boolean matches(char c) {
            return !chars.contains(c);
        }
    }

    static List<Token> parseTokens(String pattern) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                if (i + 1 < pattern.length()) {
                    char next = pattern.charAt(i + 1);
                    if (next == 'd') {
                        tokens.add(new DigitToken());
                        i += 2;
                    } else if (next == 'w') {
                        tokens.add(new WordToken());
                        i += 2;
                    } else if (next == '\\') {
                        tokens.add(new LiteralToken('\\'));
                        i += 2;
                    } else {
                        tokens.add(new LiteralToken(next));
                        i += 2;
                    }
                } else {
                    tokens.add(new LiteralToken('\\'));
                    i++;
                }
            } else if (c == '[') {
                int closing = pattern.indexOf(']', i + 1);
                if (closing != -1) {
                    if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '^') {
                        String groupChars = pattern.substring(i + 2, closing);
                        tokens.add(new NegativeGroupToken(groupChars));
                    } else {
                        String groupChars = pattern.substring(i + 1, closing);
                        tokens.add(new PositiveGroupToken(groupChars));
                    }
                    i = closing + 1;
                } else {
                    tokens.add(new LiteralToken(c));
                    i++;
                }
            } else {
                tokens.add(new LiteralToken(c));
                i++;
            }
        }
        return tokens;
    }

    public static boolean matchPattern(String inputLine, String pattern) {
        boolean anchorStart = false;
        String activePattern = pattern;
        if (pattern.startsWith("^")) {
            anchorStart = true;
            activePattern = pattern.substring(1);
        }

        List<Token> tokens = parseTokens(activePattern);

        if (tokens.isEmpty()) {
            return true;
        }

        if (anchorStart) {
            return matchesAt(inputLine, 0, tokens, 0);
        }

        for (int start = 0; start <= inputLine.length(); start++) {
            if (matchesAt(inputLine, start, tokens, 0)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAt(String inputLine, int textIdx, List<Token> tokens, int tokenIdx) {
        if (tokenIdx == tokens.size()) {
            return true;
        }
        if (textIdx == inputLine.length()) {
            return false;
        }

        Token currentToken = tokens.get(tokenIdx);
        if (currentToken.matches(inputLine.charAt(textIdx))) {
            return matchesAt(inputLine, textIdx + 1, tokens, tokenIdx + 1);
        }
        return false;
    }
}
