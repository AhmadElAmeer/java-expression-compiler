package compiler;

import static compiler.Token.Kind.*;

import compiler.Token.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Turns source text into tokens. Whitespace and // comments are skipped. */
public final class Lexer {
    private static final Map<String, Kind> KEYWORDS = Map.ofEntries(
            Map.entry("fn", FN), Map.entry("let", LET), Map.entry("if", IF), Map.entry("else", ELSE),
            Map.entry("while", WHILE), Map.entry("return", RETURN), Map.entry("print", PRINT),
            Map.entry("true", TRUE), Map.entry("false", FALSE),
            Map.entry("int", INT_TYPE), Map.entry("bool", BOOL_TYPE));

    private final String src;
    private int i = 0, line = 1, col = 1;

    private Lexer(String src) {
        this.src = src;
    }

    public static List<Token> tokenize(String src) throws CompileError {
        return new Lexer(src).run();
    }

    private List<Token> run() throws CompileError {
        List<Token> out = new ArrayList<>();
        while (true) {
            skipSpaceAndComments();
            Pos pos = new Pos(line, col);
            if (i >= src.length()) {
                out.add(new Token(EOF, "", 0, pos));
                return out;
            }
            int start = i;
            char ch = src.charAt(i);
            if (isDigit(ch)) {
                while (i < src.length() && isDigit(src.charAt(i))) advance();
                if (i < src.length() && isIdentPart(src.charAt(i))) {
                    throw new CompileError(pos, "invalid number '" + src.substring(start, i + 1) + "'");
                }
                String text = src.substring(start, i);
                long value;
                try {
                    value = Long.parseLong(text);
                } catch (NumberFormatException e) {
                    throw new CompileError(pos, "integer literal too large: " + text);
                }
                out.add(new Token(INT, text, value, pos));
                continue;
            }
            if (isIdentStart(ch)) {
                while (i < src.length() && isIdentPart(src.charAt(i))) advance();
                String text = src.substring(start, i);
                out.add(new Token(KEYWORDS.getOrDefault(text, IDENT), text, 0, pos));
                continue;
            }
            advance();
            Kind kind = switch (ch) {
                case '(' -> LPAREN;
                case ')' -> RPAREN;
                case '{' -> LBRACE;
                case '}' -> RBRACE;
                case ',' -> COMMA;
                case ';' -> SEMI;
                case ':' -> COLON;
                case '+' -> PLUS;
                case '-' -> MINUS;
                case '*' -> STAR;
                case '/' -> SLASH;
                case '%' -> PERCENT;
                case '=' -> match('=') ? EQ : ASSIGN;
                case '!' -> match('=') ? NE : BANG;
                case '<' -> match('=') ? LE : LT;
                case '>' -> match('=') ? GE : GT;
                case '&' -> {
                    if (match('&')) yield AND;
                    throw new CompileError(pos, "expected '&&' (there is no bitwise '&')");
                }
                case '|' -> {
                    if (match('|')) yield OR;
                    throw new CompileError(pos, "expected '||' (there is no bitwise '|')");
                }
                default -> throw new CompileError(pos, "unexpected character '" + ch + "'");
            };
            out.add(new Token(kind, src.substring(start, i), 0, pos));
        }
    }

    private void skipSpaceAndComments() {
        while (i < src.length()) {
            char ch = src.charAt(i);
            if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') {
                advance();
            } else if (ch == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                while (i < src.length() && src.charAt(i) != '\n') advance();
            } else {
                return;
            }
        }
    }

    private boolean match(char expected) {
        if (i < src.length() && src.charAt(i) == expected) {
            advance();
            return true;
        }
        return false;
    }

    private void advance() {
        if (src.charAt(i) == '\n') {
            line++;
            col = 1;
        } else {
            col++;
        }
        i++;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || isDigit(c);
    }
}
