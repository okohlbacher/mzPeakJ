package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.mzpeak.io.MzPeakManifest.ColumnMapping;
import org.mzpeak.model.Activation;
import org.mzpeak.model.CvTerms;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Param;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decodes one precursor row of the spectrum metadata into a {@link PrecursorPart}; columns are resolved by
 * accession. The selected ions are attached by {@link PrecursorJoin}. Unconsumed scalar columns of the activation
 * scope become activation Params; those of the precursor and isolation-window scopes have no place in the model and
 * are logged at DEBUG and dropped (split layout only; packed scopes have no leftovers).
 */
final class PrecursorRowDecoder {

    private static final System.Logger LOG = System.getLogger(PrecursorRowDecoder.class.getName());

    // precursor facet
    private static final String F_PRECURSOR_INDEX = "precursor_index";
    private static final String F_PRECURSOR_ID = "precursor_id";
    private static final String F_ACTIVATION = "activation";
    private static final String F_ISOLATION = "isolation_window";
    private static final String ISO_TARGET = "MS:1000827";
    private static final String ISO_LOWER = "MS:1000828";
    private static final String ISO_UPPER = "MS:1000829";
    private static final String DISSOCIATION_METHOD = "MS:1000044";
    private static final String COLLISION_ENERGY = "MS:1000045";

    private PrecursorRowDecoder() {
    }

    /** Decodes one precursor row. */
    static PrecursorPart decode(FacetRow precursor) {
        FacetRow iso = precursor.child(F_ISOLATION);
        IsolationWindow window = iso == null ? null : isolationWindow(precursor, iso);
        FacetRow activationRow = precursor.child(F_ACTIVATION);
        Activation activation = activationRow == null ? Activation.EMPTY : activation(activationRow);
        ColumnResolver columns = precursor.columns();
        Group g = precursor.group();
        dropLeftovers(precursor, precursor, "precursor", Set.of(F_PRECURSOR_ID));
        return new PrecursorPart(
                columns.longValue(g, F_PRECURSOR_INDEX),
                columns.stringValue(g, F_PRECURSOR_ID),
                window,
                activation);
    }

    private static IsolationWindow isolationWindow(FacetRow precursor, FacetRow iso) {
        ColumnResolver columns = iso.columns();
        Group g = iso.group();
        String target = columns.resolve(ISO_TARGET);
        String lower = columns.resolve(ISO_LOWER);
        String upper = columns.resolve(ISO_UPPER);
        dropLeftovers(precursor, iso, F_ISOLATION, consumed(target, lower, upper));
        return new IsolationWindow(
                columns.doubleValue(g, target),
                columns.doubleValue(g, lower),
                columns.doubleValue(g, upper));
    }

    /**
     * The activation's Params, in this order: the dissociation method term marker (MS:1000044) as
     * {@code Param(name, cell)}, named from {@link CvTerms#DISSOCIATION_METHODS} (a null name for a CURIE it does not
     * list); the collision energy (MS:1000045) as {@code Param("collision energy", MS:1000045, value, the mapping's
     * unit)}; the scope's leftovers ({@link ColumnResolver#leftovers}); then the {@code parameters} list, without the
     * entries whose accession one of those columns gave (a column wins over a list entry). In the packed layout only
     * the list is present.
     */
    private static Activation activation(FacetRow activation) {
        ColumnResolver columns = activation.columns();
        Group g = activation.group();
        List<Param> out = new ArrayList<>();
        String method = columns.resolve(DISSOCIATION_METHOD);
        String curie = columns.stringValue(g, method);
        if (curie != null) {
            out.add(new Param(CvTerms.DISSOCIATION_METHODS.get(curie), curie, null, null));
        }
        String energy = columns.resolve(COLLISION_ENERGY);
        Double value = columns.doubleValue(g, energy);
        if (value != null) {
            String unit = columns.mapping(energy).map(ColumnMapping::unit).orElse(null);
            out.add(new Param("collision energy", COLLISION_ENERGY, value, unit));
        }
        out.addAll(columns.leftovers(g, consumed(method, energy)));
        Set<String> fromColumns = new HashSet<>();
        for (Param p : out) {
            if (p.accession() != null) {
                fromColumns.add(p.accession());
            }
        }
        for (Param p : columns.params(g)) {
            if (p.accession() == null || !fromColumns.contains(p.accession())) {
                out.add(p);
            }
        }
        return new Activation(out);
    }

    /**
     * Logs at DEBUG, and drops, the leftovers of {@code scope}, a scope of {@code precursor} named {@code name} that
     * has no place for them in the model.
     */
    private static void dropLeftovers(FacetRow precursor, FacetRow scope, String name, Set<String> consumed) {
        List<Param> dropped = scope.columns().leftovers(scope.group(), consumed);
        if (!dropped.isEmpty()) {
            LOG.log(Level.DEBUG, () -> scope.columns().member() + ": dropped the " + name + "-scope columns of the"
                    + " precursor of source_index " + precursor.key() + ", which have no place in the model: "
                    + dropped);
        }
    }

    /** The non-null {@code fields}. */
    private static Set<String> consumed(String... fields) {
        Set<String> out = new HashSet<>();
        for (String field : fields) {
            if (field != null) {
                out.add(field);
            }
        }
        return out;
    }

    /** One precursor record without its selected ions. */
    record PrecursorPart(Long precursorIndex, String precursorId, IsolationWindow isolationWindow,
                         Activation activation) {
    }
}
