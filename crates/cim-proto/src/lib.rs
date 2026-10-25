//! c-imagine protocol primitives.
//!
//! Submodules:
//! - [`varint`]: VarInt / VarLong codec (every Minecraft packet is built on it).
//! - [`cursor`]: reading/writing cursor with the primitives the F2V v2
//!   translators need (strings, uuids, positions).
//! - [`frame`]: packet framing (length prefix) with optional zlib compression
//!   envelopes, used by the CRUSSTY net pipeline and the future visual bot.
//!
//! Everything here is allocation-conscious: hot paths return sized writes into
//! caller-owned buffers, no hidden buffers per packet.

pub mod cursor;
pub mod frame;
pub mod varint;

pub use cursor::{ReadCursor, WriteBuf};
pub use frame::{decode_frame, encode_frame, PacketFrame};
pub use varint::{
    read_varint, read_varlong, write_varint, write_varlong, MAX_VARINT_LEN, MAX_VARLONG_LEN,
};

/// Protocol-level errors.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum ProtoError {
    #[error("buffer ended early (needed {needed} bytes at offset {offset})")]
    UnexpectedEnd { needed: usize, offset: usize },
    #[error("varint too large (max {max} bytes)")]
    VarTooLarge { max: usize },
    #[error("string length {len} exceeds limit {limit}")]
    StringTooLong { len: u32, limit: u32 },
    #[error("frame payload too large: {0}")]
    FrameTooLarge(usize),
    #[error("zlib decompression failed or size mismatch (expected {expected})")]
    DecompressFailed { expected: usize },
    #[error("invalid frame: {0}")]
    InvalidFrame(String),
}
