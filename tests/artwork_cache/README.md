# Artwork-cache regression tests

The contract is bounded: transport exceptions, declared Content-Length mismatches,
unsolicited HTTP 206/Content-Range responses, and decoder-null results retain
usable stale bytes. A usable decode permits best-effort atomic replacement; a
cache-store failure still returns the usable new bitmap. This is **not complete
encoded-image validation**: BitmapFactory can accept partial encoded data. A
self-consistent HTTP body accepted by the existing decoder can still replace the
cache even if the encoded image is incomplete. Decoder/options/memory behavior
is deliberately preserved; there is no second decode or format validator.

Run from the repository root with `JAVA_HOME` pointing at JDK 17–21 and
`ANDROID_HOME` (or `ANDROID_SDK_ROOT`) pointing at an installed Android SDK:

```sh
python3 -m unittest discover -s tests -p test_artwork_cache.py -v
```

The runner compiles the actual `Images.java` and `Tmdb.java` against the SDK,
without extracting or copying any methods. Each case runs in its own JVM with
a temporary cache directory under ignored `work/artwork-cache-tests/`.
`Images.fetch` and `Tmdb.readBytes` perform real loopback HTTP and filesystem IO.
The raw HTTP fixture closes its socket at the intended byte boundary, including
premature EOF with an otherwise decodable PNG prefix. Requests are counted.
Explicit-partial cases use a PNG missing its IEND chunk that the JVM decoder
otherwise accepts: 206 with/without Content-Range and 200 with Content-Range must
retain old bytes/timestamps and return the old bitmap. The server waits for
client EOF to check connection release on each rejection branch. Valid chunked
and close-delimited bodies without Content-Length still promote; a chunked body
missing its final chunk retains stale bytes. These are host transport checks,
not proof of Android gzip/transparent-decompression behavior.

`BitmapFactory` uses genuine JVM ImageIO PNG decoding. `Bitmap` wraps the decoded
pixels; unused Android handler/cache/UI boundaries are stubs that fail if called.
The public Config stub has no credentials. This does not prove Android codec
behavior, RGB_565 quantization, GPU behavior, UI scheduling, or TV performance.

Disk-failure tests use JDK SecurityManager permission hooks to reject temporary
file creation/open or atomic promotion. Real files and `Files.move` remain in
use; no production injection seam or filesystem algorithm is substituted. The
hooks assert they fired, old bytes/timestamps survive, a new decoded bitmap is
returned, and temporary files are cleaned. This tests permission-denial paths,
not actual disk exhaustion or power-loss durability. If deletion itself is
refused, the test verifies the usable bitmap survives and explicitly removes
the unavoidable orphan only after lifting the denial. Existing startup prune
can reclaim abandoned staging files under its unchanged age/budget policy.

A deterministic concurrency case starts actual `prune` at the promotion
boundary, verifies it waits for the store, then verifies it finishes and leaves
the refreshed file intact. This is in-process coordination, not cross-process
locking or protection from OS cache eviction. Network and decoding do not hold
the disk lock. Ordinary TTL/budget pruning can still evict cache files.
Two further cases pause actual store at a file-write permission boundary or
actual prune inside listFiles, then require an already-initialized Images.init
call to complete before releasing disk IO. Latches and bounded joins exercise
the production path; the observation deadline is not measured device latency.

SecurityManager is deprecated on these JDKs and emits warnings; it is disabled
on newer JDK releases. The runner checks for it first: on a JDK without one, the
six cases that need it (the disk-fault ones, `prune_refresh` and `init_store`) are
reported as skipped and the rest still run. Use the stated JDK range to run them all. Tests need no downloaded libraries and contact no
external service. All sockets/threads and temporary test directories are closed.
