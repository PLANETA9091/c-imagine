//! c-imagine native bridge.
//!
//! Two layers:
//! - [`ring`]: the SPSC byte ring used for zero-copy frame handoff between
//!   Paper server threads (producer/consumer on the Java side via JNI) and
//!   the Rust module threads.
//! - this file: the versioned extern "C" surface Java code links against
//!   through JNI `native` declarations. Functions are intentionally tiny and
//!   panic-free: every entry point unwinds through
//!   [`std::panic::catch_unwind`] so a Rust panic can never abort the host
//!   JVM. No allocation happens on the steady-state path; the only exceptions
//!   are ring creation and the >4 KiB read spill buffer.

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
//
// Return codes shared by the read/write pair:
// 0 = success, -1 = empty/full, -2 = payload does not fit the caller buffer,
// -3 = invalid argument (null pointer, zero-length write), -4 = internal
// panic (never escapes into the JVM).

/// Report the ABI version so the plugin can refuse mismatched libraries early.
#[no_mangle]
pub extern "C" fn cim_bridge_abi_version() -> u32 {
    BRIDGE_ABI_VERSION
}

/// Create a ring of `capacity` bytes (rounded up to a power of two).
/// Returns null on invalid size/allocation failure/panic.
///
/// # Safety
/// The returned pointer is owned by the caller and must be released with
/// [`cim_bridge_ring_free`] exactly once, from a single thread.
#[no_mangle]
pub unsafe extern "C" fn cim_bridge_ring_new(capacity: usize) -> *mut ring::SpscRing {
    let res = std::panic::catch_unwind(AssertUnwindSafe(|| ring::SpscRing::new(capacity)));
    match res {
        Ok(Ok(r)) => Box::into_raw(Box::new(r)),
        // Invalid size, allocation failure, or panic: no ring.
        _ => std::ptr::null_mut(),
    }
}

/// # Safety
/// `ring` must come from [`cim_bridge_ring_new`] and not be used afterwards.
#[no_mangle]
pub unsafe extern "C" fn cim_bridge_ring_free(ring: *mut ring::SpscRing) {
    if ring.is_null() {
        return;
    }
    let res = std::panic::catch_unwind(AssertUnwindSafe(|| drop(Box::from_raw(ring))));
    if res.is_err() {
        // Drop panicked; nothing more we can safely do here — the important
        // part is that the panic did not unwind into the JVM.
    }
}

/// Write a frame. Returns 0 on success, negative on failure:
/// -1 = full, -2 = frame too large, -3 = invalid argument (null data with
/// nonzero len, or zero-length frames which would collide with the ring's
/// skip-record encoding), -4 = internal panic.
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
    // A zero-length record is the ring's SKIP encoding: user frames must
    // never produce it, or the reader would silently discard the rest of
    // the lap. Reject up front.
    if data.is_null() && len != 0 {
        return -3;
    }
    if len == 0 {
        return -3;
    }
    let res = std::panic::catch_unwind(AssertUnwindSafe(|| {
        let bytes = std::slice::from_raw_parts(data, len);
        let ring_ref = &*ring;
        match ring_ref.write_frame(bytes) {
            Ok(()) => 0,
            Err(ring::RingError::Full) => -1,
            Err(ring::RingError::TooLarge) => -2,
            Err(ring::RingError::Invalid) => -3,
            // Write never reports Empty; arm kept explicit for exhaustiveness.
            Err(ring::RingError::Empty) => -1,
        }
    }));
    res.unwrap_or(-4)
}

/// Read a frame into `out` (capacity `cap`). On success returns 0, the
/// payload length lands in `*out_len`. Returns -1 when empty, -2 when `out`
/// is too small (**frame left intact**, may be read again with a larger
/// buffer), -3 on null pointer, -4 on internal panic.
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
    if out.is_null() {
        return -3;
    }
    let res = std::panic::catch_unwind(AssertUnwindSafe(|| {
        let ring_ref = &*ring;
        // Peek the next frame WITHOUT consuming it, so a too-small `out`
        // reports -2 and leaves the frame readable by a later call.
        let len = match ring_ref.next_frame_len() {
            Ok(len) => len,
            Err(ring::RingError::Empty) => return -1,
            Err(_) => return -2,
        };
        if len > cap {
            return -2; // frame stays intact in the ring
        }
        let mut stack = [0u8; 4096];
        let mut spill: Vec<u8> = Vec::new();
        // `first` is sized to exactly the peeked length, so the spill path
        // inside read_frame_into can never trigger; the dummy spill buffer
        // just satisfies the borrow checker on a distinct allocation.
        let mut spill_for_call: Vec<u8> = Vec::new();
        let first: &mut [u8] = if len <= stack.len() {
            &mut stack[..len]
        } else {
            spill.resize(len, 0);
            &mut spill[..]
        };
        match ring_ref.read_frame_into(first, &mut spill_for_call) {
            Ok(payload) => {
                debug_assert_eq!(payload.len(), len);
                std::ptr::copy_nonoverlapping(payload.as_ptr(), out, payload.len());
                *out_len = payload.len();
                0
            }
            Err(ring::RingError::Empty) => -1,
            Err(_) => -2,
        }
    }));
    res.unwrap_or(-4)
}

use std::panic::AssertUnwindSafe;

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

    /// Regression: the FFI read must return -2 WITHOUT consuming the frame,
    /// then succeed on a retry with a big enough buffer.
    #[test]
    fn read_too_small_leaves_frame_intact() {
        let r = ring::SpscRing::new(256).unwrap();
        r.write_frame(&[7u8; 100]).unwrap();

        unsafe {
            let rp = &r as *const ring::SpscRing as *mut ring::SpscRing;
            let mut out_len: usize = 0;
            let mut small = [0u8; 10];
            let code = cim_bridge_ring_read(rp, small.as_mut_ptr(), small.len(), &mut out_len);
            assert_eq!(code, -2, "too-small buffer must report -2");

            // frame must still be there
            let mut big = [0u8; 128];
            let code = cim_bridge_ring_read(rp, big.as_mut_ptr(), big.len(), &mut out_len);
            assert_eq!(code, 0);
            assert_eq!(out_len, 100);
            assert!(big[..100].iter().all(|&b| b == 7));

            // and now empty
            let code = cim_bridge_ring_read(rp, big.as_mut_ptr(), big.len(), &mut out_len);
            assert_eq!(code, -1);
        }
    }

    /// Regression: a zero-length write used to create a skip record which
    /// made the reader discard every frame up to the lap boundary.
    #[test]
    fn zero_length_write_is_rejected() {
        let r = ring::SpscRing::new(256).unwrap();
        r.write_frame(&[1, 2, 3]).unwrap();
        unsafe {
            let rp = &r as *const ring::SpscRing as *mut ring::SpscRing;
            let code = cim_bridge_ring_write(rp, std::ptr::null(), 0);
            assert_eq!(code, -3, "zero-length write must be an invalid argument");
        }
        // The real frame must survive untouched.
        let mut spill = Vec::new();
        let mut first = [0u8; 8];
        assert_eq!(
            r.read_frame_into(&mut first, &mut spill).unwrap(),
            &[1, 2, 3]
        );
    }

    #[test]
    fn null_pointers_report_invalid_argument() {
        unsafe {
            assert_eq!(
                cim_bridge_ring_write(std::ptr::null_mut(), std::ptr::null(), 0),
                -3
            );
            assert_eq!(
                cim_bridge_ring_read(
                    std::ptr::null_mut(),
                    std::ptr::null_mut(),
                    0,
                    std::ptr::null_mut()
                ),
                -3
            );
        }
    }

    /// A ring_new with an absurd capacity must return null, not panic/abort.
    #[test]
    fn ring_new_rejects_absurd_capacity() {
        unsafe {
            let p = cim_bridge_ring_new(usize::MAX);
            assert!(p.is_null(), "usize::MAX must not panic next_power_of_two");
            cim_bridge_ring_free(p);
        }
    }
}
