package android.util;
/** Unused UI memory cache: fail if fetch starts depending on it. */
public class LruCache<K, V> {
    public LruCache(int size) {}
    protected int sizeOf(K key, V value) { return 1; }
    public V get(K key) { throw new AssertionError("Unexpected memory-cache access"); }
    public V put(K key, V value) { throw new AssertionError("Unexpected memory-cache access"); }
    public void evictAll() { throw new AssertionError("Unexpected memory-cache access"); }
}
