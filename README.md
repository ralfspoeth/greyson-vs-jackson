# greyson-vs-jackson

A standalone module that runs the same schema-less JSON tasks with **Greyson**
and with **Jackson**, side by side, so the comparison is executable rather than
rhetorical.

It is deliberately **not** part of the `json` build and is **never** published.
Jackson appears only here, so the core `json` module keeps its zero-dependency
footprint and clean CVE surface.

## What it shows

`RedactionComparisonTest` implements a recursive, schema-less redaction (mask
the string value of any sensitive key, at any depth) two ways:

- **Greyson** — a total `switch` over the sealed `JsonValue` hierarchy; the
  compiler proves every node shape is handled, and the result is a fresh
  immutable tree.
- **Jackson** — an `instanceof ObjectNode/ArrayNode/…` ladder with a catch-all
  `else`, over a mutable `JsonNode` tree.

The two results are cross-checked for agreement. A second test contrasts the
update models: Greyson's `Pointer.with` is immutable by construction and shares
off-path subtrees, whereas Jackson requires an explicit full `deepCopy()` to
avoid mutating the shared original.

## Running it

This module depends on `io.github.ralfspoeth:json:1.4.0-SNAPSHOT`, so install
the library into your local repository first:

```sh
# from the repository root (the 'json' module)
mvn install -DskipTests

# then run the comparison
cd greyson-vs-jackson
mvn test
```

Bump `<jackson.version>` in `pom.xml` to whatever Jackson release you want to
compare against.
