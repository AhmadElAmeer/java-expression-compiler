package compiler;

/** A 1-based line and column in the source text. */
public record Pos(int line, int col) {
    @Override
    public String toString() {
        return line + ":" + col;
    }
}
