//! rdump: dump an mzPeak dataset with the Rust reference reader (mzpeak_prototyping) as JSONL + LE f64 .bin.
//! Note: ion mobility of centroid peaks in spectra_peaks is not exposed by the reference API (CentroidPeak; the
//! IonMobilityAware peak types give PeakDataLevel::Missing), so it is not dumped.
//! Usage: rdump <dataset> <outPrefix> [indicesFile | --signal-range <start> <end>] (indices: one spectrum index per
//! line; chromatograms etc. always dumped; --signal-range adds the range read extract_signal(start..end))
use std::collections::{BTreeMap, HashMap};
use std::fs::File;
use std::io::{self, BufWriter, Write};
use std::panic::{AssertUnwindSafe, catch_unwind};

use arrow::array::{Array, ArrayRef, AsArray};
use arrow::compute::cast;
use arrow::datatypes::{DataType, Float64Type, UInt64Type};
use mzdata::prelude::*;
use mzdata::spectrum::{BinaryArrayMap, PeakDataLevel};
use mzpeak_prototyping::MzPeakReader;
use mzpeak_prototyping::archive::{ArchiveReader, DispatchArchiveSource};
use serde_json::{Value, json};

struct Bin {
    w: BufWriter<File>,
    off: u64,
}
impl Bin {
    fn put(&mut self, a: &[f64]) -> u64 {
        let o = self.off;
        for v in a {
            self.w.write_all(&v.to_le_bytes()).unwrap();
        }
        self.off += a.len() as u64;
        o
    }
}

fn arrays_json(bin: &mut Bin, m: &BinaryArrayMap) -> Value {
    let mut out = serde_json::Map::new();
    for (k, v) in m.iter() {
        let key = format!("{}", k);
        match v.to_f64() {
            Ok(a) => {
                let off = bin.put(&a);
                out.insert(
                    key,
                    json!({"n": a.len(), "off": off, "dtype": format!("{:?}", v.dtype), "unit": format!("{:?}", v.unit), "ctype": format!("{:?}", k)}),
                );
            }
            Err(e) => {
                out.insert(key, json!({"error": format!("{e}"), "dtype": format!("{:?}", v.dtype)}));
            }
        }
    }
    Value::Object(out)
}

fn peaks_json(bin: &mut Bin, p: &PeakDataLevel<mzpeaks::CentroidPeak, mzpeaks::DeconvolutedPeak>) -> Value {
    match p {
        PeakDataLevel::Missing => json!({"level": "missing"}),
        PeakDataLevel::RawData(m) => json!({"level": "raw", "arrays": arrays_json(bin, m)}),
        PeakDataLevel::Centroid(ps) => {
            let mz: Vec<f64> = ps.iter().map(|x| x.mz).collect();
            let it: Vec<f64> = ps.iter().map(|x| x.intensity as f64).collect();
            json!({"level": "centroid", "n": mz.len(), "mz_off": bin.put(&mz), "int_off": bin.put(&it)})
        }
        PeakDataLevel::Deconvoluted(ps) => {
            let mz: Vec<f64> = ps.iter().map(|x| x.neutral_mass).collect();
            let it: Vec<f64> = ps.iter().map(|x| x.intensity as f64).collect();
            let z: Vec<f64> = ps.iter().map(|x| x.charge as f64).collect();
            json!({"level": "deconvoluted", "n": mz.len(), "mass_off": bin.put(&mz), "int_off": bin.put(&it), "z_off": bin.put(&z)})
        }
    }
}

fn spec_json(bin: &mut Bin, i: usize, spec: &mzdata::spectrum::MultiLayerSpectrum) -> Value {
    let d = spec.description();
    let mut j = json!({"kind": "S", "index": i, "id": d.id, "sidx": d.index, "ms_level": d.ms_level,
        "rt": spec.start_time(), "polarity": format!("{:?}", d.polarity),
        "continuity": format!("{:?}", d.signal_continuity),
        "desc": serde_json::to_value(d).unwrap_or(Value::Null)});
    let mut prec = Vec::new();
    for p in d.precursor.iter() {
        let ions: Vec<Value> = p.ions.iter().map(|x| json!({"mz": x.mz, "charge": x.charge, "intensity": x.intensity, "im": x.ion_mobility()})).collect();
        prec.push(json!({"target": p.isolation_window.target, "lower": p.isolation_window.lower_bound,
            "upper": p.isolation_window.upper_bound, "iso_flags": format!("{:?}", p.isolation_window.flags),
            "methods": p.activation.methods().iter().map(|m| format!("{:?}", m)).collect::<Vec<_>>(),
            "energy": p.activation.energy, "precursor_id": p.precursor_id, "ions": ions}));
    }
    j["prec"] = Value::Array(prec);
    j["scan_im"] = json!(d.acquisition.first_scan().and_then(|s| s.ion_mobility()));
    if let Some(a) = spec.raw_arrays() {
        j["arrays"] = arrays_json(bin, a);
    }
    if let Some(p) = spec.peaks.as_ref() {
        let mz: Vec<f64> = p.iter().map(|x| x.mz).collect();
        let it: Vec<f64> = p.iter().map(|x| x.intensity as f64).collect();
        j["spec_peaks"] = json!({"n": mz.len(), "mz_off": bin.put(&mz), "int_off": bin.put(&it)});
    }
    j
}

/// The range read `extract_signal(start..end)`: one `R` record per spectrum index of its batches, with the fields of
/// the batches' struct (`columns`), the m/z and intensity values (`mz_off`, `int_off`; a null as NaN) and the number
/// of non-null intensities (`intensity_non_null`); or one `R` record with `error` or `panic`.
fn signal_range_json(reader: &mut MzPeakReader, bin: &mut Bin, start: u64, end: u64) -> Vec<Value> {
    let read = catch_unwind(AssertUnwindSafe(|| -> Result<Vec<Value>, String> {
        let (batches, _) = reader.extract_signal(start..end, None, None, None).map_err(|e| format!("{e}"))?;
        let mut columns: Vec<String> = Vec::new();
        let mut rows: BTreeMap<u64, (Vec<f64>, Vec<f64>, usize)> = BTreeMap::new();
        for batch in batches {
            let batch = batch.map_err(|e| format!("{e}"))?;
            let root = batch.column(0).as_struct();
            columns = root.column_names().iter().map(|c| c.to_string()).collect();
            let column = |name: &str, to: &DataType| -> Result<Option<ArrayRef>, String> {
                match root.column_by_name(name) {
                    Some(c) => cast(c, to).map(Some).map_err(|e| format!("{e}")),
                    None => Ok(None),
                }
            };
            let index = column("spectrum_index", &DataType::UInt64)?.ok_or("no spectrum_index column")?;
            let index = index.as_primitive::<UInt64Type>();
            let mz = column("mz", &DataType::Float64)?;
            let intensity = column("intensity", &DataType::Float64)?;
            let value = |a: &Option<ArrayRef>, i: usize| -> Option<f64> {
                a.as_ref().and_then(|a| {
                    let a = a.as_primitive::<Float64Type>();
                    if a.is_null(i) { None } else { Some(a.value(i)) }
                })
            };
            for i in 0..index.len() {
                let entry = rows.entry(index.value(i)).or_default();
                entry.0.push(value(&mz, i).unwrap_or(f64::NAN));
                let v = value(&intensity, i);
                if v.is_some() {
                    entry.2 += 1;
                }
                entry.1.push(v.unwrap_or(f64::NAN));
            }
        }
        Ok(rows
            .into_iter()
            .map(|(index, (mz, intensity, non_null))| {
                json!({"kind": "R", "index": index, "columns": columns, "n": mz.len(), "mz_off": bin.put(&mz),
                    "int_off": bin.put(&intensity), "intensity_non_null": non_null})
            })
            .collect())
    }));
    match read {
        Ok(Ok(records)) => records,
        Ok(Err(e)) => vec![json!({"kind": "R", "error": e})],
        Err(p) => vec![json!({"kind": "R", "panic": pmsg(p)})],
    }
}

fn dump_mzml(path: &std::path::Path, out: &mut BufWriter<File>, bin: &mut Bin) -> io::Result<()> {
    let mut reader = mzdata::MzMLReader::new_indexed(File::open(path)?);
    let n = reader.len();
    let nc = reader.count_chromatograms();
    writeln!(out, "{}", json!({"kind": "HEADER", "source": "mzML", "n_spectra": n, "n_chromatograms": nc}))?;
    for i in 0..n {
        let r = catch_unwind(AssertUnwindSafe(|| match reader.get_spectrum_by_index(i) {
            Some(s) => spec_json(bin, i, &s),
            None => json!({"kind": "S", "index": i, "none": true}),
        }));
        writeln!(out, "{}", r.unwrap_or_else(|p| json!({"kind": "S", "index": i, "panic": pmsg(p)})))?;
    }
    for i in 0..nc {
        let r = catch_unwind(AssertUnwindSafe(|| match reader.get_chromatogram_by_index(i) {
            Some(c) => json!({"kind": "C", "index": i, "id": c.id(), "cidx": c.index(),
                "ctype": format!("{:?}", c.chromatogram_type()),
                "desc": serde_json::to_value(c.description()).unwrap_or(Value::Null),
                "arrays": arrays_json(bin, &c.arrays)}),
            None => json!({"kind": "C", "index": i, "none": true}),
        }));
        writeln!(out, "{}", r.unwrap_or_else(|p| json!({"kind": "C", "index": i, "panic": pmsg(p)})))?;
    }
    writeln!(out, "{}", json!({"kind": "END"}))?;
    Ok(())
}

fn pmsg(e: Box<dyn std::any::Any + Send>) -> String {
    if let Some(s) = e.downcast_ref::<String>() {
        s.clone()
    } else if let Some(s) = e.downcast_ref::<&str>() {
        s.to_string()
    } else {
        "panic".into()
    }
}

fn main() -> io::Result<()> {
    env_logger::init();
    let args: Vec<String> = std::env::args().collect();
    let path = std::path::PathBuf::from(&args[1]);
    let prefix = &args[2];
    let mut out = BufWriter::new(File::create(format!("{prefix}.jsonl"))?);
    let mut bin = Bin { w: BufWriter::new(File::create(format!("{prefix}.bin"))?), off: 0 };
    if std::env::var("RDUMP_PANIC_HOOK").is_err() {
        std::panic::set_hook(Box::new(|_| {}));
    }
    if path.to_string_lossy().to_lowercase().ends_with(".mzml") {
        dump_mzml(&path, &mut out, &mut bin)?;
        out.flush()?;
        bin.w.flush()?;
        return Ok(());
    }
    let opened = catch_unwind(|| -> io::Result<MzPeakReader> {
        let archive = ArchiveReader::<DispatchArchiveSource>::from_path_with_decryption(path.clone(), HashMap::default())?;
        MzPeakReader::from_archive_reader(archive, Some(path.clone()))
    });
    let mut reader = match opened {
        Ok(Ok(r)) => r,
        Ok(Err(e)) => {
            writeln!(out, "{}", json!({"kind": "OPEN_ERROR", "error": format!("{e}")}))?;
            return Ok(());
        }
        Err(p) => {
            writeln!(out, "{}", json!({"kind": "OPEN_PANIC", "error": pmsg(p)}))?;
            return Ok(());
        }
    };
    let n = reader.len();
    let nc = reader.len_chromatograms();
    let nw = reader.len_wavelength_spectra();
    let (tlo, thi) = catch_unwind(AssertUnwindSafe(|| reader.observed_time_range())).unwrap_or((None, None));
    let (mlo, mhi) = catch_unwind(AssertUnwindSafe(|| reader.observed_mz_range())).unwrap_or((None, None));
    writeln!(out, "{}", json!({"kind": "HEADER", "n_spectra": n, "n_chromatograms": nc, "n_wavelength": nw,
        "obs_t": [tlo, thi], "obs_mz": [mlo, mhi]}))?;
    let range: Option<(u64, u64)> = match args.get(3).map(|a| a.as_str()) {
        Some("--signal-range") => Some((args[4].parse().unwrap(), args[5].parse().unwrap())),
        _ => None,
    };
    let sel: Vec<usize> = match args.get(3) {
        Some(f) if range.is_none() => std::fs::read_to_string(f)?.lines().filter(|l| !l.trim().is_empty()).map(|l| l.trim().parse().unwrap()).collect(),
        _ => (0..n).collect(),
    };
    for i in sel {
        let r = catch_unwind(AssertUnwindSafe(|| -> Value {
            let Some(spec) = reader.get_spectrum(i) else {
                return json!({"kind": "S", "index": i, "none": true});
            };
            let mut j = spec_json(&mut bin, i, &spec);
            // separate guard: get_spectrum_peaks_for panics for spectra absent from spectra_peaks
            match catch_unwind(AssertUnwindSafe(|| reader.get_spectrum_peaks_for(i as u64))) {
                Ok(Ok(Some(PeakDataLevel::Missing))) | Ok(Ok(None)) => {}
                Ok(Ok(Some(p))) => { j["peaks"] = peaks_json(&mut bin, &p); }
                Ok(Err(e)) => { j["peaks_err"] = json!(format!("{e}")); }
                Err(p) => { j["peaks_panic"] = json!(pmsg(p)); }
            }
            j
        }));
        let v = r.unwrap_or_else(|p| json!({"kind": "S", "index": i, "panic": pmsg(p)}));
        writeln!(out, "{v}")?;
    }
    for i in 0..nc {
        let r = catch_unwind(AssertUnwindSafe(|| -> Value {
            match reader.get_chromatogram(i) {
                None => json!({"kind": "C", "index": i, "none": true}),
                Some(c) => json!({"kind": "C", "index": i, "id": c.id(), "cidx": c.index(),
                    "ctype": format!("{:?}", c.chromatogram_type()),
                    "desc": serde_json::to_value(c.description()).unwrap_or(Value::Null),
                    "arrays": arrays_json(&mut bin, &c.arrays)}),
            }
        }));
        let v = r.unwrap_or_else(|p| json!({"kind": "C", "index": i, "panic": pmsg(p)}));
        writeln!(out, "{v}")?;
    }
    for (which, idx) in [("TIC", 0usize), ("BPC", 1usize)] {
        let r = catch_unwind(AssertUnwindSafe(|| -> Value {
            let c = if idx == 0 { reader.encoded_tic() } else { reader.encoded_bpc() };
            match c {
                Ok(c) => json!({"kind": "T", "which": which, "id": c.id(), "arrays": arrays_json(&mut bin, &c.arrays)}),
                Err(e) => json!({"kind": "T", "which": which, "error": format!("{e}")}),
            }
        }));
        let v = r.unwrap_or_else(|p| json!({"kind": "T", "which": which, "panic": pmsg(p)}));
        writeln!(out, "{v}")?;
    }
    for i in 0..nw {
        let r = catch_unwind(AssertUnwindSafe(|| -> Value {
            match reader.get_wavelength_spectrum(i) {
                None => {
                    // diagnose which half failed
                    let m = match reader.get_wavelength_spectrum_metadata(i as u64) { Ok(Some(_)) => "some".to_string(), Ok(None) => "none".to_string(), Err(e) => format!("err: {e}") };
                    let a = match reader.get_wavelength_spectrum_arrays(i as u64) { Ok(Some(_)) => "some".to_string(), Ok(None) => "none".to_string(), Err(e) => format!("err: {e}") };
                    json!({"kind": "W", "index": i, "none": true, "metadata": m, "arrays": a})
                }
                Some(s) => {
                    let d = s.description();
                    let mut j = json!({"kind": "W", "index": i, "id": d.id, "rt": s.start_time(),
                        "desc": serde_json::to_value(d).unwrap_or(Value::Null)});
                    if let Some(a) = s.raw_arrays() {
                        j["arrays"] = arrays_json(&mut bin, a);
                    }
                    j
                }
            }
        }));
        let v = r.unwrap_or_else(|p| json!({"kind": "W", "index": i, "panic": pmsg(p)}));
        writeln!(out, "{v}")?;
    }
    if let Some((start, end)) = range {
        for v in signal_range_json(&mut reader, &mut bin, start, end) {
            writeln!(out, "{v}")?;
        }
    }
    writeln!(out, "{}", json!({"kind": "END"}))?;
    out.flush()?;
    bin.w.flush()?;
    Ok(())
}
