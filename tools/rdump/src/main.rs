//! rdump: dump an mzPeak dataset with the Rust reference reader (mzpeak_prototyping) as JSONL + LE f64 .bin.
//! Note: ion mobility of centroid peaks in spectra_peaks is not exposed by the reference API (CentroidPeak; the
//! IonMobilityAware peak types give PeakDataLevel::Missing), so it is not dumped.
//! Usage: rdump <dataset> <outPrefix> [indicesFile] (indices: one spectrum index per line; chromatograms etc. always dumped)
use std::collections::HashMap;
use std::fs::File;
use std::io::{self, BufWriter, Write};
use std::panic::{AssertUnwindSafe, catch_unwind};

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
    let sel: Vec<usize> = match args.get(3) {
        Some(f) => std::fs::read_to_string(f)?.lines().filter(|l| !l.trim().is_empty()).map(|l| l.trim().parse().unwrap()).collect(),
        None => (0..n).collect(),
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
    writeln!(out, "{}", json!({"kind": "END"}))?;
    out.flush()?;
    bin.w.flush()?;
    Ok(())
}
