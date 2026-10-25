//! c-imagine native bridge.
//!
//! Two layers:
//! - [`ring`]: the SPSC byte ring used for zero-copy frame handoff between
//!   Paper server threads (producer/consumer on the Java side via JNI) and
//!   the Rust module threads.
//! - this file: the versioned extern "C" surface Java code links against
//!   through JNI `native` declarations. Functions are intentionally tiny,
//!   panic-free (catch_unwind at the boundary), and never allocate except on
//!   ring creation.

pub mod ring;

use cim_core::BRIDGE_ABI_VERSION;

/// Magic at the head of every bridge handshake frame: "CIMB".
pub const BRIDGE_MAGIC: u32 = 0x4349_4D42;

/// Handshake header exchanged as the first frame on any new ring pair.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BridgeHeader {
    pub magic: u32,
    pub abi_version: u32,
    /// Bitset of negotiated features; 0 for the M0 base profile.
    pub features: u64,
}

impl BridgeHeader {
    pub fn base() -> Self {
        Self {
            magic: BRIDGE_MAGIC,
            abi_version: BRIDGE_ABI_VERSION,
            features: 0,
        }
    }

    pub fn is_valid(&self) -> bool {
        self.magic == BRIDGE_MAGIC && self.abi_version == BRIDGE_ABI_VERSION
    }
}

// --- extern "C" surface (loaded by the Paper plugin via System.load) -------

/// Report the ABI version so the plugin can refuse mismatched libraries early.
#[no_mangle]
pub extern "C" fn cim_bridge_abi_version() -> u32 {
    BRIDGE_ABI_VERSION
}

/// Create a ring of `capacity` bytes (rounded up to a power of two).
/// Returns null on invalid size/allocation failure.
///
/// # Safety
/// The returned pointer is owned by the caller and must be released with
/// [`cim_bridge_ring_free`] exactly once, from a single thread.
#[no_mangle]
pub unsafe extern "C" fn cim_bridge_ring_new(capacity: usize) -> *mut ring::SpscRing {
    match ring::SpscRing::new(capacity) {
        Ok(r) => Box::into_raw(Box::new(r)),
        Err(_) => std::ptr::null_mut(),
    }
}

/// # Safety
/// `ring` must come from [`cim_bridge_ring_new`] and not be used afterwards.
#[no_mangle]
pub unsafe extern "C" fn cim_bridge_ring_free(ring: *mut ring::SpscRing) {
    if !ring.is_null() {
        drop(Box::from_raw(ring));
    }
}

/// Write a frame. Returns 0 on success, negative on failure:
/// -1 = full, -2 = frame too large, -3 = null pointer.
///
/// # Safety
/// `ring` must be valid; `data` must point to `len` readable bytes. Single
/// producer only.
#[no_mangle]
pub unsafe extern "C" fn cim_bridge_ring_write(
    ring: *mut ring::SpscRing,
    data: *const u8,
    len: usize,
) -> i32 {
    if ring.is_null() {
        return -3;
    }
    let bytes = if data.is_null() || len == 0 {
        &[][..]
    } else {
        std::slice::from_raw_parts(data, len)
    };
    let ring_ref = &*ring;
    match ring_ref.write_frame(bytes) {
        Ok(()) => 0,
        Err(ring::RingError::Full) => -1,
        Err(ring::RingError::TooLarge) => -2,
        // Write never reports Empty; arm kept explicit for exhaustiveness.
        Err(ring::RingError::Empty) => -1,
    }
}

/// Read a frame into `out` (capacity `cap`). On success returns the payload
/// length and sets `*out_len`. Returns -1 when empty, -2 when `out` is too
/// small (frame left intact), -3 on null pointer.
///
/// # Safety
/// `ring` valid; `out`/`out_len` writable. Single consumer only.
#[no_mangle]
pub unsafe extern "C" fn cim_bridge_ring_read(
    ring: *mut ring::SpscRing,
    out: *mut u8,
    cap: usize,
    out_len: *mut usize,
) -> i32 {
    if ring.is_null() || out_len.is_null() {
        return -3;
    }
    let mut scratch: Vec<u8> = Vec::new();
    let mut stack = [0u8; 4096];
    let ring_ref = &*ring;
    match ring_ref.read_frame_into(&mut stack, &mut scratch) {
        Ok(payload) => {
            if payload.len() > cap {
                return -2;
            }
            if !payload.is_empty() {
                std::ptr::copy_nonoverlapping(payload.as_ptr(), out, payload.len());
            }
            *out_len = payload.len();
            0
        }
        Err(ring::RingError::Empty) => -1,
        Err(ring::RingError::Full) => -1,
        Err(ring::RingError::TooLarge) => -2,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn header_roundtrip_and_validation() {
        let h = BridgeHeader::base();
        assert!(h.is_valid());
        assert_eq!(h.magic, 0x4349_4D42);
        let corrupt = BridgeHeader {
            abi_version: 99,
            ..h
        };
        assert!(!corrupt.is_valid());
    }

    #[test]
    fn abi_version_matches_core() {
        assert_eq!(cim_bridge_abi_version(), cim_core::BRIDGE_ABI_VERSION);
    }
}
