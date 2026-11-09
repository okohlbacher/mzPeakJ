package org.mzpeak.mzml;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mzpeak.model.CvTerms;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.StringReader;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * MZ-15: {@link MzMLTerms}, the term names, value typing and formatting, id rules and XML repairs of the mzML
 * converter (mzML design 5.2, 5.3, 12.2). Where a rule stands in for the JDK's XML Schema validator (xs:ID, xs:anyURI,
 * xs:dateTime, the spectrum id pattern), its answers are compared with that validator on a test-local schema.
 */
class MzMLTermsTest {

    /** Test-local schema: one attribute per checked type, the spectrum id pattern copied from mzML1.1.0.xsd L830. */
    private static final String XSD = "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\""
            + " targetNamespace=\"urn:t\" elementFormDefault=\"qualified\"><xs:element name=\"r\"><xs:complexType>"
            + "<xs:attribute name=\"uri\" type=\"xs:anyURI\"/>"
            + "<xs:attribute name=\"time\" type=\"xs:dateTime\"/>"
            + "<xs:attribute name=\"id\" type=\"xs:ID\"/>"
            + "<xs:attribute name=\"spectrum\"><xs:simpleType><xs:restriction base=\"xs:string\">"
            + "<xs:pattern value=\"\\S+=\\S+( \\S+=\\S+)*\"/></xs:restriction></xs:simpleType></xs:attribute>"
            + "</xs:complexType></xs:element></xs:schema>";

    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        schema = factory.newSchema(new StreamSource(new StringReader(XSD)));
    }

    /** Whether the JDK's validator accepts {@code value} as the attribute {@code attribute} of the test schema. */
    private static boolean jdkAccepts(String attribute, String value) {
        StringBuilder escaped = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '"' -> escaped.append("&quot;");
                case '\t' -> escaped.append("&#9;");
                case '\n' -> escaped.append("&#10;");
                case '\r' -> escaped.append("&#13;");
                default -> escaped.append(c);
            }
        }
        String document = "<r xmlns=\"urn:t\" " + attribute + "=\"" + escaped + "\"/>";
        try {
            schema.newValidator().validate(new StreamSource(new StringReader(document)));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Object value(String text) {
        return MzMLTerms.value(text, null).value();
    }

    // ---- (a) value typing ---------------------------------------------------------------------------------------

    @Test
    void untypedTextIsTypedByContent() {
        assertThat(value("1")).isEqualTo(1L);
        assertThat(value("007")).isEqualTo(7L);
        assertThat(value("+5")).isEqualTo(5L);
        assertThat(value("-3")).isEqualTo(-3L);
        assertThat(value("-9223372036854775808")).isEqualTo(Long.MIN_VALUE);
        assertThat(value("1.5245068e07")).isEqualTo(1.5245068E7);
        assertThat(value("35.0")).isEqualTo(35.0);
        assertThat(value(".5")).isEqualTo(0.5);
        assertThat(value("1.")).isEqualTo(1.0);
        assertThat(value("1e5")).isEqualTo(1e5);
        assertThat(value("-2.5E-3")).isEqualTo(-2.5E-3);
        assertThat(value("true")).isEqualTo(Boolean.TRUE);
        assertThat(value("false")).isEqualTo(Boolean.FALSE);
        assertThat(value("abc")).isEqualTo("abc");
        assertThat(value("INF")).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(value("-INF")).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(value("NaN")).isEqualTo(Double.NaN);
    }

    @Test
    void untypedTextThatIsNoNumberOrBooleanStaysAString() {
        assertThat(value("12345678901234567890")).isEqualTo("12345678901234567890");
        assertThat(value("Infinity")).isEqualTo("Infinity");
        assertThat(value("+INF")).isEqualTo("+INF");
        assertThat(value("TRUE")).isEqualTo("TRUE");
        assertThat(value("1 ")).isEqualTo("1 ");
        assertThat(value("1.2.3")).isEqualTo("1.2.3");
        assertThat(value("e5")).isEqualTo("e5");
        assertThat(value("\u0661")).isEqualTo("\u0661"); // ARABIC-INDIC DIGIT ONE: not an ASCII number
        assertThat(value("1.5f")).isEqualTo("1.5f");
        assertThat(value("0x10")).isEqualTo("0x10");
    }

    @Test
    void anEmptyOrAbsentValueIsNullWithOrWithoutAType() {
        assertThat(MzMLTerms.value("", null)).isEqualTo(new MzMLTerms.Typed(null, false));
        assertThat(MzMLTerms.value(null, null)).isEqualTo(new MzMLTerms.Typed(null, false));
        assertThat(MzMLTerms.value("", "xsd:int")).isEqualTo(new MzMLTerms.Typed(null, false));
        assertThat(MzMLTerms.value(null, "xsd:string")).isEqualTo(new MzMLTerms.Typed(null, false));
    }

    @Test
    void untypedTextNeverReportsATypeMismatch() {
        for (String text : List.of("1", "abc", "12345678901234567890", "Infinity", "NaN", "true")) {
            assertThat(MzMLTerms.value(text, null).typeMismatch()).as(text).isFalse();
        }
    }

    @Test
    void aDeclaredTypeDecidesTheValueClass() {
        assertThat(MzMLTerms.value("0", "xsd:float")).isEqualTo(new MzMLTerms.Typed(0.0, false));
        assertThat(MzMLTerms.value("007", "xsd:string")).isEqualTo(new MzMLTerms.Typed("007", false));
        assertThat(MzMLTerms.value("true", "xsd:string")).isEqualTo(new MzMLTerms.Typed("true", false));
        assertThat(MzMLTerms.value("1", "xs:normalizedString")).isEqualTo(new MzMLTerms.Typed("1", false));
        assertThat(MzMLTerms.value("INF", "xsd:double"))
                .isEqualTo(new MzMLTerms.Typed(Double.POSITIVE_INFINITY, false));
        assertThat(MzMLTerms.value("-INF", "xsd:decimal"))
                .isEqualTo(new MzMLTerms.Typed(Double.NEGATIVE_INFINITY, false));
        assertThat(MzMLTerms.value("NaN", "xsd:float")).isEqualTo(new MzMLTerms.Typed(Double.NaN, false));
        assertThat(MzMLTerms.value("1.5", "xs:double")).isEqualTo(new MzMLTerms.Typed(1.5, false));
        assertThat(MzMLTerms.value("2e3", "double")).isEqualTo(new MzMLTerms.Typed(2000.0, false));
        assertThat(MzMLTerms.value("1", "xsd:boolean")).isEqualTo(new MzMLTerms.Typed(Boolean.TRUE, false));
        assertThat(MzMLTerms.value("0", "xsd:boolean")).isEqualTo(new MzMLTerms.Typed(Boolean.FALSE, false));
        assertThat(MzMLTerms.value("true", "xsd:boolean")).isEqualTo(new MzMLTerms.Typed(Boolean.TRUE, false));
        assertThat(MzMLTerms.value("false", "boolean")).isEqualTo(new MzMLTerms.Typed(Boolean.FALSE, false));
        for (String type : List.of("xsd:int", "xsd:integer", "xsd:long", "xsd:short", "xsd:byte", "int",
                "xsd:nonNegativeInteger", "xsd:positiveInteger", "xsd:unsignedInt", "xsd:unsignedByte")) {
            assertThat(MzMLTerms.value("42", type)).as(type).isEqualTo(new MzMLTerms.Typed(42L, false));
        }
        assertThat(MzMLTerms.value("-007", "xsd:long")).isEqualTo(new MzMLTerms.Typed(-7L, false));
    }

    @Test
    void textThatDoesNotFitItsDeclaredTypeStaysAStringWithTheWarningFlag() {
        assertThat(MzMLTerms.value("x", "xsd:int")).isEqualTo(new MzMLTerms.Typed("x", true));
        assertThat(MzMLTerms.value("1.5", "xsd:int")).isEqualTo(new MzMLTerms.Typed("1.5", true));
        assertThat(MzMLTerms.value("12345678901234567890", "xsd:unsignedLong"))
                .isEqualTo(new MzMLTerms.Typed("12345678901234567890", true));
        assertThat(MzMLTerms.value("abc", "xsd:double")).isEqualTo(new MzMLTerms.Typed("abc", true));
        assertThat(MzMLTerms.value("Infinity", "xsd:float")).isEqualTo(new MzMLTerms.Typed("Infinity", true));
        assertThat(MzMLTerms.value("yes", "xsd:boolean")).isEqualTo(new MzMLTerms.Typed("yes", true));
    }

    @Test
    void anOtherDeclaredTypeGivesAStringWithoutTheWarningFlag() {
        assertThat(MzMLTerms.value("2005-07-20T19:44:22Z", "xsd:dateTime"))
                .isEqualTo(new MzMLTerms.Typed("2005-07-20T19:44:22Z", false));
        assertThat(MzMLTerms.value("42", "xsd:anyURI")).isEqualTo(new MzMLTerms.Typed("42", false));
        assertThat(MzMLTerms.value("1.5", "")).isEqualTo(new MzMLTerms.Typed("1.5", false));
    }

    // ---- (b) formatting -----------------------------------------------------------------------------------------

    @Test
    void valuesAreFormattedForMzML() {
        assertThat(MzMLTerms.format(1.5245068E7)).isEqualTo("1.5245068E7");
        assertThat(MzMLTerms.format(35.0)).isEqualTo("35.0");
        assertThat(MzMLTerms.format(Double.POSITIVE_INFINITY)).isEqualTo("INF");
        assertThat(MzMLTerms.format(Double.NEGATIVE_INFINITY)).isEqualTo("-INF");
        assertThat(MzMLTerms.format(Double.NaN)).isEqualTo("NaN");
        assertThat(MzMLTerms.format(Float.POSITIVE_INFINITY)).isEqualTo("INF");
        assertThat(MzMLTerms.format(0.1f)).isEqualTo(Double.toString((double) 0.1f));
        assertThat(MzMLTerms.format(35L)).isEqualTo("35");
        assertThat(MzMLTerms.format(35)).isEqualTo("35");
        assertThat(MzMLTerms.format((short) -3)).isEqualTo("-3");
        assertThat(MzMLTerms.format((byte) 3)).isEqualTo("3");
        assertThat(MzMLTerms.format(Boolean.TRUE)).isEqualTo("true");
        assertThat(MzMLTerms.format(Boolean.FALSE)).isEqualTo("false");
        assertThat(MzMLTerms.format("007")).isEqualTo("007");
        assertThat(MzMLTerms.format(null)).isNull();
    }

    @Test
    void userParamTypesAreTheFourOfTheModel() {
        assertThat(MzMLTerms.userParamType(35L)).isEqualTo("xsd:integer");
        assertThat(MzMLTerms.userParamType(35)).isEqualTo("xsd:integer");
        assertThat(MzMLTerms.userParamType((short) 3)).isEqualTo("xsd:integer");
        assertThat(MzMLTerms.userParamType((byte) 3)).isEqualTo("xsd:integer");
        assertThat(MzMLTerms.userParamType(1.5)).isEqualTo("xsd:double");
        assertThat(MzMLTerms.userParamType(1.5f)).isEqualTo("xsd:double");
        assertThat(MzMLTerms.userParamType(Boolean.TRUE)).isEqualTo("xsd:boolean");
        assertThat(MzMLTerms.userParamType("x")).isEqualTo("xsd:string");
        assertThat(MzMLTerms.userParamType(BigInteger.TEN)).isEqualTo("xsd:string");
        assertThat(MzMLTerms.userParamType(null)).isNull();
    }

    @Test
    void formattedValuesAreTypedBackToTheSameValue() {
        List<Object> values = List.of(7L, -3L, 1.5, 1e-300, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.NaN, Boolean.TRUE, Boolean.FALSE, 0.0, -0.0, 1e10, Double.MIN_VALUE, Double.MAX_VALUE,
                Long.MAX_VALUE, Long.MIN_VALUE, 0L);
        for (Object x : values) {
            assertThat(MzMLTerms.value(MzMLTerms.format(x), null).value()).as("untyped %s", x).isEqualTo(x);
            assertThat(MzMLTerms.value(MzMLTerms.format(x), MzMLTerms.userParamType(x)))
                    .as("typed %s", x).isEqualTo(new MzMLTerms.Typed(x, false));
        }
        assertThat(MzMLTerms.value(MzMLTerms.format("007"), MzMLTerms.userParamType("007")).value())
                .isEqualTo("007");
    }

    // ---- (c) NCName encoding ------------------------------------------------------------------------------------

    @Test
    void charactersNotAllowedAtTheirPositionInAnNCNameAreEncoded() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("test/data/diaPASEF.d", "test_x002f_data_x002f_diaPASEF.d");
        cases.put("RAW1", "RAW1");
        cases.put("1abc", "_x0031_abc");
        cases.put("a b", "a_x0020_b");
        cases.put("a:b", "a_x003a_b");
        cases.put("-a.b-c", "_x002d_a.b-c");
        cases.put(".a", "_x002e_a");
        cases.put("_x", "_x");
        cases.put("M\u00fcller", "M\u00fcller");
        cases.put("\u00b7a\u00b7", "_x00b7_a\u00b7");
        cases.put("\u0218", "_x0218_"); // valid in XML 1.0 5th edition names, not in the XML Schema 1.0 NCName
        cases.put("a\ud83d\ude00", "a_xd83d__xde00_");
        cases.put("LCQ Deca", "LCQ_x0020_Deca");
        for (Map.Entry<String, String> e : cases.entrySet()) {
            String encoded = MzMLTerms.ncName(e.getKey());
            assertThat(encoded).as(e.getKey()).isEqualTo(e.getValue());
            assertThat(jdkAccepts("id", encoded)).as(encoded).isTrue();
        }
        assertThat(jdkAccepts("id", "\u0218")).isFalse();
        assertThat(MzMLTerms.ncName("")).isEmpty();
    }

    @Test
    void ncNameKeepsExactlyTheCharactersTheJdkAcceptsInAnXmlName() throws Exception {
        // the DOM's element name check uses the JDK's XML 1.0 name tables, as its xs:ID check does (an NCName is a
        // Name without ':'); one createElement per character is fast enough for the whole BMP
        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        List<String> disagreements = new ArrayList<>();
        for (int c = 0; c <= 0xFFFF; c++) {
            String s = String.valueOf((char) c);
            boolean start = c != ':' && isXmlName(document, s);
            boolean part = c != ':' && isXmlName(document, "a" + s);
            if (MzMLTerms.ncName(s).equals(s) != start || MzMLTerms.ncName("a" + s).equals("a" + s) != part) {
                disagreements.add(String.format("U+%04X", c));
            }
        }
        assertThat(disagreements).isEmpty();
    }

    private static boolean isXmlName(Document document, String name) {
        try {
            document.createElement(name);
            return true;
        } catch (DOMException e) {
            return false;
        }
    }

    // ---- (d) unique and repaired ids ----------------------------------------------------------------------------

    @Test
    void uniqueIdAppendsTheFirstFreeSuffixAndTakesTheId() {
        Set<String> taken = new HashSet<>(Set.of("a_x002f_b"));
        assertThat(MzMLTerms.uniqueId("a_x002f_b", taken)).isEqualTo("a_x002f_b_2");
        assertThat(taken).containsExactlyInAnyOrder("a_x002f_b", "a_x002f_b_2");

        taken = new HashSet<>(Set.of("x", "x_2"));
        assertThat(MzMLTerms.uniqueId("x", taken)).isEqualTo("x_3");
        assertThat(MzMLTerms.uniqueId("x", taken)).isEqualTo("x_4");
        assertThat(MzMLTerms.uniqueId("y", taken)).isEqualTo("y");
        assertThat(taken).containsExactlyInAnyOrder("x", "x_2", "x_3", "x_4", "y");
    }

    @Test
    void repairedIdIsTheIndexIdOrTheFirstFreeMzpeakjSuffix() {
        Set<String> taken = new HashSet<>();
        assertThat(MzMLTerms.repairedId(3, taken)).isEqualTo("index=3");
        assertThat(taken).containsExactly("index=3");
        assertThat(MzMLTerms.repairedId(3, taken)).isEqualTo("index=3 mzpeakj=1");
        assertThat(MzMLTerms.repairedId(3, taken)).isEqualTo("index=3 mzpeakj=2");
        assertThat(MzMLTerms.repairedId(3, new HashSet<>(Set.of("index=3")))).isEqualTo("index=3 mzpeakj=1");
        assertThat(MzMLTerms.repairedId(3, new HashSet<>(Set.of("index=3", "index=3 mzpeakj=1"))))
                .isEqualTo("index=3 mzpeakj=2");
        assertThat(MzMLTerms.repairedId(0, new HashSet<>(Set.of("index=3")))).isEqualTo("index=0");
        assertThat(MzMLTerms.spectrumIdValid(MzMLTerms.repairedId(3, taken))).isTrue();
    }

    // ---- (e) rule IC --------------------------------------------------------------------------------------------

    private static Map<String, Integer> map(Object... pairs) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return out;
    }

    @Test
    void icIdsOfRuleIcAreExact() {
        MzMLTerms.IcIds ids = MzMLTerms.icIds(List.of("IC1", "IC2"));
        assertThat(ids.exact()).isTrue();
        assertThat(ids.ids()).containsExactly(0, 1);
        assertThat(ids.map()).containsExactlyEntriesOf(map("IC1", 0, "IC2", 1));

        ids = MzMLTerms.icIds(List.of("IC2", "IC1"));
        assertThat(ids.exact()).isTrue();
        assertThat(ids.ids()).containsExactly(1, 0);
        assertThat(ids.map()).containsExactlyEntriesOf(map("IC2", 1, "IC1", 0));

        ids = MzMLTerms.icIds(List.of("IC_-1", "IC1", "IC8"));
        assertThat(ids.exact()).isTrue();
        assertThat(ids.ids()).containsExactly(-1, 0, 7);
        assertThat(ids.map()).containsExactlyEntriesOf(map("IC_-1", -1, "IC1", 0, "IC8", 7));

        assertThat(MzMLTerms.icIds(List.of("IC2147483648"))).isEqualTo(
                new MzMLTerms.IcIds(List.of(Integer.MAX_VALUE), map("IC2147483648", Integer.MAX_VALUE), true));
        assertThat(MzMLTerms.icIds(List.of("IC_-2147483648"))).isEqualTo(
                new MzMLTerms.IcIds(List.of(Integer.MIN_VALUE), map("IC_-2147483648", Integer.MIN_VALUE), true));
        assertThat(MzMLTerms.icIds(List.of())).isEqualTo(new MzMLTerms.IcIds(List.of(), map(), true));
    }

    @Test
    void icIdsOutsideRuleIcFallBackToDocumentOrder() {
        assertThat(MzMLTerms.icIds(List.of("LCQ_x0020_Deca")))
                .isEqualTo(new MzMLTerms.IcIds(List.of(0), map("LCQ_x0020_Deca", 0), false));
        assertThat(MzMLTerms.icIds(List.of("IC1", "IC1x")))
                .isEqualTo(new MzMLTerms.IcIds(List.of(0, 1), map("IC1", 0, "IC1x", 1), false));
        assertThat(MzMLTerms.icIds(List.of("IC1", "IC_-0")))
                .isEqualTo(new MzMLTerms.IcIds(List.of(0, 1), map("IC1", 0, "IC_-0", 1), false));
        assertThat(MzMLTerms.icIds(List.of("IC2", "IC2147483649")))
                .isEqualTo(new MzMLTerms.IcIds(List.of(0, 1), map("IC2", 0, "IC2147483649", 1), false));
        assertThat(MzMLTerms.icIds(List.of("IC_-2147483649")))
                .isEqualTo(new MzMLTerms.IcIds(List.of(0), map("IC_-2147483649", 0), false));
        assertThat(MzMLTerms.icIds(List.of("IC99999999999")))
                .isEqualTo(new MzMLTerms.IcIds(List.of(0), map("IC99999999999", 0), false));
        // a repeated id: positions, and a reference resolves to the first occurrence
        assertThat(MzMLTerms.icIds(List.of("IC1", "IC1")))
                .isEqualTo(new MzMLTerms.IcIds(List.of(0, 1), map("IC1", 0), false));
        // IC0 is no rule-IC id (no leading zero), so IC_-1 next to it does not count either
        assertThat(MzMLTerms.icIds(List.of("IC_-1", "IC0")).exact()).isFalse();
        assertThat(MzMLTerms.icIds(List.of("IC01", "ic1", "IC_1", "IC_+1", " IC1")).exact()).isFalse();
    }

    @Test
    void icIdIsTheInverseOfRuleIc() {
        assertThat(MzMLTerms.icId(0)).isEqualTo("IC1");
        assertThat(MzMLTerms.icId(-1)).isEqualTo("IC_-1");
        assertThat(MzMLTerms.icId(Integer.MAX_VALUE)).isEqualTo("IC2147483648");
        assertThat(MzMLTerms.icId(Integer.MIN_VALUE)).isEqualTo("IC_-2147483648");

        List<Integer> ids = List.of(-3, -1, 0, 2, 7, Integer.MAX_VALUE, Integer.MIN_VALUE);
        List<String> written = ids.stream().map(MzMLTerms::icId).toList();
        MzMLTerms.IcIds read = MzMLTerms.icIds(written);
        assertThat(read.exact()).isTrue();
        assertThat(read.ids()).isEqualTo(ids);
        for (int i = 0; i < ids.size(); i++) {
            assertThat(read.map()).containsEntry(written.get(i), ids.get(i));
            assertThat(jdkAccepts("id", written.get(i))).as(written.get(i)).isTrue();
        }
    }

    // ---- (f) spectrum ids and xs:dateTime -----------------------------------------------------------------------

    @Test
    void spectrumIdsMatchThePatternOfTheSchema() {
        Map<String, Boolean> cases = new LinkedHashMap<>();
        cases.put("scan=19", true);
        cases.put("sample=1 period=1 cycle=22 experiment=1", true);
        cases.put("index=3 mzpeakj=1", true);
        cases.put("===", true);
        cases.put("a=b=c", true);
        cases.put("a=\u00a0b", true);
        cases.put("scan19", false);
        cases.put("a=b  c=d", false);
        cases.put("", false);
        cases.put("==", false);
        cases.put("=a", false);
        cases.put("a=", false);
        cases.put("a=b ", false);
        cases.put(" a=b", false);
        cases.put("a=b\tc=d", false);
        cases.put("a=b\nc", false);
        cases.put("a=b\r", false);
        for (Map.Entry<String, Boolean> e : cases.entrySet()) {
            assertThat(MzMLTerms.spectrumIdValid(e.getKey())).as(e.getKey()).isEqualTo(e.getValue());
            assertThat(jdkAccepts("spectrum", e.getKey())).as("JDK " + e.getKey()).isEqualTo(e.getValue());
        }
    }

    @Test
    void theSpectrumIdCheckHandlesLongIdsInLinearTime() {
        // a run of '=' that a backtracking regex retries quadratically, and pairs that a regex recurses over
        String run = "=".repeat(100_000) + " ";
        String pairs = "x=" + "a=b ".repeat(20_000) + "=";
        String valid = "a=b ".repeat(20_000) + "c=d";
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            assertThat(MzMLTerms.spectrumIdValid(run)).isFalse();
            assertThat(MzMLTerms.spectrumIdValid(pairs)).isFalse();
            assertThat(MzMLTerms.spectrumIdValid(valid)).isTrue();
        });
    }

    @Test
    void dateTimesAreJudgedAsTheJdkValidatorJudgesThem() {
        Map<String, Boolean> cases = new LinkedHashMap<>();
        cases.put("2005-07-20T19:44:22Z", true);
        cases.put("2007-06-27T15:23:45.00035", true);
        cases.put("2005-07-20 19:44:22", false);
        cases.put("2005-07-20T19:44:22+14:00", true);
        cases.put("2005-07-20T19:44:22-13:59", true);
        cases.put("2005-07-20T19:44:22+14:01", false);
        cases.put("2005-07-20T19:44:22z", false);
        cases.put("2005-07-20T24:00:00", true);
        cases.put("2005-12-31T24:00:00.000", true);
        cases.put("2005-07-20T24:00:00.5", false);
        cases.put("2005-07-20T24:01:00", false);
        cases.put("2005-07-20T24:00:01", false);
        // the validator reads the seconds as a double: below 60, and 0 for hour 24
        cases.put("2005-07-20T19:44:59.99999999999999Z", true);
        cases.put("2005-07-20T19:44:59.999999999999999Z", false);
        cases.put("2005-07-20T19:44:59.9999999999999999999999999", false);
        cases.put("2005-07-20T24:00:00." + "0".repeat(400) + "1", true);
        cases.put("2005-07-20T24:00:00." + "0".repeat(300) + "1", false);
        cases.put("2005-07-20T19:44:60", false);
        cases.put("2005-07-20T19:44:22.", false);
        cases.put("2005-07-20T19:44", false);
        cases.put("2005-7-20T19:44:22", false);
        cases.put("+2005-07-20T19:44:22", false);
        cases.put("0000-01-01T00:00:00", false);
        cases.put("-0000-01-01T00:00:00", false);
        cases.put("-0001-01-01T00:00:00", true);
        cases.put("12345-07-20T19:44:22", true);
        cases.put("012345-07-20T19:44:22", false);
        cases.put("2147483647-01-01T00:00:00", true);
        cases.put("2147483648-01-01T00:00:00", false);
        cases.put("-2147483648-01-01T00:00:00", true);
        cases.put("-2147483649-01-01T00:00:00", false);
        cases.put("99999999999-01-01T00:00:00", false);
        cases.put("2000-02-29T00:00:00", true);
        cases.put("2004-02-29T00:00:00", true);
        cases.put("1900-02-29T00:00:00", false);
        cases.put("2005-02-29T00:00:00", false);
        cases.put("2005-02-28T00:00:00", true);
        cases.put("-0004-02-29T00:00:00", true);
        cases.put("-0001-02-29T00:00:00", false);
        cases.put("2005-04-31T00:00:00", false);
        cases.put("2005-06-30T00:00:00", true);
        cases.put("2005-07-31T00:00:00", true);
        cases.put("2005-11-31T00:00:00", false);
        cases.put("2005-13-01T00:00:00", false);
        cases.put("2005-07-32T00:00:00", false);
        cases.put(" 2005-07-20T19:44:22Z\t", true);
        cases.put("2005-07-20T19:44:22Z\n", true);
        cases.put("", false);
        for (Map.Entry<String, Boolean> e : cases.entrySet()) {
            assertThat(MzMLTerms.dateTimeValid(e.getKey())).as(e.getKey()).isEqualTo(e.getValue());
            assertThat(jdkAccepts("time", e.getKey())).as("JDK " + e.getKey()).isEqualTo(e.getValue());
        }
    }

    // ---- (g) characters XML cannot carry ------------------------------------------------------------------------

    @Test
    void charactersXmlCannotCarryBecomeTheReplacementCharacter() {
        for (String bad : List.of("\u0001", "\u0000", "\u001f", "\ufffe", "\uffff", "\ud800", "\udc00", "\udbff",
                "\udfff")) {
            assertThat(MzMLTerms.xmlSafe("a" + bad + "b"))
                    .as("U+%04X", (int) bad.charAt(0)).isEqualTo(new MzMLTerms.Repaired("a\ufffdb", true));
        }
        assertThat(MzMLTerms.xmlSafe("\ud800")).isEqualTo(new MzMLTerms.Repaired("\ufffd", true));
        assertThat(MzMLTerms.xmlSafe("\udc00\ud800")).isEqualTo(new MzMLTerms.Repaired("\ufffd\ufffd", true));
        assertThat(MzMLTerms.xmlSafe("x\u0002\ud83d\ude00\u0003"))
                .isEqualTo(new MzMLTerms.Repaired("x\ufffd\ud83d\ude00\ufffd", true));
    }

    /** JZ-5: the C1 controls are replaced too (design 5.3, A-28); DEL before them and NBSP after them are kept. */
    @Test
    void c1ControlsBecomeTheReplacementCharacter() {
        for (String c1 : List.of("\u0080", "\u0085", "\u009f")) {
            assertThat(MzMLTerms.xmlSafe("a" + c1 + "b"))
                    .as("U+%04X", (int) c1.charAt(0)).isEqualTo(new MzMLTerms.Repaired("a\ufffdb", true));
        }
        assertThat(MzMLTerms.xmlSafe("\u0080\u009f")).isEqualTo(new MzMLTerms.Repaired("\ufffd\ufffd", true));
        for (String kept : List.of("\u007f", "\u00a0", "a\u007f\u00a0b")) {
            MzMLTerms.Repaired safe = MzMLTerms.xmlSafe(kept);
            assertThat(safe.repaired()).as("U+%04X", (int) kept.charAt(0)).isFalse();
            assertThat(safe.value()).isSameAs(kept);
        }
    }

    @Test
    void textXmlCanCarryIsReturnedUnchanged() {
        for (String good : List.of("", "plain", "\t\n\r", "\ud83d\ude00", "a\ud83d\ude00b", " \u007f\u00a0\ud7ff",
                "\ue000\ufffd\ufdd0", "\u00e4")) {
            MzMLTerms.Repaired safe = MzMLTerms.xmlSafe(good);
            assertThat(safe.repaired()).as(good).isFalse();
            assertThat(safe.value()).isSameAs(good);
        }
    }

    // ---- (h) term names -----------------------------------------------------------------------------------------

    @Test
    void namesResolveThroughTheTablesAndTheLibraryTables() {
        assertThat(MzMLTerms.name("MS:1000580")).isEqualTo("MSn spectrum");
        assertThat(MzMLTerms.name("MS:1000235")).isEqualTo("total ion current chromatogram");
        assertThat(MzMLTerms.name("MS:4000104")).isEqualTo("total ion currents");
        assertThat(MzMLTerms.name("MS:1000795")).isEqualTo("no combination");
        assertThat(MzMLTerms.name("UO:0000031")).isEqualTo("minute");
        assertThat(MzMLTerms.name("UO:0000032")).isEqualTo("hour");
        assertThat(MzMLTerms.name("MS:1000523")).isEqualTo("64-bit float");
        assertThat(MzMLTerms.name("MS:1002746"))
                .isEqualTo("MS-Numpress linear prediction compression followed by zlib compression");
        assertThat(MzMLTerms.name("MS:1003812")).isEqualTo("lambda max");
        assertThat(MzMLTerms.name("MS:1000590")).isEqualTo("contact affiliation");
        assertThat(MzMLTerms.name("MS:1003006")).isEqualTo(CvTerms.ARRAY_TYPES.get("MS:1003006"))
                .isEqualTo("mean inverse reduced ion mobility array");
        assertThat(MzMLTerms.name("MS:1000133")).isEqualTo(CvTerms.DISSOCIATION_METHODS.get("MS:1000133"))
                .isEqualTo("collision-induced dissociation");
        assertThat(MzMLTerms.name("MS:1000343")).isNull(); // obsolete 'product ion spectrum'
        assertThat(MzMLTerms.name("MS:1000520")).isNull(); // obsolete '16-bit float'
        assertThat(MzMLTerms.name("MS:9999999")).isNull();
        assertThat(MzMLTerms.name(null)).isNull();
    }

    /**
     * Every entry of the five generated tables with its psi-ms name, pinned in the default build: checked once against
     * psi-ms 4.2.11 (mzdata v0.68.0 {@code cv/psi-ms.obo.gz}), as {@code CvTermsTablesTest} pins
     * {@code CvTerms.ARRAY_TYPES}. {@code MzMLFixturesTest.theTermListsRegenerateFromThePsiMsObo} compares the tables
     * with that file when {@code -Dmzpeak.psims.obo} names it.
     */
    @ParameterizedTest(name = "{0} {1} {2}")
    @CsvSource(delimiter = '|', value = {
            "SPECTRUM_TYPES | MS:1000294 | mass spectrum",
            "SPECTRUM_TYPES | MS:1000322 | charge inversion mass spectrum",
            "SPECTRUM_TYPES | MS:1000325 | constant neutral gain spectrum",
            "SPECTRUM_TYPES | MS:1000326 | constant neutral loss spectrum",
            "SPECTRUM_TYPES | MS:1000328 | e/2 mass spectrum",
            "SPECTRUM_TYPES | MS:1000341 | precursor ion spectrum",
            "SPECTRUM_TYPES | MS:1000579 | MS1 spectrum",
            "SPECTRUM_TYPES | MS:1000580 | MSn spectrum",
            "SPECTRUM_TYPES | MS:1000581 | CRM spectrum",
            "SPECTRUM_TYPES | MS:1000582 | SIM spectrum",
            "SPECTRUM_TYPES | MS:1000583 | SRM spectrum",
            "SPECTRUM_TYPES | MS:1000789 | enhanced multiply charged spectrum",
            "SPECTRUM_TYPES | MS:1000790 | time-delayed fragmentation spectrum",
            "SPECTRUM_TYPES | MS:1000804 | electromagnetic radiation spectrum",
            "SPECTRUM_TYPES | MS:1000805 | emission spectrum",
            "SPECTRUM_TYPES | MS:1000806 | absorption spectrum",
            "CHROMATOGRAM_TYPES | MS:1000235 | total ion current chromatogram",
            "CHROMATOGRAM_TYPES | MS:1000627 | selected ion current chromatogram",
            "CHROMATOGRAM_TYPES | MS:1000628 | basepeak chromatogram",
            "CHROMATOGRAM_TYPES | MS:1000810 | ion current chromatogram",
            "CHROMATOGRAM_TYPES | MS:1000811 | electromagnetic radiation chromatogram",
            "CHROMATOGRAM_TYPES | MS:1000812 | absorption chromatogram",
            "CHROMATOGRAM_TYPES | MS:1000813 | emission chromatogram",
            "CHROMATOGRAM_TYPES | MS:1001472 | selected ion monitoring chromatogram",
            "CHROMATOGRAM_TYPES | MS:1001473 | selected reaction monitoring chromatogram",
            "CHROMATOGRAM_TYPES | MS:1002715 | temperature chromatogram",
            "CHROMATOGRAM_TYPES | MS:1003019 | pressure chromatogram",
            "CHROMATOGRAM_TYPES | MS:1003020 | flow rate chromatogram",
            "CHROMATOGRAM_TYPES | MS:4000025 | precursor ion current chromatogram",
            "CHROMATOGRAM_TYPES | MS:4000104 | total ion currents",
            "SPECTRA_COMBINATION | MS:1000571 | sum of spectra",
            "SPECTRA_COMBINATION | MS:1000573 | median of spectra",
            "SPECTRA_COMBINATION | MS:1000575 | mean of spectra",
            "SPECTRA_COMBINATION | MS:1000795 | no combination",
            "DATA_TYPES | MS:1000519 | 32-bit integer",
            "DATA_TYPES | MS:1000521 | 32-bit float",
            "DATA_TYPES | MS:1000522 | 64-bit integer",
            "DATA_TYPES | MS:1000523 | 64-bit float",
            "DATA_TYPES | MS:1001479 | null-terminated ASCII string",
            "COMPRESSIONS | MS:1000574 | zlib compression",
            "COMPRESSIONS | MS:1000576 | no compression",
            "COMPRESSIONS | MS:1002312 | MS-Numpress linear prediction compression",
            "COMPRESSIONS | MS:1002313 | MS-Numpress positive integer compression",
            "COMPRESSIONS | MS:1002314 | MS-Numpress short logged float compression",
            "COMPRESSIONS | MS:1002746 | MS-Numpress linear prediction compression followed by zlib compression",
            "COMPRESSIONS | MS:1002747 | MS-Numpress positive integer compression followed by zlib compression",
            "COMPRESSIONS | MS:1002748 | MS-Numpress short logged float compression followed by zlib compression",
            "COMPRESSIONS | MS:1003088 | truncation and zlib compression",
            "COMPRESSIONS | MS:1003089 | truncation, delta prediction and zlib compression",
            "COMPRESSIONS | MS:1003090 | truncation, linear prediction and zlib compression",
            "COMPRESSIONS | MS:1003780 | zstd compression",
            "COMPRESSIONS | MS:1003781 | byte-shuffled zstd compression",
            "COMPRESSIONS | MS:1003782 | dictionary-encoded zstd compression",
            "COMPRESSIONS | MS:1003783 | MS-Numpress linear prediction compression followed by zstd compression",
            "COMPRESSIONS | MS:1003784 | MS-Numpress positive integer compression followed by zstd compression",
            "COMPRESSIONS | MS:1003785 | MS-Numpress short logged float compression followed by zstd compression",
            "COMPRESSIONS | MS:1003826 | coordinate grid encoding"
    })
    void everyGeneratedTableEntryHasItsPsiMsName(String table, String accession, String name) {
        Map<String, String> entries = switch (table) {
            case "SPECTRUM_TYPES" -> MzMLTerms.SPECTRUM_TYPES;
            case "CHROMATOGRAM_TYPES" -> MzMLTerms.CHROMATOGRAM_TYPES;
            case "SPECTRA_COMBINATION" -> MzMLTerms.SPECTRA_COMBINATION;
            case "DATA_TYPES" -> MzMLTerms.DATA_TYPES;
            default -> MzMLTerms.COMPRESSIONS;
        };

        assertThat(entries).containsEntry(accession, name);
    }

    @Test
    void theGeneratedTablesHoldTheNonObsoleteDescendantsInAccessionOrder() {
        assertThat(MzMLTerms.SPECTRUM_TYPES.keySet()).containsExactly("MS:1000294", "MS:1000322", "MS:1000325",
                "MS:1000326", "MS:1000328", "MS:1000341", "MS:1000579", "MS:1000580", "MS:1000581", "MS:1000582",
                "MS:1000583", "MS:1000789", "MS:1000790", "MS:1000804", "MS:1000805", "MS:1000806");
        assertThat(MzMLTerms.SPECTRUM_TYPES).containsEntry("MS:1000579", "MS1 spectrum")
                .containsEntry("MS:1000804", "electromagnetic radiation spectrum")
                .doesNotContainKeys("MS:1000343", "MS:1000620");
        assertThat(MzMLTerms.CHROMATOGRAM_TYPES.keySet()).containsExactly("MS:1000235", "MS:1000627",
                "MS:1000628", "MS:1000810", "MS:1000811", "MS:1000812", "MS:1000813", "MS:1001472", "MS:1001473",
                "MS:1002715", "MS:1003019", "MS:1003020", "MS:4000025", "MS:4000104");
        assertThat(MzMLTerms.CHROMATOGRAM_TYPES).containsEntry("MS:1000628", "basepeak chromatogram")
                .doesNotContainKey("MS:1001474");
        assertThat(MzMLTerms.SPECTRA_COMBINATION).containsExactlyInAnyOrderEntriesOf(Map.of(
                "MS:1000571", "sum of spectra", "MS:1000573", "median of spectra", "MS:1000575", "mean of spectra",
                "MS:1000795", "no combination"));
        assertThat(MzMLTerms.SPECTRA_COMBINATION.keySet())
                .containsExactly("MS:1000571", "MS:1000573", "MS:1000575", "MS:1000795");
        assertThat(MzMLTerms.DATA_TYPES.keySet())
                .containsExactly("MS:1000519", "MS:1000521", "MS:1000522", "MS:1000523", "MS:1001479");
        assertThat(MzMLTerms.COMPRESSIONS.keySet()).containsExactly("MS:1000574", "MS:1000576", "MS:1002312",
                "MS:1002313", "MS:1002314", "MS:1002746", "MS:1002747", "MS:1002748", "MS:1003088", "MS:1003089",
                "MS:1003090", "MS:1003780", "MS:1003781", "MS:1003782", "MS:1003783", "MS:1003784", "MS:1003785",
                "MS:1003826");
        assertThat(MzMLTerms.COMPRESSIONS).containsEntry("MS:1000574", "zlib compression")
                .containsEntry("MS:1000576", "no compression");
    }

    @Test
    void theUnitTableNamesEveryDefaultArrayUnit() {
        assertThat(MzMLTerms.UNIT_NAMES.keySet()).containsExactly("MS:1000040", "MS:1000131", "MS:1002814",
                "UO:0000010", "UO:0000017", "UO:0000018", "UO:0000028", "UO:0000031", "UO:0000032", "UO:0000186",
                "UO:0000266", "UO:0000269");
        assertThat(MzMLTerms.UNIT_NAMES.keySet()).containsAll(CvTerms.DEFAULT_ARRAY_UNITS.values());
        assertThat(MzMLTerms.UNIT_NAMES).containsEntry("MS:1000040", "m/z")
                .containsEntry("MS:1000131", "number of detector counts")
                .containsEntry("MS:1002814", "volt-second per square centimeter")
                .containsEntry("UO:0000010", "second").containsEntry("UO:0000028", "millisecond")
                .containsEntry("UO:0000018", "nanometer").containsEntry("UO:0000186", "dimensionless unit")
                .containsEntry("UO:0000266", "electronvolt").containsEntry("UO:0000269", "absorbance unit")
                .containsEntry("UO:0000017", "micrometer");
    }

    @Test
    void theFixedTermsNameEveryTermTheWriterEmits() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("MS:1000016", "scan start time");
        expected.put("MS:1000041", "charge state");
        expected.put("MS:1000042", "peak intensity");
        expected.put("MS:1000045", "collision energy");
        expected.put("MS:1000127", "centroid spectrum");
        expected.put("MS:1000128", "profile spectrum");
        expected.put("MS:1000129", "negative scan");
        expected.put("MS:1000130", "positive scan");
        expected.put("MS:1000500", "scan window upper limit");
        expected.put("MS:1000501", "scan window lower limit");
        expected.put("MS:1000511", "ms level");
        expected.put("MS:1000512", "filter string");
        expected.put("MS:1000544", "Conversion to mzML");
        expected.put("MS:1000586", "contact name");
        expected.put("MS:1000590", "contact affiliation");
        expected.put("MS:1000616", "preset scan configuration");
        expected.put("MS:1000618", "highest observed wavelength");
        expected.put("MS:1000619", "lowest observed wavelength");
        expected.put("MS:1000744", "selected ion m/z");
        expected.put("MS:1000786", "non-standard data array");
        expected.put("MS:1000799", "custom unreleased software tool");
        expected.put("MS:1000827", "isolation window target m/z");
        expected.put("MS:1000828", "isolation window lower offset");
        expected.put("MS:1000829", "isolation window upper offset");
        expected.put("MS:1000927", "ion injection time");
        expected.put("MS:1003812", "lambda max");
        assertThat(MzMLTerms.FIXED_TERMS).containsExactlyEntriesOf(expected);
        assertThat(MzMLTerms.FIXED_TERMS.get("MS:1000786")).isEqualTo(CvTerms.ARRAY_TYPES.get("MS:1000786"));
    }

    @Test
    void theTablesAreUnmodifiable() {
        for (Map<String, String> table : List.of(MzMLTerms.SPECTRUM_TYPES, MzMLTerms.CHROMATOGRAM_TYPES,
                MzMLTerms.SPECTRA_COMBINATION, MzMLTerms.DATA_TYPES, MzMLTerms.COMPRESSIONS, MzMLTerms.UNIT_NAMES,
                MzMLTerms.FIXED_TERMS)) {
            assertThatThrownBy(() -> table.put("MS:0", "x")).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // ---- (i) XSD whitespace collapse ----------------------------------------------------------------------------

    @Test
    void collapseRemovesLeadingAndTrailingWhitespaceAndShortensEachInnerRunToOneSpace() {
        assertThat(MzMLTerms.collapse(" a \t\n  b ")).isEqualTo("a b");
        assertThat(MzMLTerms.collapse("\r\na\rb\t")).isEqualTo("a b");
        assertThat(MzMLTerms.collapse(" \t\n\r ")).isEmpty();
        assertThat(MzMLTerms.collapse("a b")).isEqualTo("a b"); // NBSP is no XML whitespace
        assertThat(MzMLTerms.collapse("")).isEmpty();
        assertThat(MzMLTerms.collapse(null)).isNull();
    }

    /**
     * Java's {@code $} also matches before a final U+0085, U+2028 or U+2029, which are no XML whitespace: the
     * whitespace before them stays, as in XSD and JAXB collapse (MJ-5 note 1).
     */
    @Test
    void whitespaceBeforeAFinalLineTerminatorThatIsNoXmlWhitespaceStays() {
        assertThat(MzMLTerms.collapse("a \u0085")).isEqualTo("a \u0085");
        assertThat(MzMLTerms.collapse(" a \t\u2028")).isEqualTo("a \u2028");
        assertThat(MzMLTerms.collapse("a\n\u2029 ")).isEqualTo("a \u2029");
    }

    @Test
    void collapseGivesWhatTheEarlierEndsFirstOrderGave() {
        // the order before MJ-5: ends removed first, then the inner runs; the end anchored at the end of input, not
        // with '$', which also matches before a final line terminator, so U+0085, U+2028 and U+2029 are drawn too
        Pattern ends = Pattern.compile("^[ \\t\\n\\r]+|[ \\t\\n\\r]+\\z");
        Pattern runs = Pattern.compile("[ \\t\\n\\r]+");
        String alphabet = " \t\n\ra \u0085  ";
        Random random = new Random(5);
        for (int n = 0; n < 20_000; n++) {
            char[] chars = new char[random.nextInt(9)];
            for (int k = 0; k < chars.length; k++) {
                chars[k] = alphabet.charAt(random.nextInt(alphabet.length()));
            }
            String text = new String(chars);
            assertThat(MzMLTerms.collapse(text)).as(Arrays.toString(chars))
                    .isEqualTo(runs.matcher(ends.matcher(text).replaceAll("")).replaceAll(" "));
        }
    }

    @Test
    void collapseHandlesLongWhitespaceRunsInLinearTime() {
        // a long inner run that a trailing-whitespace pattern would retry from each of its positions
        String run = " \t\n".repeat(66_667).substring(1); // 200,000 spaces, tabs and LFs
        assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> assertThat(MzMLTerms.collapse("x" + run + "y")).isEqualTo("x y"));
    }

    @Test
    void dateTimesAreCollapsedBeforeTheyAreChecked() {
        assertThat(MzMLTerms.dateTimeValid(" 2007-06-27T15:23:45.00035 ")).isTrue();
        assertThat(jdkAccepts("time", " 2007-06-27T15:23:45.00035 ")).isTrue();
    }

    // ---- (j) rule URI -------------------------------------------------------------------------------------------

    /** The source file location of the vendored Example_Processed.img.mzpeak: spaces and backslashes. */
    private static final String WINDOWS_LOCATION = "C:\\Users\\Thorsten Schramm\\Documents\\Promotion\\imzML\\Website"
            + "\\files\\Beispiel-Dateien\\Example images\\";

    @Test
    void validUrisAreReturnedUnchanged() {
        for (String uri : List.of("", "file:///unknown", "file:///unknown dir/x", WINDOWS_LOCATION,
                "file://F:/data/Exp01", "file:///a|b", "http://[::1]/x", "file:///\u00e4", "a~b", "a\u007fb",
                "  a  b  ", "a\tb", " http://x", "///", "%41", "tag:a,b:c", "file:///a%5Bb", "?", "#",
                "http://[::1]:65535/", "http://[::1]:0065535/", "http://[::255.255.255.255]/",
                "http://[::ffff:001.2.3.4]/", "http://u@[::1]:80/", "//[1:2:3:4:5:6:7:8]", "http://h:65536/")) {
            assertThat(jdkAccepts("uri", uri)).as("JDK " + uri).isTrue();
            assertThat(MzMLTerms.anyUri(uri)).as(uri).isEqualTo(new MzMLTerms.Repaired(uri, false));
        }
    }

    @Test
    void anyUriHandlesLongWhitespaceRunsInLinearTime() {
        // valid: the validator collapses the run and escapes the one space left, as the rule does
        String uri = "a" + " ".repeat(100_000) + "b";
        assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> assertThat(MzMLTerms.anyUri(uri)).isEqualTo(new MzMLTerms.Repaired(uri, false)));
        assertThat(jdkAccepts("uri", uri)).isTrue();
    }

    @Test
    void invalidUrisAreRepairedByTheFirstStageThatGivesAValidUri() {
        Map<String, String> cases = new LinkedHashMap<>();
        // stage 1
        cases.put("file:///x%zz", "file:///x%25zz");
        cases.put("file:///unknown dir/with%zz bad", "file:///unknown dir/with%25zz bad");
        cases.put("file:///a[b]", "file:///a%5Bb%5D");
        cases.put("#a#b", "#a%23b");
        cases.put("#a#b#", "#a%23b%23");
        cases.put("%", "%25");
        cases.put("a%4", "a%254");
        cases.put("a%4g%41", "a%254g%41");
        cases.put("%%41", "%25%41");
        // bracketed hosts java.net.URI accepts and the validator does not: a zone id (also one the escaping of '|',
        // ' ' and '~' makes), an IPv4 byte of more than three digits, a port over 65535
        cases.put("http://[fe80::1%25en0]/", "http://%5Bfe80::1%25en0%5D/");
        cases.put("http://[::1%en0]/", "http://%5B::1%25en0%5D/");
        cases.put("//[::1%en0]/", "//%5B::1%25en0%5D/");
        cases.put("http://[::1|]", "http://%5B::1|%5D");
        cases.put("http://[:: ]", "http://%5B:: %5D");
        cases.put("http://[::~]", "http://%5B::~%5D");
        cases.put("http://[::0001.2.3.4]/", "http://%5B::0001.2.3.4%5D/");
        cases.put("http://[::ffff:1.2.3.0001]/", "http://%5B::ffff:1.2.3.0001%5D/");
        // an IPv4 byte over Integer.MAX_VALUE: java.net.URI of Java 17 throws NumberFormatException for these, later
        // releases a URISyntaxException, so they only test the catch on a Java 17 runtime
        // (mvn -Djvm=<java17>/bin/java -Dtest=MzMLTermsTest test)
        cases.put("http://[::1.2.3.2147483648]/", "http://%5B::1.2.3.2147483648%5D/");
        cases.put("file://[::99999999999.0.0.1]/data/run.raw", "file://%5B::99999999999.0.0.1%5D/data/run.raw");
        cases.put("http://[::1]:65536/", "http://%5B::1%5D:65536/");
        cases.put("http://u@[::1]:65536/", "http://u@%5B::1%5D:65536/");
        // stage 2
        cases.put(":abc", "%3Aabc");
        cases.put("1abc:x", "1abc%3Ax");
        cases.put("\u00e4:x y%", "%C3%A4%3Ax%20y%25");
        cases.put("http://", "http%3A//");
        cases.put("a:#\ud83d\ude00", "a%3A%23%F0%9F%98%80");
        // stage 3: '//' alone is an empty authority, which stage 2 cannot repair
        cases.put("//", "%2F%2F");
        for (Map.Entry<String, String> e : cases.entrySet()) {
            assertThat(jdkAccepts("uri", e.getKey())).as("JDK " + e.getKey()).isFalse();
            assertThat(MzMLTerms.anyUri(e.getKey())).as(e.getKey())
                    .isEqualTo(new MzMLTerms.Repaired(e.getValue(), true));
            assertThat(jdkAccepts("uri", e.getValue())).as("JDK " + e.getValue()).isTrue();
        }
    }

    @Test
    void stageTwoEncodesALongInputCompletely() {
        // 1,000 characters encoded as 2,755: the builder grows past its initial capacity of the input length
        String uri = ":" + "ä b/".repeat(249) + "😀z";
        String expected = "%3A" + "%C3%A4%20b/".repeat(249) + "%F0%9F%98%80z";
        assertThat(uri).hasSize(1000);
        assertThat(MzMLTerms.anyUri(uri)).isEqualTo(new MzMLTerms.Repaired(expected, true));
        assertThat(jdkAccepts("uri", uri)).isFalse();
        assertThat(jdkAccepts("uri", expected)).isTrue();
    }

    @Test
    void bracketedHostsTheValidatorAcceptsButTheRuleDoesNotAreRepairedToValidUris() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("http://[::1]:+80/", "http://%5B::1%5D:+80/");
        cases.put("http://[::1]:-0/", "http://%5B::1%5D:-0/");
        cases.put("http://[::1.2.3.]/", "http://%5B::1.2.3.%5D/");
        for (Map.Entry<String, String> e : cases.entrySet()) {
            assertThat(jdkAccepts("uri", e.getKey())).as("JDK " + e.getKey()).isTrue();
            assertThat(MzMLTerms.anyUri(e.getKey())).as(e.getKey())
                    .isEqualTo(new MzMLTerms.Repaired(e.getValue(), true));
            assertThat(jdkAccepts("uri", e.getValue())).as("JDK " + e.getValue()).isTrue();
        }
    }

    /** The inputs of {@link #bracketedHostsTheValidatorAcceptsButTheRuleDoesNotAreRepairedToValidUris}. */
    private static final Pattern STRICTER = Pattern.compile("\\]:[+-]|\\.\\]");

    @Test
    void anyUriAgreesWithTheJdkValidatorAndAlwaysGivesAValidUri() {
        assertThat(anyUriProblems(new Random(15), 3000, List.of(""),
                "aZ09:/?#[]@!$&'()*+,;=-._~% <>\"{}|\\^`\u00e4\t", 6)).isEmpty();
    }

    @Test
    void anyUriAgreesWithTheJdkValidatorOnBracketedHosts() {
        assertThat(anyUriProblems(new Random(15), 4000,
                List.of("http://[", "http://[::1]", "http://[::", "//[1::", "http://u@[::1]:", "http://[::ffff:"),
                "0123456789abcdef:::...%]]|~ +-/", 10)).isEmpty();
    }

    /**
     * Random inputs of a prefix and up to {@code maxLength} characters of {@code alphabet} for which {@code anyUri}
     * disagrees with the JDK's validator (except as stated for {@link #STRICTER} inputs), gives a value that validator
     * rejects, or reports a repair other than a change.
     */
    private static List<String> anyUriProblems(Random random, int count, List<String> prefixes, String alphabet,
            int maxLength) {
        List<String> problems = new ArrayList<>();
        for (int n = 0; n < count; n++) {
            char[] chars = new char[random.nextInt(maxLength + 1)];
            for (int k = 0; k < chars.length; k++) {
                chars[k] = alphabet.charAt(random.nextInt(alphabet.length()));
            }
            // one prefix draws nothing, so a single empty prefix gives the inputs of the test before the prefixes
            String prefix = prefixes.size() == 1 ? prefixes.get(0) : prefixes.get(random.nextInt(prefixes.size()));
            String uri = prefix + new String(chars);
            MzMLTerms.Repaired repaired = MzMLTerms.anyUri(uri);
            boolean accepted = jdkAccepts("uri", uri);
            if (accepted == repaired.repaired() && !(accepted && STRICTER.matcher(uri).find())
                    || repaired.repaired() == repaired.value().equals(uri)
                    || !jdkAccepts("uri", repaired.value())) {
                problems.add(Arrays.toString(uri.toCharArray()) + " -> " + repaired);
            }
        }
        return problems;
    }
}
