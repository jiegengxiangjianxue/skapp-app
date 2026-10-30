//! pyo3 绑定：Python 可直接调用 Rust 计算核心
use pyo3::prelude::*;
use pyo3::types::PyModule;
use skapp_core::{composite, cross_rank, sma, Bar, Quote};

#[pyfunction]
fn py_sma(closes: Vec<f64>, n: usize) -> Vec<f64> { sma(&closes, n) }

#[pyfunction]
fn py_cross_rank(vals: Vec<f64>) -> Vec<f64> { cross_rank(&vals) }

#[pyfunction]
fn py_composite(matrix: Vec<Vec<f64>>, weights: Vec<f64>) -> Vec<f64> { composite(&matrix, &weights) }

#[pyclass]
struct PyBar { #[pyo3(get, set)] symbol: String, #[pyo3(get, set)] date: String }

#[pymodule]
fn skapp_core(m: &Bound<'_, PyModule>) -> PyResult<()> {
    m.add_function(wrap_pyfunction!(py_sma, m)?)?;
    m.add_function(wrap_pyfunction!(py_cross_rank, m)?)?;
    m.add_function(wrap_pyfunction!(py_composite, m)?)?;
    m.add_class::<PyBar>()?;
    Ok(())
}
