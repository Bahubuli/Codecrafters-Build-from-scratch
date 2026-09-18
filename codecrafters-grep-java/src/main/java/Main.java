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

    static class WildcardToken implements Token {
        @Override
        public boolean matches(char c) {
            return c != '\n';
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

    enum Quantifier {
        EXACTLY_ONE,
        ONE_OR_MORE,
        ZERO_OR_ONE
    }

    static class PatternElement {
        final Token token;
        final Quantifier quantifier;

        PatternElement(Token token, Quantifier quantifier) {
            this.token = token;
            this.quantifier = quantifier;
        }
    }

    static List<PatternElement> parsePatternElements(String pattern) {
        List<Token> rawTokens = new ArrayList<>();
        List<Quantifier> quantifiers = new ArrayList<>();

        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            Token currentToken = null;

            if (c == '\\') {
                if (i + 1 < pattern.length()) {
                    char next = pattern.charAt(i + 1);
                    if (next == 'd') {
                        currentToken = new DigitToken();
                        i += 2;
                    } else if (next == 'w') {
                        currentToken = new WordToken();
                        i += 2;
                    } else if (next == '\\') {
                        currentToken = new LiteralToken('\\');
                        i += 2;
                    } else {
                        currentToken = new LiteralToken(next);
                        i += 2;
                    }
                } else {
                    currentToken = new LiteralToken('\\');
                    i++;
                }
            } else if (c == '.') {
                currentToken = new WildcardToken();
                i++;
            } else if (c == '[') {
                int closing = pattern.indexOf(']', i + 1);
                if (closing != -1) {
                    if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '^') {
                        String groupChars = pattern.substring(i + 2, closing);
                        currentToken = new NegativeGroupToken(groupChars);
                    } else {
                        String groupChars = pattern.substring(i + 1, closing);
                        currentToken = new PositiveGroupToken(groupChars);
                    }
                    i = closing + 1;
                } else {
                    currentToken = new LiteralToken(c);
                    i++;
                }
            } else {
                currentToken = new LiteralToken(c);
                i++;
            }

            Quantifier q = Quantifier.EXACTLY_ONE;
            if (i < pattern.length()) {
                char nextChar = pattern.charAt(i);
                if (nextChar == '+') {
                    q = Quantifier.ONE_OR_MORE;
                    i++;
                } else if (nextChar == '?') {
                    q = Quantifier.ZERO_OR_ONE;
                    i++;
                }
            }

            rawTokens.add(currentToken);
            quantifiers.add(q);
        }

        List<PatternElement> elements = new ArrayList<>();
        for (int j = 0; j < rawTokens.size(); j++) {
            elements.add(new PatternElement(rawTokens.get(j), quantifiers.get(j)));
        }
        return elements;
    }

    public static boolean matchPattern(String inputLine, String pattern) {
        boolean anchorStart = false;
        boolean anchorEnd = false;
        String activePattern = pattern;

        if (activePattern.startsWith("^")) {
            anchorStart = true;
            activePattern = activePattern.substring(1);
        }
        if (activePattern.endsWith("$")) {
            anchorEnd = true;
            activePattern = activePattern.substring(0, activePattern.length() - 1);
        }

        List<PatternElement> elements = parsePatternElements(activePattern);

        if (elements.isEmpty()) {
            return !anchorEnd || inputLine.isEmpty();
        }

        if (anchorStart) {
            return matchesAt(inputLine, 0, elements, 0, anchorEnd);
        }

        for (int start = 0; start <= inputLine.length(); start++) {
            if (matchesAt(inputLine, start, elements, 0, anchorEnd)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAt(String inputLine, int textIdx, List<PatternElement> elements, int elemIdx, boolean anchorEnd) {
        if (elemIdx == elements.size()) {
            if (anchorEnd) {
                return textIdx == inputLine.length();
            }
            return true;
        }

        PatternElement current = elements.get(elemIdx);

        if (current.quantifier == Quantifier.EXACTLY_ONE) {
            if (textIdx < inputLine.length() && current.token.matches(inputLine.charAt(textIdx))) {
                return matchesAt(inputLine, textIdx + 1, elements, elemIdx + 1, anchorEnd);
            }
            return false;
        } else if (current.quantifier == Quantifier.ONE_OR_MORE) {
            if (textIdx >= inputLine.length() || !current.token.matches(inputLine.charAt(textIdx))) {
                return false;
            }
            int maxMatch = textIdx;
            while (maxMatch < inputLine.length() && current.token.matches(inputLine.charAt(maxMatch))) {
                maxMatch++;
            }
            for (int end = maxMatch; end >= textIdx + 1; end--) {
                if (matchesAt(inputLine, end, elements, elemIdx + 1, anchorEnd)) {
                    return true;
                }
            }
            return false;
        } else if (current.quantifier == Quantifier.ZERO_OR_ONE) {
            if (textIdx < inputLine.length() && current.token.matches(inputLine.charAt(textIdx))) {
                if (matchesAt(inputLine, textIdx + 1, elements, elemIdx + 1, anchorEnd)) {
                    return true;
                }
            }
            return matchesAt(inputLine, textIdx, elements, elemIdx + 1, anchorEnd);
        }
        return false;
    }
}
