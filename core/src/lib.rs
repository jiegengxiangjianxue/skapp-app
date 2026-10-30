//! skapp-core: 行情数据结构与因子计算加速
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Bar {
    pub symbol: String,
    pub date: String,
    pub open: f64,
    pub close: f64,
    pub high: f64,
    pub low: f64,
    pub volume: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Quote {
    pub symbol: String,
    pub price: f64,
    pub pct_chg: f64,
    pub volume: f64,
    pub ts: i64,
}

/// 简单移动平均
pub fn sma(closes: &[f64], n: usize) -> Vec<f64> {
    let mut out = vec![f64::NAN; closes.len()];
    if n == 0 { return out; }
    let mut sum = 0.0;
    for (i, &c) in closes.iter().enumerate() {
        sum += c;
        if i >= n { sum -= closes[i - n]; }
        if i + 1 >= n { out[i] = sum / n as f64; }
    }
    out
}

/// 收益率序列（前向简单收益）
pub fn returns(closes: &[f64]) -> Vec<f64> {
    closes.windows(2).map(|w| (w[1] - w[0]) / w[0]).collect()
}

/// 截面 rank（升序名次，归一 0..1），用于多因子加权融合
pub fn cross_rank(vals: &[f64]) -> Vec<f64> {
    let mut idx: Vec<usize> = (0..vals.len()).collect();
    idx.sort_by(|&a, &b| vals[a].partial_cmp(&vals[b]).unwrap());
    let mut ranks = vec![0.0; vals.len()];
    let n = vals.len() as f64;
    for (r, &i) in idx.iter().enumerate() {
        ranks[i] = if n > 1.0 { r as f64 / (n - 1.0) } else { 0.0 };
    }
    ranks
}

/// 多因子加权合成（因子矩阵 行=股票 列=因子，权重等长）
pub fn composite(matrix: &[Vec<f64>], weights: &[f64]) -> Vec<f64> {
    let n = matrix.len();
    let mut out = vec![0.0; n];
    for (i, row) in matrix.iter().enumerate() {
        let mut s = 0.0;
        let mut wsum = 0.0;
        for (j, &w) in weights.iter().enumerate() {
            if j < row.len() && row[j].is_finite() {
                s += row[j] * w;
                wsum += w.abs();
            }
        }
        out[i] = if wsum > 0.0 { s / wsum } else { 0.0 };
    }
    out
}

/// 涨停判断（板别阈值：主板10% / ST 5% / 创业板科创20%）
pub fn limit_up_pct(symbol: &str, is_st: bool) -> f64 {
    if symbol.starts_with("sz30") || symbol.starts_with("sh68") { 20.0 }
    else if is_st { 5.0 }
    else { 10.0 }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn test_sma() {
        let r = sma(&[1.0, 2.0, 3.0, 4.0], 2);
        assert!((r[1] - 1.5).abs() < 1e-9 && (r[3] - 3.5).abs() < 1e-9);
    }
    #[test]
    fn test_composite() {
        let c = composite(&[vec![1.0, 2.0], vec![3.0, 4.0]], &[1.0, 1.0]);
        assert_eq!(c, vec![1.5, 3.5]);
    }
}
