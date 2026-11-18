package compiler;

public record Token(Token.Kind kind, String text, long value, Pos pos) {

    public enum Kind {
        INT, IDENT,
        FN, LET, IF, ELSE, WHILE, RETURN, PRINT, TRUE, FALSE, INT_TYPE, BOOL_TYPE,
        LPAREN, RPAREN, LBRACE, RBRACE, COMMA, SEMI, COLON, ASSIGN,
        PLUS, MINUS, STAR, SLASH, PERCENT, BANG,
        LT, LE, GT, GE, EQ, NE, AND, OR,
        EOF
    }

    /** How the token appears in source, for error messages. */
    public static String symbol(Kind k) {
        return switch (k) {
            case PLUS -> "+"; case MINUS -> "-"; case STAR -> "*"; case SLASH -> "/"; case PERCENT -> "%";
            case BANG -> "!"; case LT -> "<"; case LE -> "<="; case GT -> ">"; case GE -> ">=";
            case EQ -> "=="; case NE -> "!="; case AND -> "&&"; case OR -> "||";
            default -> k.name().toLowerCase();
        };
    }

    public String describe() {
        return kind == Kind.EOF ? "end of input" : "'" + text + "'";
    }
}
