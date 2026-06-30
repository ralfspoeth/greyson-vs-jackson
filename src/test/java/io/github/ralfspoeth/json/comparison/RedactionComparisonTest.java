package io.github.ralfspoeth.json.comparison;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ralfspoeth.json.Greyson;
import io.github.ralfspoeth.json.data.Basic;
import io.github.ralfspoeth.json.data.JsonArray;
import io.github.ralfspoeth.json.data.JsonObject;
import io.github.ralfspoeth.json.data.JsonString;
import io.github.ralfspoeth.json.data.JsonValue;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static io.github.ralfspoeth.json.data.Builder.arrayBuilder;
import static io.github.ralfspoeth.json.data.Builder.objectBuilder;
import static io.github.ralfspoeth.json.query.Pointer.parse;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The same schema-less tasks done with Greyson and with Jackson, run side by
 * side so the contrast is executable rather than rhetorical. The two redactions
 * are cross-checked for agreement; the immutability test shows where the two
 * models genuinely diverge.
 */
class RedactionComparisonTest {

    private static final Set<String> SENSITIVE = Set.of("password", "token", "secret", "cardNumber");

    private static final String EXPORT = """
            {
              "id": 42,
              "profile": {
                "name": "Ada Lovelace",
                "email": "ada@example.com",
                "password": "hunter2",
                "addresses": [
                  {"kind": "home", "city": "London", "zip": "E1 6AN"},
                  {"kind": "work", "city": "Cambridge", "zip": "CB2 1TN"}
                ]
              },
              "payment": {
                "methods": [
                  {"type": "card", "cardNumber": "4111111111111111", "exp": "12/29"},
                  {"type": "card", "cardNumber": "5500005555555559", "exp": "01/30"}
                ]
              },
              "sessions": [
                {"id": "s-1", "token": "abc.def.ghi", "ip": "10.0.0.1"},
                {"id": "s-2", "token": "uvw.xyz.123", "ip": "10.0.0.2"}
              ],
              "metadata": {
                "version": 3,
                "tags": ["beta", "internal"],
                "audit": {"secret": "do-not-log", "createdBy": "system"}
              }
            }
            """;

    // ---- Greyson: a total sealed switch; the result is a fresh immutable tree.
    static JsonValue greysonRedact(JsonValue value, Predicate<String> sensitive) {
        return switch (value) {
            case JsonObject(var members) -> {
                var b = objectBuilder();
                members.forEach((key, val) -> b.put(key,
                        sensitive.test(key) && val instanceof JsonString
                                ? Basic.of("***")
                                : greysonRedact(val, sensitive)));
                yield b.build();
            }
            case JsonArray(var elements) -> {
                var b = arrayBuilder();
                elements.forEach(e -> b.add(greysonRedact(e, sensitive)));
                yield b.build();
            }
            case Basic<?> leaf -> leaf;
        };
    }

    // ---- Jackson: an instanceof ladder with a catch-all else; nodes are mutable.
    static JsonNode jacksonRedact(JsonNode node, Set<String> sensitive) {
        if (node instanceof ObjectNode obj) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                if (sensitive.contains(e.getKey()) && e.getValue().isTextual()) {
                    out.put(e.getKey(), "***");
                } else {
                    out.set(e.getKey(), jacksonRedact(e.getValue(), sensitive));
                }
            }
            return out;
        } else if (node instanceof ArrayNode arr) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (JsonNode e : arr) out.add(jacksonRedact(e, sensitive));
            return out;
        } else {
            return node; // value nodes are immutable, so reuse is safe
        }
    }

    @Test
    void bothRedactionsAgree() throws IOException {
        var mapper = new ObjectMapper();

        var greyson = greysonRedact(
                Greyson.readValue(Reader.of(EXPORT)).orElseThrow(), SENSITIVE::contains);
        var jackson = jacksonRedact(mapper.readTree(EXPORT), SENSITIVE);

        // Serialize the Greyson result and re-parse it with Jackson; JsonNode
        // object equality is order-independent, so this proves the two redactions
        // produce the same document.
        JsonNode greysonAsJackson = mapper.readTree(greyson.json());

        assertAll(
                () -> assertEquals(jackson, greysonAsJackson),
                () -> assertEquals("***", parse("profile/password").stringOrThrow(greyson)),
                () -> assertEquals("***", jackson.at("/profile/password").asText()),
                () -> assertEquals("ada@example.com", jackson.at("/profile/email").asText())
        );
    }

    @Test
    void immutabilityContrast() throws IOException {
        var mapper = new ObjectMapper();

        // Greyson: with() is immutable by construction and shares off-path subtrees.
        var doc = Greyson.readValue(Reader.of(EXPORT)).orElseThrow();
        var profileBefore = parse("profile").require(doc);
        var bumped = parse("metadata/version").with(doc, Basic.of(4));
        assertAll(
                () -> assertEquals(4, parse("metadata/version").intOrThrow(bumped)),
                () -> assertEquals(3, parse("metadata/version").intOrThrow(doc)),     // original intact
                () -> assertSame(profileBefore, parse("profile").require(bumped))     // subtree shared
        );

        // Jackson: nodes are mutable, so immutability needs an explicit full deepCopy().
        JsonNode root = mapper.readTree(EXPORT);
        ObjectNode copy = (ObjectNode) root.deepCopy();
        ((ObjectNode) copy.get("metadata")).put("version", 4);
        assertAll(
                () -> assertEquals(4, copy.at("/metadata/version").asInt()),
                () -> assertEquals(3, root.at("/metadata/version").asInt()),          // intact ONLY due to deepCopy
                () -> assertNotSame(root.get("profile"), copy.get("profile"))         // deepCopy shares nothing
        );

        // The footgun the deepCopy guards against: mutating in place changes the
        // original everyone else is holding.
        JsonNode shared = mapper.readTree(EXPORT);
        ((ObjectNode) shared.get("metadata")).put("version", 99);
        assertEquals(99, shared.at("/metadata/version").asInt());
    }
}
