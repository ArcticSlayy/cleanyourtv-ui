package android.os;
public final class Handler {
    public Handler(Looper looper) {}
    public boolean post(Runnable r) { throw new AssertionError("Unexpected UI post"); }
}
