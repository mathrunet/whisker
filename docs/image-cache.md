# Managed image caching

`whisker-image::Image` accepts optional reactive `ImageCacheOptions`. Ordinary
images retain their platform loader policy. Opt-in images use an exact URL key,
a temporary original file named by SHA-256, and a shared decoded-image LRU.
HTTP headers are forwarded on a miss but never participate in the key.

The public decode-bound and control API lives in [Rustdoc](../packages/whisker-image/src/lib.rs).
Bounds determine the retained pixel dimensions independently of layout and fit.
Native decoders downsample where supported; the Desktop worker decodes and then
resizes before retaining the result.

Each URL has separate decoded variants for its requested bounds. All variants
share a 64 MiB default budget based on actual decoded storage (RGBA length on
Desktop, bitmap allocation on Android, row stride × height on Apple platforms).
Hits refresh recency. Insertion and budget reduction evict least recently used
entries. Oversized images are not retained; zero disables retention. Visible
image references, GPU surfaces and transient decode allocations are not part of
the budget. Decoders may need full-source memory temporarily; Desktop resizes
after decoding.

Originals live in an application temporary directory and have no expiration.
Desktop namespaces the OS temporary directory using the executable path. Cache
workers serialize loads and mutations off the UI thread. A file survives memory
eviction and restarts until the OS removes it. Downloads larger than 64 MiB and
HTTP/decode failures are not committed; a corrupt saved file is removed and
retried once. Await `remove_cached_image(url)` to delete the original and all
memory variants. Visible images remain visible after removal.

The internal `cache` string attribute contains the options plus `_src` and
`_headers`. A single attribute carries one coherent request independently of
Host property application order. Ordinary source/header attributes are empty
for managed images, preventing a second unmanaged load or cache.

Web accepts the component but delegates to the browser cache. File persistence,
requested decode retention sizes and byte limits cannot be enforced there.
Configuration/removal methods return errors on Web, and custom request headers
retain the existing browser-host restriction.
