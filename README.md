# greyson-competition

A standalone module that runs the same schema-less JSON tasks with **Greyson**,
**Jackson**, and **Gson**, side by side, so the comparison is executable rather
than rhetorical.

It is deliberately **not** part of the `json` build and is **never** published.
Jackson and Gson appear only here, so the core `json` module keeps its
zero-dependency footprint and clean CVE surface.

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

`PortfolioMappingComparisonTest` goes wider: it maps a noisy portfolio document
into a record graph (`Portfolio`/`Position`/`Instrument`) three ways — with
**Greyson**, **Jackson**, and **Gson** — and asserts all three produce equal
graphs. Its mapping rules (a coded `"%"` enum, field-derived `name`/`localCcy`
defaults, a context-dependent `valueLocal`, and array-or-single `positions`)
all fall outside reflective binding, so the Jackson and Gson versions both drop
to hand-rolled tree walks while Greyson stays explicit pointer/stream code.

## Running it

This module depends on the released `io.github.ralfspoeth:json:1.6.0`, so it
resolves straight from Maven Central:

```sh
cd greyson-competition
mvn test
```

Bump `<jackson.version>` in `pom.xml` to whatever Jackson release you want to
compare against.
