package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.MzPeakWriter.Session;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.AuxiliaryArray;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Param;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.WavelengthSpectrum;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Auxiliary arrays written (R1, X-32; D13-19, docs/layouts/auxiliary-arrays.md): the split layout writes each
 * entity's {@link AuxiliaryArray}s into the {@code auxiliary_arrays} list of its metadata row, {@code data} and
 * {@code compression} as they are, with {@code number_of_auxiliary_arrays}; until X-32 it wrote the list empty and the
 * count 0 for every row. The names, units, data types, compressions, params and data processing references of the
 * arrays pass the writer's W12 checks; the packed layout, whose schemas are frozen, does not write them and warns.
 *
 * <p>Fixtures: small.unpacked and has_uv as read, spectrum 0 (chromatogram 0, wavelength spectrum 0) given
 * {@link #PLAIN}, MS:1000517 (signal to noise array) of the 64-bit floats 1, 2 and 3 without compression, and
 * {@link #ZLIB}, the same values zlib-compressed, named MS:1000786 (non-standard data array) "zlib signal to noise";
 * the two names differ, since both reference readers key a spectrum's arrays by name.
 */
class AuxiliaryArrayWriteTest {

    private static final Path SMALL = Fixtures.current("small.unpacked.mzpeak");
    private static final Path HAS_UV = Fixtures.current("has_uv.mzpeak");
    private static final String SPECTRA = "spectra_metadata.parquet";
    private static final String COUNT = "number_of_auxiliary_arrays";
    private static final String FLOAT64 = "MS:1000523";
    private static final String NONE = "MS:1000576";
    private static final String ZLIB_COMPRESSION = "MS:1000574";
    /** The name of {@link #ZLIB}, the value of its non-standard data array term. */
    static final String ZLIB_NAME = "zlib signal to noise";
    static final AuxiliaryArray PLAIN = new AuxiliaryArray(
            new Param("signal to noise array", "MS:1000517", null, null), float64(1.0, 2.0, 3.0), FLOAT64, NONE, 3,
            null, List.of(), null);
    static final AuxiliaryArray ZLIB = new AuxiliaryArray(
            new Param("non-standard data array", "MS:1000786", ZLIB_NAME, null), zlib(float64(1.0, 2.0, 3.0)), FLOAT64,
            ZLIB_COMPRESSION, 3, null, List.of(), null);
    private static final Options SPLIT = Options.defaults().withMetadataLayout(MetadataLayout.SPLIT);
    private static final Options PACKED = Options.defaults().withMetadataLayout(MetadataLayout.PACKED);

    @TempDir
    Path tmp;

    // ---- spectra ------------------------------------------------------------------------------------------------

    /**
     * Spectrum 0 of small.unpacked with the plain and the zlib array, written split: both read back with their bytes
     * and compression unchanged and decode to [1, 2, 3]; the row's count is 2, every other row's 0 with an empty
     * list, and the spectrum's signal is the source's.
     */
    @Test
    void aSpectrumWritesItsPlainAndZlibArraysUnchanged() {
        Source source = withSpectrumArrays(SMALL, List.of(PLAIN, ZLIB));
        Path out = tmp.resolve("aux.mzpeak");
        MzPeakWriter.writeDirectory(out, source.spectra, source.chromatograms, source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata, SPLIT);

        try (MzPeakReader reader = MzPeakReader.open(out)) {
            Spectrum s0 = reader.getSpectrum(0).orElseThrow();
            assertWritten(s0.auxiliaryArrays(), List.of(PLAIN, ZLIB));
            assertThat(s0.auxiliaryArrays().get(1).name().value()).isEqualTo(ZLIB_NAME);
            assertThat(s0.mz()).isEqualTo(source.spectra.get(0).mz());
            assertThat(s0.intensity()).isEqualTo(source.spectra.get(0).intensity());
            for (int i = 1; i < reader.size(); i++) {
                assertThat(reader.getSpectrum(i).orElseThrow().auxiliaryArrays()).as("spectrum %d", i).isEmpty();
            }
        }
        List<Group> rows = rows(out.resolve(SPECTRA));
        assertThat(rows).hasSize(48);
        for (Group row : rows) {
            long index = ParquetGroups.optLong(row, "index");
            assertThat(row.getInteger(COUNT, 0)).as("count of %d", index).isEqualTo(index == 0 ? 2 : 0);
            assertThat(ParquetGroups.groupList(row, "auxiliary_arrays")).as("list of %d", index)
                    .hasSize(index == 0 ? 2 : 0);
        }
    }

    /**
     * The optional fields are written: a unit, params and a declared data processing; a name or param that has only
     * an accession is written with a name, as a {@code parameters} item is, its accession where the writer does not
     * know the term; an empty array is an empty list.
     */
    @Test
    void theOptionalFieldsAndANamelessNameAreWritten() {
        AuxiliaryArray full = new AuxiliaryArray(new Param(null, "MS:1000517", null, null), float64(4.0), FLOAT64,
                NONE, 1, "UO:0000186", List.of(new Param("comment", null, "note", null),
                new Param(null, "MS:1000786", "x", null)), "mzpeak_conversion1");
        AuxiliaryArray empty = new AuxiliaryArray(PLAIN.name(), new byte[0], FLOAT64, NONE, 0, null, List.of(), null);
        Source source = withSpectrumArrays(SMALL, List.of(full, empty));
        Path out = tmp.resolve("optional.mzpeak");
        MzPeakWriter.writeDirectory(out, source.spectra, List.of(), List.of(), source.scanSettings,
                source.fileMetadata, SPLIT);

        List<AuxiliaryArray> back;
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            back = reader.getSpectrum(0).orElseThrow().auxiliaryArrays();
        }
        assertThat(back).hasSize(2);
        AuxiliaryArray a = back.get(0);
        assertThat(a.name()).isEqualTo(new Param("MS:1000517", "MS:1000517", null, null));
        assertThat(a.unit()).isEqualTo("UO:0000186");
        assertThat(a.parameters()).containsExactly(new Param("comment", null, "note", null),
                new Param("MS:1000786", "MS:1000786", "x", null));
        assertThat(a.dataProcessingId()).isEqualTo("mzpeak_conversion1");
        assertThat(a.doubles()).containsExactly(4.0);
        assertThat(back.get(1).data()).isEmpty();
        assertThat(back.get(1).items()).isZero();
        assertThat(back.get(1).doubles()).isEmpty();
    }

    /** A session writes the arrays of the spectra added to it as the batch write does. */
    @Test
    void aSessionWritesTheArraysToo() {
        Source source = withSpectrumArrays(SMALL, List.of(PLAIN, ZLIB));
        Path out = tmp.resolve("session.mzpeak");
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        source.spectra.forEach(s -> keys.addAll(s.arrays().keySet()));
        try (Session session = MzPeakWriter.openDirectory(out, source.fileMetadata, source.scanSettings, keys,
                SPLIT)) {
            source.spectra.forEach(session::addSpectrum);
            session.finish();
        }

        try (MzPeakReader reader = MzPeakReader.open(out)) {
            assertWritten(reader.getSpectrum(0).orElseThrow().auxiliaryArrays(), List.of(PLAIN, ZLIB));
            assertThat(reader.getSpectrum(1).orElseThrow().auxiliaryArrays()).isEmpty();
        }
    }

    /** Renumbered spectra keep their arrays: written without spectrum 0, spectrum 1 is written as 0 with them. */
    @Test
    void aRenumberedSpectrumKeepsItsArrays() {
        Source source = ForwardBackwardCorpusTest.readSource(SMALL);
        List<Spectrum> later = new ArrayList<>(source.spectra.subList(1, source.spectra.size()));
        later.set(0, withArrays(later.get(0), List.of(ZLIB)));
        Path out = tmp.resolve("renumbered.mzpeak");
        MzPeakWriter.writeDirectory(out, later, List.of(), List.of(), source.scanSettings, source.fileMetadata,
                SPLIT);

        try (MzPeakReader reader = MzPeakReader.open(out)) {
            Spectrum s0 = reader.getSpectrum(0).orElseThrow();
            assertThat(s0.description().id()).isEqualTo(later.get(0).description().id());
            assertWritten(s0.auxiliaryArrays(), List.of(ZLIB));
        }
    }

    // ---- chromatograms and wavelength spectra -------------------------------------------------------------------

    /** has_uv's chromatogram 0 and wavelength spectrum 0 write their arrays; the other entities none. */
    @Test
    void aChromatogramAndAWavelengthSpectrumWriteTheirArrays() {
        Source source = ForwardBackwardCorpusTest.readSource(HAS_UV);
        List<Chromatogram> chromatograms = new ArrayList<>(source.chromatograms);
        chromatograms.set(0, withArrays(chromatograms.get(0), List.of(PLAIN, ZLIB)));
        List<WavelengthSpectrum> wavelengthSpectra = new ArrayList<>(source.wavelengthSpectra);
        wavelengthSpectra.set(0, withArrays(wavelengthSpectra.get(0), List.of(ZLIB)));
        Path out = tmp.resolve("has_uv.mzpeak");
        MzPeakWriter.writeDirectory(out, source.spectra, chromatograms, wavelengthSpectra, source.scanSettings,
                source.fileMetadata, SPLIT);

        try (MzPeakReader reader = MzPeakReader.open(out)) {
            List<Chromatogram> c = reader.chromatograms();
            assertWritten(c.get(0).auxiliaryArrays(), List.of(PLAIN, ZLIB));
            assertThat(c.subList(1, c.size())).allSatisfy(x -> assertThat(x.auxiliaryArrays()).isEmpty());
            List<WavelengthSpectrum> w = reader.wavelengthSpectra();
            assertWritten(w.get(0).auxiliaryArrays(), List.of(ZLIB));
            assertThat(w.get(0).wavelength()).isEqualTo(wavelengthSpectra.get(0).wavelength());
            assertThat(w.subList(1, w.size())).allSatisfy(x -> assertThat(x.auxiliaryArrays()).isEmpty());
        }
        assertThat(rows(out.resolve("chromatograms_metadata.parquet")).get(0).getInteger(COUNT, 0)).isEqualTo(2);
        assertThat(rows(out.resolve("wavelength_spectra_metadata.parquet")).get(0).getInteger(COUNT, 0))
                .isEqualTo(1);
    }

    // ---- checks -------------------------------------------------------------------------------------------------

    /**
     * W12 applies to the arrays: an undeclared CV prefix in the name, a param or a CURIE-shaped unit, and a data
     * processing the file metadata does not declare, are each refused before anything is written, naming the entity
     * and the array; so is a name without an accession or with one that is not a binary data array type, and a data
     * type or compression that is not a binary data type or compression term, the Rust reference reader accepts (it
     * panics on others), CURIE or not. The units and param accessions are covered by
     * {@link #unitsAndParamAccessionsMustBeCuriesTheRustReaderParses}.
     */
    @Test
    void theW12ChecksApplyToTheArrays() {
        assertRefused(array(new Param("foo", "FOO:0001", null, null), FLOAT64, NONE, null, List.of(), null),
                "spectrum 0: auxiliary array 1 name: ");
        assertRefused(array(new Param("foo", null, null, null), FLOAT64, NONE, null, List.of(), null),
                "spectrum 0: auxiliary array 1: name null is not one of the binary data array types (children of"
                        + " MS:1000513) the Rust reference reader accepts: MS:1000514, MS:1000515, MS:1000516,");
        assertRefused(array(new Param("mean charge array", "MS:1002478", null, null), FLOAT64, NONE, null, List.of(),
                null), "spectrum 0: auxiliary array 1: name MS:1002478 is not one of the binary data array types");
        assertRefused(array(new Param("factor", "EFO:0000001", null, null), FLOAT64, NONE, null, List.of(), null),
                "spectrum 0: auxiliary array 1: name EFO:0000001 is not one of the binary data array types");
        assertRefused(array(PLAIN.name(), "FOO:0002", NONE, null, List.of(), null),
                "spectrum 0: auxiliary array 1: data type FOO:0002 is not one of the binary data types (children of"
                        + " MS:1000518) the Rust reference reader accepts: MS:1000521, MS:1000523, MS:1000519,"
                        + " MS:1000522, MS:1001479");
        assertRefused(array(PLAIN.name(), "float64", NONE, null, List.of(), null),
                "spectrum 0: auxiliary array 1: data type float64 is not one of the binary data types");
        assertRefused(array(PLAIN.name(), "MS:", NONE, null, List.of(), null),
                "spectrum 0: auxiliary array 1: data type MS: is not one of the binary data types");
        assertRefused(array(PLAIN.name(), "MS:1000576", NONE, null, List.of(), null),
                "spectrum 0: auxiliary array 1: data type MS:1000576 is not one of the binary data types");
        assertRefused(array(PLAIN.name(), FLOAT64, "FOO:0003", null, List.of(), null),
                "spectrum 0: auxiliary array 1: compression FOO:0003 is not one of the binary data compressions"
                        + " (children of MS:1000572) the Rust reference reader accepts: MS:1000576, MS:1000574,");
        assertRefused(array(PLAIN.name(), FLOAT64, "zlib", null, List.of(), null),
                "spectrum 0: auxiliary array 1: compression zlib is not one of the binary data compressions");
        assertRefused(array(PLAIN.name(), FLOAT64, "MS:", null, List.of(), null),
                "spectrum 0: auxiliary array 1: compression MS: is not one of the binary data compressions");
        assertRefused(array(PLAIN.name(), FLOAT64, NONE, "FOO:0004", List.of(), null),
                "spectrum 0: auxiliary array 1: unit uses FOO:0004");
        assertRefused(array(PLAIN.name(), FLOAT64, NONE, null, List.of(new Param("p", "FOO:0005", null, null)),
                null), "spectrum 0: auxiliary array 1: ");
        assertRefused(array(PLAIN.name(), FLOAT64, NONE, null, List.of(), "nope"),
                "spectrum 0: auxiliary array 1 data_processing_id nope names no data processing");
        assertRefused(array(new Param(null, null, "v", null), FLOAT64, NONE, null, List.of(), null),
                "spectrum 0 (" + ForwardBackwardCorpusTest.readSource(SMALL).spectra.get(0).description().id()
                        + "): a parameter needs a name or an accession");
    }

    /**
     * An uncompressed array of a fixed-width data type must hold its items: 8 bytes of float64 with items 2, and of
     * int32, float32 and int64 with a length that is not a multiple of the width or not items * width, are refused,
     * naming the array, its length and items * width (AuxiliaryArray.doubles() would throw on the read-back). A
     * compressed array and a string array are not checked (D13-19): they are written as given.
     */
    @Test
    void anUncompressedFixedWidthArrayMustHoldItsItems() {
        assertRefused(new AuxiliaryArray(PLAIN.name(), float64(1.0), FLOAT64, NONE, 2, null, List.of(), null),
                "spectrum 0: auxiliary array 1: its 8 bytes are not the 16 bytes of its 2 items of MS:1000523 (8"
                        + " bytes each); an uncompressed (MS:1000576) array holds items times the width of its data"
                        + " type");
        assertRefused(new AuxiliaryArray(PLAIN.name(), new byte[6], "MS:1000519", NONE, 1, null, List.of(), null),
                "spectrum 0: auxiliary array 1: its 6 bytes are not the 4 bytes of its 1 items of MS:1000519 (4");
        assertRefused(new AuxiliaryArray(PLAIN.name(), new byte[7], "MS:1000521", NONE, 2, null, List.of(), null),
                "spectrum 0: auxiliary array 1: its 7 bytes are not the 8 bytes of its 2 items of MS:1000521 (4");
        assertRefused(new AuxiliaryArray(PLAIN.name(), new byte[8], "MS:1000522", NONE, Long.MAX_VALUE, null,
                List.of(), null), "spectrum 0: auxiliary array 1: its 8 bytes are not the 73786976294838206456"
                + " bytes of its 9223372036854775807 items of MS:1000522 (8");

        AuxiliaryArray zlibShort = new AuxiliaryArray(ZLIB.name(), ZLIB.data(), FLOAT64, ZLIB_COMPRESSION, 4, null,
                List.of(), null);
        AuxiliaryArray text = new AuxiliaryArray(new Param("non-standard data array", "MS:1000786", "labels", null),
                "abc".getBytes(java.nio.charset.StandardCharsets.UTF_8), "MS:1001479", NONE, 2, null, List.of(),
                null);
        Source source = withSpectrumArrays(SMALL, List.of(PLAIN, zlibShort, text));
        Path out = tmp.resolve("unchecked.mzpeak");
        MzPeakWriter.writeDirectory(out, source.spectra, List.of(), List.of(), source.scanSettings,
                source.fileMetadata, SPLIT);
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            List<AuxiliaryArray> back = reader.getSpectrum(0).orElseThrow().auxiliaryArrays();
            assertThat(back).hasSize(3);
            assertThat(back.get(1).data()).isEqualTo(zlibShort.data());
            assertThat(back.get(1).items()).isEqualTo(4);
            assertThat(back.get(2).data()).isEqualTo(text.data());
            assertThat(back.get(2).items()).isEqualTo(2);
        }
    }

    /**
     * The Rust reference reader parses the unit of an auxiliary array, of its name and of each of its params, and the
     * accession of each param, as an mzdata CURIE (curie_array_visitor.rs:65-71, {@code parse::<CURIE>().unwrap()}):
     * a prefix, a colon and a number that fits u32 (mzdata-param 0.68 curie_.rs:446-463); it panics on anything else,
     * "seconds" ({@code MissingNamespaceSeparator}) and "MS:abc" ({@code ParseIntError}) as rdump showed. Each such
     * value is refused, naming the entity, the array and the field, in a batch write and in a session, for spectra,
     * chromatograms and wavelength spectra; null and a CURIE whose number fits u32, leading zeros or not, are written.
     */
    @Test
    void unitsAndParamAccessionsMustBeCuriesTheRustReaderParses() {
        String why = " is not a CURIE the Rust reference reader parses (PREFIX:number, the number at most 4294967295;"
                + " it panics on any other value): give a CV term such as UO:0000010 (second), or null";
        assertRefused(array(PLAIN.name(), FLOAT64, NONE, "seconds", List.of(), null),
                "spectrum 0: auxiliary array 1: unit seconds" + why);
        assertRefused(array(PLAIN.name(), FLOAT64, NONE, "UO:second", List.of(), null),
                "spectrum 0: auxiliary array 1: unit UO:second" + why);
        assertRefused(array(PLAIN.name(), FLOAT64, NONE, "NCIT:C25301", List.of(), null),
                "spectrum 0: auxiliary array 1: unit NCIT:C25301" + why);
        assertRefused(array(PLAIN.name(), FLOAT64, NONE, "UO:4294967296", List.of(), null),
                "spectrum 0: auxiliary array 1: unit UO:4294967296" + why);
        assertRefused(array(PLAIN.name(), FLOAT64, NONE, "UO:", List.of(), null),
                "spectrum 0: auxiliary array 1: unit UO:" + why);
        assertRefused(array(PLAIN.name(), FLOAT64, NONE, ":0000010", List.of(), null),
                "spectrum 0: auxiliary array 1: unit :0000010" + why);
        assertRefused(array(new Param("signal to noise array", "MS:1000517", null, "seconds"), FLOAT64, NONE, null,
                List.of(), null), "spectrum 0: auxiliary array 1: name unit seconds" + why);
        assertRefused(array(PLAIN.name(), FLOAT64, NONE, null, List.of(new Param("comment", null, "note", null),
                new Param("foo", "foo", null, null)), null), "spectrum 0: auxiliary array 1: param 1 accession foo"
                + why);
        assertRefused(array(PLAIN.name(), FLOAT64, NONE, null, List.of(new Param("abc", "MS:abc", null, null)), null),
                "spectrum 0: auxiliary array 1: param 0 accession MS:abc" + why);
        assertRefused(array(PLAIN.name(), FLOAT64, NONE, null, List.of(new Param("p", "MS:1000786", 1.0,
                "minutes")), null), "spectrum 0: auxiliary array 1: param 0 unit minutes" + why);

        Source source = ForwardBackwardCorpusTest.readSource(HAS_UV);
        AuxiliaryArray seconds = array(PLAIN.name(), FLOAT64, NONE, "seconds", List.of(), null);
        AuxiliaryArray badAccession = array(PLAIN.name(), FLOAT64, NONE, null,
                List.of(new Param("abc", "MS:abc", null, null)), null);
        List<Chromatogram> chromatograms = new ArrayList<>(source.chromatograms);
        chromatograms.set(1, withArrays(chromatograms.get(1), List.of(PLAIN, seconds)));
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(tmp.resolve("c.mzpeak"), source.spectra,
                chromatograms, source.wavelengthSpectra, source.scanSettings, source.fileMetadata, SPLIT))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("chromatogram 1: auxiliary array 1: unit seconds" + why);
        List<WavelengthSpectrum> wavelengthSpectra = new ArrayList<>(source.wavelengthSpectra);
        wavelengthSpectra.set(2, withArrays(wavelengthSpectra.get(2), List.of(badAccession)));
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(tmp.resolve("w.mzpeak"), source.spectra,
                source.chromatograms, wavelengthSpectra, source.scanSettings, source.fileMetadata, SPLIT))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("wavelength spectrum 2: auxiliary array 0: param 0 accession MS:abc" + why);
        assertThat(tmp.resolve("c.mzpeak")).doesNotExist();
        assertThat(tmp.resolve("w.mzpeak")).doesNotExist();

        try (Session session = MzPeakWriter.openDirectory(tmp.resolve("session.mzpeak"), source.fileMetadata,
                source.scanSettings, new LinkedHashSet<>(), SPLIT)) {
            assertThatThrownBy(() -> session.addSpectrum(withArrays(source.spectra.get(0), List.of(seconds))))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("spectrum 0: auxiliary array 0: unit seconds" + why);
            assertThatThrownBy(() -> session.addSpectrum(withArrays(source.spectra.get(0),
                    List.of(PLAIN, badAccession)))).isInstanceOf(MzPeakException.class)
                    .hasMessage("spectrum 0: auxiliary array 1: param 0 accession MS:abc" + why);
            assertThatThrownBy(() -> session.addChromatogram(withArrays(source.chromatograms.get(0),
                    List.of(badAccession)))).isInstanceOf(MzPeakException.class)
                    .hasMessage("chromatogram 0: auxiliary array 0: param 0 accession MS:abc" + why);
            assertThatThrownBy(() -> session.addWavelengthSpectrum(withArrays(source.wavelengthSpectra.get(0),
                    List.of(seconds)))).isInstanceOf(MzPeakException.class)
                    .hasMessage("wavelength spectrum 0: auxiliary array 0: unit seconds" + why);
        }

        AuxiliaryArray accepted = array(new Param("signal to noise array", "MS:1000517", null, "UO:0000186"), FLOAT64,
                NONE, "UO:4294967295", List.of(new Param("p", "MS:1000786", 1.0, "UO:10")), null);
        Source small = withSpectrumArrays(SMALL, List.of(accepted));
        Path out = tmp.resolve("accepted.mzpeak");
        MzPeakWriter.writeDirectory(out, small.spectra, List.of(), List.of(), small.scanSettings, small.fileMetadata,
                SPLIT);
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            AuxiliaryArray back = reader.getSpectrum(0).orElseThrow().auxiliaryArrays().get(0);
            assertThat(back.unit()).isEqualTo("UO:4294967295");
            assertThat(back.name().unit()).isEqualTo("UO:0000186");
            assertThat(back.parameters().get(0).unit()).isEqualTo("UO:10");
        }
    }

    /** The checks cover the arrays of chromatograms and wavelength spectra, in a batch write and in a session. */
    @Test
    void theChecksCoverChromatogramsAndWavelengthSpectra() {
        Source source = ForwardBackwardCorpusTest.readSource(HAS_UV);
        AuxiliaryArray undeclared = array(PLAIN.name(), FLOAT64, NONE, "FOO:0002", List.of(), null);
        AuxiliaryArray badType = array(PLAIN.name(), "float64", NONE, null, List.of(), null);
        AuxiliaryArray badCompression = array(PLAIN.name(), FLOAT64, "zlib", null, List.of(), null);
        AuxiliaryArray noAccession = array(new Param("foo", null, null, null), FLOAT64, NONE, null, List.of(), null);
        AuxiliaryArray unmapped = array(new Param("mean charge array", "MS:1002478", null, null), FLOAT64, NONE, null,
                List.of(), null);
        AuxiliaryArray nameless = array(new Param(null, null, "v", null), FLOAT64, NONE, null, List.of(), null);
        List<Chromatogram> chromatograms = new ArrayList<>(source.chromatograms);
        chromatograms.set(1, withArrays(chromatograms.get(1), List.of(undeclared)));
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(tmp.resolve("c.mzpeak"), source.spectra,
                chromatograms, source.wavelengthSpectra, source.scanSettings, source.fileMetadata, SPLIT))
                .isInstanceOf(MzPeakException.class)
                .hasMessageStartingWith("chromatogram 1: auxiliary array 0: unit uses FOO:0002");
        chromatograms.set(1, withArrays(chromatograms.get(1), List.of(nameless)));
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(tmp.resolve("c2.mzpeak"), source.spectra,
                chromatograms, source.wavelengthSpectra, source.scanSettings, source.fileMetadata, SPLIT))
                .isInstanceOf(MzPeakException.class)
                .hasMessageContaining("a parameter needs a name or an accession");
        List<WavelengthSpectrum> wavelengthSpectra = new ArrayList<>(source.wavelengthSpectra);
        wavelengthSpectra.set(2, withArrays(wavelengthSpectra.get(2), List.of(undeclared)));
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(tmp.resolve("w.mzpeak"), source.spectra,
                source.chromatograms, wavelengthSpectra, source.scanSettings, source.fileMetadata, SPLIT))
                .isInstanceOf(MzPeakException.class)
                .hasMessageStartingWith("wavelength spectrum 2: auxiliary array 0: unit uses FOO:0002");
        wavelengthSpectra.set(2, withArrays(wavelengthSpectra.get(2), List.of(nameless)));
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(tmp.resolve("w2.mzpeak"), source.spectra,
                source.chromatograms, wavelengthSpectra, source.scanSettings, source.fileMetadata, SPLIT))
                .isInstanceOf(MzPeakException.class)
                .hasMessageContaining("a parameter needs a name or an accession");
        chromatograms.set(1, withArrays(chromatograms.get(1), List.of(PLAIN, badCompression)));
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(tmp.resolve("c3.mzpeak"), source.spectra,
                chromatograms, source.wavelengthSpectra, source.scanSettings, source.fileMetadata, SPLIT))
                .isInstanceOf(MzPeakException.class)
                .hasMessageStartingWith("chromatogram 1: auxiliary array 1: compression zlib is not one of");
        wavelengthSpectra.set(2, withArrays(wavelengthSpectra.get(2), List.of(badType)));
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(tmp.resolve("w3.mzpeak"), source.spectra,
                source.chromatograms, wavelengthSpectra, source.scanSettings, source.fileMetadata, SPLIT))
                .isInstanceOf(MzPeakException.class)
                .hasMessageStartingWith("wavelength spectrum 2: auxiliary array 0: data type float64 is not one of");
        chromatograms.set(1, withArrays(chromatograms.get(1), List.of(unmapped)));
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(tmp.resolve("c4.mzpeak"), source.spectra,
                chromatograms, source.wavelengthSpectra, source.scanSettings, source.fileMetadata, SPLIT))
                .isInstanceOf(MzPeakException.class)
                .hasMessageStartingWith("chromatogram 1: auxiliary array 0: name MS:1002478 is not one of");
        wavelengthSpectra.set(2, withArrays(wavelengthSpectra.get(2), List.of(PLAIN, noAccession)));
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(tmp.resolve("w4.mzpeak"), source.spectra,
                source.chromatograms, wavelengthSpectra, source.scanSettings, source.fileMetadata, SPLIT))
                .isInstanceOf(MzPeakException.class)
                .hasMessageStartingWith("wavelength spectrum 2: auxiliary array 1: name null is not one of");
        assertThat(tmp.resolve("c.mzpeak")).doesNotExist();
        assertThat(tmp.resolve("w.mzpeak")).doesNotExist();
        assertThat(tmp.resolve("c3.mzpeak")).doesNotExist();
        assertThat(tmp.resolve("w3.mzpeak")).doesNotExist();
        assertThat(tmp.resolve("c4.mzpeak")).doesNotExist();
        assertThat(tmp.resolve("w4.mzpeak")).doesNotExist();

        try (Session session = MzPeakWriter.openDirectory(tmp.resolve("session.mzpeak"), source.fileMetadata,
                source.scanSettings, new LinkedHashSet<>(), SPLIT)) {
            assertThatThrownBy(() -> session.addWavelengthSpectrum(withArrays(source.wavelengthSpectra.get(0),
                    List.of(undeclared)))).isInstanceOf(MzPeakException.class)
                    .hasMessageStartingWith("wavelength spectrum 0: auxiliary array 0: unit uses FOO:0002");
            assertThatThrownBy(() -> session.addSpectrum(withArrays(source.spectra.get(0), List.of(undeclared))))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessageStartingWith("spectrum 0: auxiliary array 0: unit uses FOO:0002");
            assertThatThrownBy(() -> session.addSpectrum(withArrays(source.spectra.get(0), List.of(badType))))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessageStartingWith("spectrum 0: auxiliary array 0: data type float64 is not one of");
            assertThatThrownBy(() -> session.addSpectrum(withArrays(source.spectra.get(0),
                    List.of(PLAIN, badCompression)))).isInstanceOf(MzPeakException.class)
                    .hasMessageStartingWith("spectrum 0: auxiliary array 1: compression zlib is not one of");
            assertThatThrownBy(() -> session.addWavelengthSpectrum(withArrays(source.wavelengthSpectra.get(0),
                    List.of(badCompression)))).isInstanceOf(MzPeakException.class)
                    .hasMessageStartingWith("wavelength spectrum 0: auxiliary array 0: compression zlib is not one of");
            assertThatThrownBy(() -> session.addSpectrum(withArrays(source.spectra.get(0), List.of(noAccession))))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessageStartingWith("spectrum 0: auxiliary array 0: name null is not one of");
            assertThatThrownBy(() -> session.addSpectrum(withArrays(source.spectra.get(0), List.of(PLAIN, unmapped))))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessageStartingWith("spectrum 0: auxiliary array 1: name MS:1002478 is not one of");
            assertThatThrownBy(() -> session.addWavelengthSpectrum(withArrays(source.wavelengthSpectra.get(0),
                    List.of(unmapped)))).isInstanceOf(MzPeakException.class)
                    .hasMessageStartingWith("wavelength spectrum 0: auxiliary array 0: name MS:1002478 is not one of");
        }
    }

    /**
     * metadata.cv_list declares the CV prefixes that only auxiliary arrays use (conformance.md: declare every CV
     * prefix used): of the name's unit, the unit and a param's accession and unit of a spectrum's array, and a param
     * of a chromatogram's and of a wavelength spectrum's array; none of these CVs is declared without the arrays.
     * A session declares them too.
     */
    @Test
    void theCvListDeclaresThePrefixesOnlyTheArraysUse() {
        Source plain = ForwardBackwardCorpusTest.readSource(HAS_UV);
        Path without = tmp.resolve("without.mzpeak");
        MzPeakWriter.writeDirectory(without, plain.spectra, plain.chromatograms, plain.wavelengthSpectra,
                plain.scanSettings, plain.fileMetadata, SPLIT);
        List<String> used = List.of("EFO", "OBI", "BFO", "HANCESTRO", "BTO", "PRIDE");
        assertThat(cvIds(without)).doesNotContainAnyElementsOf(used);

        AuxiliaryArray spectrumArray = new AuxiliaryArray(
                new Param("non-standard data array", "MS:1000786", "x", "BFO:0000001"), float64(1.0, 2.0, 3.0),
                FLOAT64, NONE, 3, "HANCESTRO:0000004",
                List.of(new Param("factor", "EFO:0000001", null, "BTO:0000001")), null);
        Source source = withSpectrumArrays(HAS_UV, List.of(spectrumArray));
        List<Chromatogram> chromatograms = new ArrayList<>(source.chromatograms);
        chromatograms.set(0, withArrays(chromatograms.get(0), List.of(array(PLAIN.name(), FLOAT64, NONE, null,
                List.of(new Param("assay", "OBI:0000070", null, null)), null))));
        List<WavelengthSpectrum> wavelengthSpectra = new ArrayList<>(source.wavelengthSpectra);
        wavelengthSpectra.set(0, withArrays(wavelengthSpectra.get(0), List.of(array(PLAIN.name(), FLOAT64, NONE,
                null, List.of(new Param("pride", "PRIDE:0000001", null, null)), null))));
        Path out = tmp.resolve("with.mzpeak");
        MzPeakWriter.writeDirectory(out, source.spectra, chromatograms, wavelengthSpectra, source.scanSettings,
                source.fileMetadata, SPLIT);
        assertThat(cvIds(out)).containsAll(used);

        Path session = tmp.resolve("session-cv.mzpeak");
        try (Session s = MzPeakWriter.openDirectory(session, source.fileMetadata, source.scanSettings,
                new LinkedHashSet<>(), SPLIT)) {
            s.addSpectrum(source.spectra.get(0));
            s.finish();
        }
        assertThat(cvIds(session)).contains("EFO", "BFO", "HANCESTRO", "BTO").doesNotContain("OBI", "PRIDE");
    }

    /**
     * The packed layout writes no auxiliary arrays and says so once, counting them, for spectra, chromatograms and
     * wavelength spectra, in a batch write and in a session.
     */
    @Test
    void thePackedLayoutWarnsAboutTheArrays() {
        Source source = withSpectrumArrays(HAS_UV, List.of(PLAIN, ZLIB));
        List<Chromatogram> chromatograms = new ArrayList<>(source.chromatograms);
        chromatograms.set(0, withArrays(chromatograms.get(0), List.of(PLAIN)));
        List<WavelengthSpectrum> wavelengthSpectra = new ArrayList<>(source.wavelengthSpectra);
        wavelengthSpectra.set(0, withArrays(wavelengthSpectra.get(0), List.of(PLAIN)));
        Path out = tmp.resolve("packed.mzpeak");
        assertThat(warnings(() -> MzPeakWriter.writeDirectory(out, source.spectra, chromatograms, wavelengthSpectra,
                source.scanSettings, source.fileMetadata, PACKED)))
                .containsExactly("the packed metadata layout writes no auxiliary arrays: 4 auxiliary arrays are not"
                        + " written");
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            assertThat(reader.getSpectrum(0).orElseThrow().auxiliaryArrays()).isEmpty();
        }

        Path session = tmp.resolve("packed-session.mzpeak");
        assertThat(warnings(() -> {
            try (Session s = MzPeakWriter.openDirectory(session, source.fileMetadata, source.scanSettings,
                    new LinkedHashSet<>(), PACKED)) {
                s.addSpectrum(withArrays(source.spectra.get(0), List.of(ZLIB)));
                s.finish();
            }
        })).containsExactly("the packed metadata layout writes no auxiliary arrays: 1 auxiliary array is not"
                + " written");
        assertThat(warnings(() -> MzPeakWriter.writeDirectory(tmp.resolve("split.mzpeak"), source.spectra,
                chromatograms, wavelengthSpectra, source.scanSettings, source.fileMetadata, SPLIT))).isEmpty();
        assertThat(warnings(() -> MzPeakWriter.writeDirectory(tmp.resolve("packed-none.mzpeak"),
                ForwardBackwardCorpusTest.readSource(HAS_UV).spectra, List.of(), List.of(), source.scanSettings,
                source.fileMetadata, PACKED))).isEmpty();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** {@code fixture} as read, its spectrum 0 given {@code arrays}. */
    static Source withSpectrumArrays(Path fixture, List<AuxiliaryArray> arrays) {
        Source source = ForwardBackwardCorpusTest.readSource(fixture);
        source.spectra.set(0, withArrays(source.spectra.get(0), arrays));
        return source;
    }

    static Spectrum withArrays(Spectrum s, List<AuxiliaryArray> arrays) {
        return new Spectrum(s.description(), s.mz(), s.intensity(), s.peaks(), s.arrays(), s.peakArrays(),
                s.intensityUnit(), s.peakIntensityUnit(), arrays);
    }

    private static Chromatogram withArrays(Chromatogram c, List<AuxiliaryArray> arrays) {
        return new Chromatogram(c.index(), c.id(), c.typeCurie(), c.time(), c.intensity(), c.intensityUnit(),
                c.arrays(), c.polarity(), c.dataProcessingRef(), c.parameters(), c.precursors(), c.timeUnit(),
                c.products(), arrays);
    }

    private static WavelengthSpectrum withArrays(WavelengthSpectrum w, List<AuxiliaryArray> arrays) {
        return new WavelengthSpectrum(w.index(), w.id(), w.time(), w.lambdaMax(), w.wavelength(), w.intensity(),
                w.parameters(), w.scans(), arrays);
    }

    /** One float64 value, 1.0, uncompressed bytes, with the given fields. */
    private static AuxiliaryArray array(Param name, String dataType, String compression, String unit,
                                        List<Param> parameters, String dataProcessingId) {
        return new AuxiliaryArray(name, float64(1.0), dataType, compression, 1, unit, parameters, dataProcessingId);
    }

    /** Spectrum 0 of small.unpacked given {@link #PLAIN} and {@code second}: the write is refused with {@code message}. */
    private void assertRefused(AuxiliaryArray second, String message) {
        Source source = withSpectrumArrays(SMALL, List.of(PLAIN, second));
        Path out = tmp.resolve("refused-" + System.nanoTime() + ".mzpeak");
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(out, source.spectra, List.of(), List.of(),
                source.scanSettings, source.fileMetadata, SPLIT)).isInstanceOf(MzPeakException.class)
                .hasMessageStartingWith(message);
        assertThat(out).doesNotExist();
    }

    /** {@code actual} are {@code expected} as written: each field equal, the bytes compared by value. */
    static void assertWritten(List<AuxiliaryArray> actual, List<AuxiliaryArray> expected) {
        assertThat(actual).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            AuxiliaryArray a = actual.get(i);
            AuxiliaryArray e = expected.get(i);
            assertThat(a.name()).as("name of %d", i).isEqualTo(e.name());
            assertThat(a.data()).as("bytes of %d", i).isEqualTo(e.data());
            assertThat(a.dataType()).as("data type of %d", i).isEqualTo(e.dataType());
            assertThat(a.compression()).as("compression of %d", i).isEqualTo(e.compression());
            assertThat(a.items()).as("items of %d", i).isEqualTo(e.items());
            assertThat(a.unit()).as("unit of %d", i).isEqualTo(e.unit());
            assertThat(a.parameters()).as("params of %d", i).isEqualTo(e.parameters());
            assertThat(a.dataProcessingId()).as("data processing of %d", i).isEqualTo(e.dataProcessingId());
            assertThat(a.doubles()).as("values of %d", i).containsExactly(1.0, 2.0, 3.0);
        }
    }

    /** The writer's warnings about auxiliary arrays during {@code write}. */
    private static List<String> warnings(Runnable write) {
        return ArrayIndexTest.captureLog(MzPeakWriter.class, write).stream()
                .filter(r -> r.getLevel() == Level.WARNING).map(LogRecord::getMessage)
                .filter(m -> m.contains("auxiliary")).toList();
    }

    /** The ids of the metadata.cv_list of {@code dataset}, as written. */
    private static List<String> cvIds(Path dataset) {
        try (MzPeakReader reader = MzPeakReader.open(dataset)) {
            return reader.manifest().cvList().stream().map(MzPeakManifest.CvEntry::id).toList();
        }
    }

    private static List<Group> rows(Path member) {
        List<Group> rows = new ArrayList<>();
        ParquetGroups.forEach(member, rows::add);
        return rows;
    }

    static byte[] float64(double... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Double.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (double v : values) {
            buffer.putDouble(v);
        }
        return buffer.array();
    }

    static byte[] zlib(byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(out)) {
            deflater.write(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
