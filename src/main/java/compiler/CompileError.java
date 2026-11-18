package compiler;

/** A lexing, parsing or type error, with the position it refers to. */
public final class CompileError extends Exception {
    public final Pos pos;
    public final String detail;

    public CompileError(Pos pos, String detail) {
        super(pos + ": " + detail);
        this.pos = pos;
        this.detail = detail;
    }

    /** The message plus the offending source line with a caret under the column. */
    public String render(String source) {
        String[] lines = source.split("\n", -1);
        String text = pos.line() - 1 < lines.length ? lines[pos.line() - 1] : "";
        StringBuilder caret = new StringBuilder();
        for (int i = 0; i < pos.col() - 1 && i < text.length(); i++) {
            caret.append(text.charAt(i) == '\t' ? '\t' : ' ');
        }
        return "error at " + pos + ": " + detail + "\n  " + text + "\n  " + caret + "^";
    }
}
