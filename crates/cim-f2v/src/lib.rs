//! F2V v2 — modded→vanilla visual translation, rewritten in Rust.
//!
//! The original F2V (Java, `jefthebuilder/F2V`) proves vanilla clients can
//! render modded servers; v2 moves the pipeline inside Paper via c-imagine.
//! M0 scope: the mapping tables and the translation *plan* model that the
//! CRUSSTY net-pipeline hooks (M4) will execute. Subsystems land per
//! `docs/F2V_ANALYSIS.md` §5.

pub mod plan;
pub mod translate;

/// Errors surfaced by translation planning/execution.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum F2vError {
    #[error("no visual mapping for {0}")]
    Unmapped(String),
    #[error("registry snapshot missing: {0}")]
    MissingSnapshot(String),
    #[error("translation plan invalid: {0}")]
    InvalidPlan(String),
}
