package io.github.ralfspoeth.json.comparison;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.ralfspoeth.json.Greyson;
import static io.github.ralfspoeth.json.query.Pointer.self;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static io.github.ralfspoeth.json.query.Selector.all;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Mapping a domain record graph out of a noisy document, with mapping rules that
 * declarative binding cannot express:
 * <ul>
 *   <li>{@code valueLocal} is optional in JSON and defaults to {@code valueRef}
 *       when {@code instrument.ccy == portfolio.refCcy} (a sibling/parent rule);</li>
 *   <li>{@code Instrument.name} / {@code Portfolio.name} are required, defaulting
 *       to the {@code isin} / {@code id} when absent (a field-derived default);</li>
 *   <li>{@code quotation} is the literal {@code "%"} → {@link Quotation#PERCENT},
 *       or absent → {@link Quotation#PCS} (a coded value, not an enum name);</li>
 *   <li>{@code Position.localCcy} is optional and defaults to the instrument's
 *       {@code ccy} when absent (another field-derived default);</li>
 *   <li>{@code positions} may be a JSON array <em>or</em> a single bare object —
 *       Greyson's {@code Selector.all()} accepts either, whereas the Jackson walk
 *       needs an explicit array-vs-object branch ({@code for}-each over an
 *       {@code ObjectNode} iterates its field values, not the object).</li>
 * </ul>
 *
 * <p>In Greyson these are ordinary expressions in an explicit mapper. Jackson's
 * {@code treeToValue} can't express any of them without either annotating the
 * domain records (coupling them to Jackson — e.g. a {@code @JsonCreator} on the
 * enum for {@code "%"}) or post-processing, so the honest Jackson version maps
 * the whole graph by hand from {@code JsonNode}. At that point Jackson is just a
 * parser, and a more awkward one here: nullable {@code get(...)}, manual
 * {@code decimalValue()}, no {@code Optional} or typed accessors.</p>
 *
 * <p>Gson is in the same position: {@code gsonPortfolios} maps its
 * {@code JsonElement} tree by hand for the same reasons, so all three libraries
 * are compared on the identical document and asserted to produce equal graphs.</p>
 */
class PortfolioMappingComparisonTest {

    // ---- the domain (no Jackson annotations; names are required again) ----

    enum Quotation {PCS, PERCENT}

    record Instrument(String isin, String name, Currency ccy, Quotation quotation) {}

    record Position(Instrument instrument, long amount,
                    BigDecimal valueLocal, Currency localCcy,
                    BigDecimal valueRef, double percentage) {
        Position {
            valueLocal = valueLocal.stripTrailingZeros();
            valueRef = valueRef.stripTrailingZeros();
        }
    }

    record Portfolio(String id, String name, Currency refCcy, List<Position> positions) {}

    // ---- a realistic, noisy payload --------------------------------------
    // Names absent for the Deutsche Bank instrument and portfolio PF-2 (default
    // to isin / id). quotation is "%" only on the bond (PERCENT); elsewhere it
    // is absent (PCS). valueLocal omitted wherever ccy already equals refCcy.
    private static final String DOC = """
            {
              "generatedAt": "2026-06-29T10:00:00Z",
              "source": "custody-system",
              "page": {"index": 0, "size": 50, "total": 4},
              "warnings": [],
              "data": {
                "asOf": "2026-06-28",
                "portfolios": [
                  {
                    "id": "PF-1",
                    "name": "Growth",
                    "owner": "desk-emea",
                    "refCcy": "EUR",
                    "positions": [
                      {"instrument": {"isin": "US0378331005", "name": "Apple",
                                      "ccy": "USD", "sector": "Tech"},
                       "amount": 100, "valueLocal": 18250.00, "ccy":"USD"
                       , "valueRef": 16900.00, "percentage": 74.5},
                      {"instrument": {"isin": "DE0005140008", "ccy": "EUR"},
                       "amount": 500, "valueRef": 6250.00, "percentage": 25.5}
                    ]
                  },
                  {
                    "id": "PF-2",
                    "refCcy": "USD",
                    "positions": [
                      {"instrument": {"isin": "US912828U816", "name": "UST 2.25 2027",
                                      "ccy": "USD", "quotation": "%"},
                       "amount": 1000000, "valueRef": 921000.00, "percentage": 100.0}
                    ]
                  },
                  {
                    "id": "PF-3",
                    "name": "Multi",
                    "refCcy": "USD",
                    "positions": [
                      {"instrument": {"isin": "US1", "name": "AlphaCorp", "ccy": "USD"},
                       "amount": 10, "valueRef": 1000.00, "percentage": 20.0},
                      {"instrument": {"isin": "DE1", "name": "BetaAG", "ccy": "EUR"},
                       "amount": 20, "valueLocal": 2000.00, "valueRef": 2200.00, "percentage": 20.0},
                      {"instrument": {"isin": "GB1", "name": "GammaPlc", "ccy": "GBP"},
                       "amount": 30, "valueLocal": 3000.00, "localCcy": "GBP", "valueRef": 3500.00, "percentage": 20.0},
                      {"instrument": {"isin": "JP1", "name": "DeltaKK", "ccy": "JPY"},
                       "amount": 40, "valueLocal": 400000.00, "localCcy": "USD", "valueRef": 2700.00, "percentage": 20.0},
                      {"instrument": {"isin": "CH1", "name": "EpsilonSA", "ccy": "CHF", "quotation": "%"},
                       "amount": 50, "valueLocal": 5000.00, "localCcy": "CHF", "valueRef": 5400.00, "percentage": 20.0}
                    ]
                  },
                  {
                    "id": "PF-4",
                    "name": "Single",
                    "refCcy": "USD",
                    "positions": {"instrument": {"isin": "US9", "name": "ZetaInc", "ccy": "USD"},
                                  "amount": 5, "valueRef": 500.00, "percentage": 100.0}
                  }
                ]
              },
              "links": {"next": null, "self": "/portfolios?page=0"}
            }
            """;

    // ====================================================================
    //  Greyson: one pipeline, no helper methods. The reusable segment pointers
    //  are declared once up front (each is relative to the value it is applied
    //  to, so namePtr/isinPtr serve both portfolios and instruments), and the
    //  whole record graph is built inline in the .map(...) lambda.
    // ====================================================================

    static List<Portfolio> greysonPortfolios(String source) throws IOException {
        // Only the pointers reused more than once get a name; single-use ones
        // are inlined at their call site below.
        var idPtr = self().member("id");
        var refCcyPtr = self().member("refCcy");
        var valueRefPtr = self().member("valueRef");
        var instrumentPtr = self().member("instrument");
        var isinPtr = instrumentPtr.member("isin");
        var insCcyPtr = instrumentPtr.member("ccy");

        return Greyson.readValue(Reader.of(source))
                .stream()
                .flatMap(self().member("data").member("portfolios").select(all()))
                .map(pf -> new Portfolio(
                        idPtr.stringOrThrow(pf),
                        self().member("name").stringValue(pf).orElse(idPtr.stringOrThrow(pf)),    // default: the id
                        Currency.getInstance(refCcyPtr.stringOrThrow(pf)),
                        pf.get("positions").stream()
                                .flatMap(all())
                                .map(pos -> new Position(
                                        new Instrument(
                                                isinPtr.stringOrThrow(pos),
                                                // default: the isin
                                                instrumentPtr.member("name")
                                                        .stringValue(pos)
                                                        .or(() -> isinPtr.stringValue(pos))
                                                        .orElseThrow(),
                                                insCcyPtr.stringValue(pos).
                                                        map(Currency::getInstance)
                                                        .orElseThrow(),
                                                // "%" -> PERCENT, else PCS
                                                instrumentPtr.member("quotation")
                                                        .stringValue(pos)
                                                        .filter("%"::equals)
                                                        .map(_ -> Quotation.PERCENT)
                                                        .orElse(Quotation.PCS)
                                        ),
                                        self().member("amount").longOrThrow(pos),
                                        // default: valueRef when ccy == refCcy
                                        self().member("valueLocal")
                                                .decimalValue(pos)
                                                .or(() -> insCcyPtr.stringOrThrow(pos).equals(refCcyPtr.stringOrThrow(pf))
                                                        ? valueRefPtr.decimalValue(pos)
                                                        : Optional.empty())
                                                .orElseThrow(),
                                        // default: instrument ccy
                                        self().member("localCcy").stringValue(pos)
                                                .or(() -> insCcyPtr.stringValue(pos))
                                                .map(Currency::getInstance)
                                                .orElseThrow(),
                                        valueRefPtr.decimalOrThrow(pos),
                                        self().member("percentage").doubleOrThrow(pos))
                                ).toList()
                        )
                ).toList();
    }


    // ====================================================================
    //  Jackson: treeToValue is abandoned — the "%" code is not an enum name,
    //  and the isin/id name defaults aren't declarative. The whole graph is
    //  mapped by hand from JsonNode, keeping the domain records annotation-free.
    //  Only USE_BIG_DECIMAL_FOR_FLOATS remains, so decimalValue() keeps scale.
    // ====================================================================

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    static Instrument jacksonInstrument(JsonNode n) {
        String isin = n.get("isin").asText();
        JsonNode name = n.get("name");
        JsonNode q = n.get("quotation");
        return new Instrument(
                isin,
                (name != null && !name.isNull()) ? name.asText() : isin,
                Currency.getInstance(n.get("ccy").asText()),
                (q != null && !q.isNull() && "%".equals(q.asText())) ? Quotation.PERCENT : Quotation.PCS);
    }

    static Position jacksonPosition(JsonNode n, Currency refCcy) {
        Instrument instrument = jacksonInstrument(n.get("instrument"));
        BigDecimal valueRef = n.get("valueRef").decimalValue();
        JsonNode local = n.get("valueLocal");
        BigDecimal valueLocal;
        if (local != null && !local.isNull()) {
            valueLocal = local.decimalValue();
        } else if (instrument.ccy().equals(refCcy)) {
            valueLocal = valueRef;
        } else {
            throw new NoSuchElementException("valueLocal missing for " + instrument.isin());
        }
        JsonNode localCcy = n.get("localCcy");
        Currency ccy = (localCcy != null && !localCcy.isNull())
                ? Currency.getInstance(localCcy.asText())
                : instrument.ccy();                 // default: instrument ccy
        return new Position(instrument, n.get("amount").asLong(), valueLocal, ccy, valueRef,
                n.get("percentage").asDouble());
    }

    static Portfolio jacksonPortfolio(JsonNode n) {
        Currency refCcy = Currency.getInstance(n.get("refCcy").asText());
        JsonNode name = n.get("name");
        String id = n.get("id").asText();
        JsonNode positionsNode = n.get("positions");
        List<Position> positions = new ArrayList<>();
        // Greyson's Selector.all() treats a lone object as a one-element stream;
        // a Jackson for-each over an ObjectNode iterates its field values instead,
        // so the single-vs-array distinction needs an explicit branch here.
        if (positionsNode != null && positionsNode.isArray()) {
            for (JsonNode p : positionsNode) {
                positions.add(jacksonPosition(p, refCcy));
            }
        } else if (positionsNode != null && !positionsNode.isNull()) {
            positions.add(jacksonPosition(positionsNode, refCcy));
        }
        return new Portfolio(
                id,
                (name != null && !name.isNull()) ? name.asText() : id,
                refCcy,
                positions);
    }

    static List<Portfolio> jacksonPortfolios(String json) throws IOException {
        JsonNode portfolios = MAPPER.readTree(json).at("/data/portfolios");
        List<Portfolio> result = new ArrayList<>();
        for (JsonNode node : portfolios) {
            result.add(jacksonPortfolio(node));
        }
        return result;
    }

    // ====================================================================
    //  Gson: same story as Jackson — the "%" code, the field-derived name and
    //  localCcy defaults, and the array-or-object positions all sit outside
    //  reflective binding, so the graph is mapped by hand over Gson's
    //  JsonElement tree. get(name) returns null when absent; getAsBigDecimal
    //  keeps the literal scale (Position then strips it, as on the other sides).
    // ====================================================================

    static Instrument gsonInstrument(JsonObject n) {
        String isin = n.get("isin").getAsString();
        JsonElement name = n.get("name");
        JsonElement q = n.get("quotation");
        return new Instrument(
                isin,
                (name != null && !name.isJsonNull()) ? name.getAsString() : isin,
                Currency.getInstance(n.get("ccy").getAsString()),
                (q != null && !q.isJsonNull() && "%".equals(q.getAsString())) ? Quotation.PERCENT : Quotation.PCS);
    }

    static Position gsonPosition(JsonObject n, Currency refCcy) {
        Instrument instrument = gsonInstrument(n.getAsJsonObject("instrument"));
        BigDecimal valueRef = n.get("valueRef").getAsBigDecimal();
        JsonElement local = n.get("valueLocal");
        BigDecimal valueLocal;
        if (local != null && !local.isJsonNull()) {
            valueLocal = local.getAsBigDecimal();
        } else if (instrument.ccy().equals(refCcy)) {
            valueLocal = valueRef;
        } else {
            throw new NoSuchElementException("valueLocal missing for " + instrument.isin());
        }
        JsonElement localCcy = n.get("localCcy");
        Currency ccy = (localCcy != null && !localCcy.isJsonNull())
                ? Currency.getInstance(localCcy.getAsString())
                : instrument.ccy();                 // default: instrument ccy
        return new Position(instrument, n.get("amount").getAsLong(), valueLocal, ccy, valueRef,
                n.get("percentage").getAsDouble());
    }

    static Portfolio gsonPortfolio(JsonObject n) {
        Currency refCcy = Currency.getInstance(n.get("refCcy").getAsString());
        JsonElement name = n.get("name");
        String id = n.get("id").getAsString();
        JsonElement positions = n.get("positions");
        List<Position> list = new ArrayList<>();
        // Same single-vs-array branch the Jackson side needs.
        if (positions != null && positions.isJsonArray()) {
            for (JsonElement p : positions.getAsJsonArray()) {
                list.add(gsonPosition(p.getAsJsonObject(), refCcy));
            }
        } else if (positions != null && positions.isJsonObject()) {
            list.add(gsonPosition(positions.getAsJsonObject(), refCcy));
        }
        return new Portfolio(
                id,
                (name != null && !name.isJsonNull()) ? name.getAsString() : id,
                refCcy,
                list);
    }

    static List<Portfolio> gsonPortfolios(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonElement portfolios = root.getAsJsonObject("data").get("portfolios");
        List<Portfolio> result = new ArrayList<>();
        for (JsonElement p : portfolios.getAsJsonArray()) {
            result.add(gsonPortfolio(p.getAsJsonObject()));
        }
        return result;
    }

    // ====================================================================

    @Test
    void bothMapTheSamePortfolios() throws IOException {
        var greyson = greysonPortfolios(DOC);
        var jackson = jacksonPortfolios(DOC);
        var gson = gsonPortfolios(DOC);

        assertAll(
                () -> assertEquals(4, greyson.size()),
                // Position's constructor strips trailing zeros on all three sides, so
                // the record graphs compare equal directly — Greyson's normalized
                // scale and Jackson's/Gson's literal scale converge at construction.
                () -> assertEquals(greyson, jackson),
                () -> assertEquals(greyson, gson),
                () -> assertEquals("Growth", greyson.getFirst().name()),
                () -> assertEquals("PF-2", greyson.get(1).name()),            // name defaulted to id
                () -> {
                    var apple = greyson.getFirst().positions().getFirst();
                    assertEquals("Apple", apple.instrument().name());
                    assertEquals(Quotation.PCS, apple.instrument().quotation()); // quotation absent -> PCS
                    // by value: Greyson stores 18250.00 as 1.825E+4 (stripped scale)
                    assertEquals(0, new BigDecimal("18250.00").compareTo(apple.valueLocal()));
                },
                () -> {
                    var db = greyson.getFirst().positions().get(1);
                    assertEquals("DE0005140008", db.instrument().name());       // name defaulted to isin
                    assertEquals(Quotation.PCS, db.instrument().quotation());
                    assertEquals(db.valueRef(), db.valueLocal());               // valueLocal fallback (EUR==refCcy)
                },
                () -> {
                    var bond = greyson.get(1).positions().getFirst();
                    assertEquals(Quotation.PERCENT, bond.instrument().quotation()); // "%" -> PERCENT
                    assertEquals(bond.valueRef(), bond.valueLocal());               // fallback (USD==refCcy)
                },
                () -> assertEquals(5, greyson.get(2).positions().size()),
                () -> {
                    var pf3 = greyson.get(2);
                    // localCcy defaults to the instrument ccy when absent
                    assertEquals(Currency.getInstance("USD"), pf3.positions().getFirst().localCcy());
                    assertEquals(Currency.getInstance("EUR"), pf3.positions().get(1).localCcy());
                    // an explicit localCcy overrides — even when it differs from the instrument ccy
                    assertEquals(Currency.getInstance("JPY"), pf3.positions().get(3).instrument().ccy());
                    assertEquals(Currency.getInstance("USD"), pf3.positions().get(3).localCcy());
                },
                // PF-4's single position is not wrapped in an array
                () -> assertEquals(1, greyson.get(3).positions().size()),
                () -> assertEquals("ZetaInc",
                        greyson.get(3).positions().getFirst().instrument().name())
        );
    }

    @Test
    void zeroPortfoliosWhenThereAreNone() {
        var empty = """
                {"source": "x", "data": {"portfolios": []}, "links": {}}
                """;
        assertAll(
                () -> assertEquals(List.of(), greysonPortfolios(empty)),
                () -> assertEquals(List.of(), jacksonPortfolios(empty)),
                () -> assertEquals(List.of(), gsonPortfolios(empty))
        );
    }
}
