//! Single-producer / single-consumer byte ring with length-prefixed frames.
//!
//! Layout: `cap` bytes (power of two ≥ 64). Free-running `head`/`tail` usize
//! indices, wrapped by mask. Every record is `u32 be len | payload`.
//!
//! Lap (wrap) protocol — kept deliberately simple and symmetric:
//! - A frame is never split across the buffer end. When `cap - at` cannot fit
//!   the whole record:
//!   - ≥ 4 bytes remain: the producer writes a **skip record** (`len = 0`)
//!     and starts the frame at offset 0 of the next lap;
//!   - < 4 bytes remain: the producer zero-fills the tail (the reader treats
//!     any offset with fewer than 4 bytes left in the lap as end-of-lap).
//! - The reader, on `len == 0` or on a <4-byte tail, releases space up to the
//!   next lap boundary. Both sides therefore agree: **frames live wholly
//!   inside one lap**.
//!
//! Memory ordering: index stores are `Release`, loads `Acquire`, so payload
//! bytes written before the index store are fully visible to the consumer.

use std::cell::UnsafeCell;
use std::sync::atomic::{AtomicUsize, Ordering};

#[derive(Debug, PartialEq, Eq)]
pub enum RingError {
    Full,
    Empty,
    TooLarge,
    /// Semantically invalid operation (e.g. a zero-length user frame, which
    /// would collide with the ring's skip-record encoding).
    Invalid,
}

/// Cache-line padding so producer/consumer indices never share a line (or an
/// adjacent pair of lines: Intel's adjacent-line prefetcher pulls 128-byte
/// pairs, so 128B is the safe stride for false-parameter elimination).
#[repr(align(128))]
struct Padded(AtomicUsize);

/// The ring. Single producer, single consumer; both may run concurrently.
pub struct SpscRing {
    buf: UnsafeCell<Box<[u8]>>,
    head: Padded, // producer-owned (Release publishes frames)
    tail: Padded, // consumer-owned (Release releases space)
}

// Send+Sync: exactly one producer and one consumer, coordinated by the atomics
// above; UnsafeCell access only happens under the head/tail protocol.
unsafe impl Send for SpscRing {}
unsafe impl Sync for SpscRing {}

const MIN_CAPACITY: usize = 64;
const LEN_PREFIX: usize = 4;

impl SpscRing {
    /// Upper bound on ring capacity (1 TiB): anything larger is treated as
    /// invalid input rather than allowed to overflow `next_power_of_two`.
    const MAX_CAPACITY: usize = 1 << 40;

    /// Create with `capacity` bytes (rounded up to a power of two, min 64).
    pub fn new(capacity: usize) -> Result<Self, RingError> {
        if capacity > Self::MAX_CAPACITY {
            return Err(RingError::TooLarge);
        }
        let cap = capacity.max(MIN_CAPACITY).next_power_of_two();
        let buf = vec![0u8; cap].into_boxed_slice();
        Ok(Self {
            buf: UnsafeCell::new(buf),
            head: Padded(AtomicUsize::new(0)),
            tail: Padded(AtomicUsize::new(0)),
        })
    }

    fn capacity(&self) -> usize {
        // Capacity is fixed after construction.
        unsafe { (&*self.buf.get()).len() }
    }

    /// Largest payload that can always be written (one byte keeps empty/full
    /// distinguishable with free-running indices).
    pub fn max_frame(&self) -> usize {
        self.capacity() - LEN_PREFIX - 1
    }

    /// Write one frame. `Full` means "cannot fit right now"; the caller may
    /// spin or drop (the net pipeline decides per frame class).
    pub fn write_frame(&self, payload: &[u8]) -> Result<(), RingError> {
        if payload.is_empty() {
            // len == 0 is the SKIP record: writing it as a user frame would
            // make the reader release the whole rest of the lap.
            return Err(RingError::Invalid);
        }
        if payload.len() > self.max_frame() {
            return Err(RingError::TooLarge);
        }
        let cap = self.capacity();
        let need = LEN_PREFIX + payload.len();
        let head = self.head.0.load(Ordering::Relaxed);
        let tail = self.tail.0.load(Ordering::Acquire);
        let used = head.wrapping_sub(tail);
        let free = cap - 1 - used;
        let at = head & (cap - 1);
        let contiguous = cap - at;

        let consumed = if contiguous >= need {
            need
        } else {
            contiguous + need
        };
        if consumed > free {
            return Err(RingError::Full);
        }

        // Safety: single producer; the consumer never touches [head, tail).
        let buf = unsafe { &mut *self.buf.get() };
        let len_bytes = (payload.len() as u32).to_be_bytes();
        if contiguous >= need {
            buf[at..at + LEN_PREFIX].copy_from_slice(&len_bytes);
            buf[at + LEN_PREFIX..at + need].copy_from_slice(payload);
            self.head.0.store(head + need, Ordering::Release);
        } else if contiguous >= LEN_PREFIX {
            // Skip record to the lap boundary, frame starts at 0.
            buf[at..at + LEN_PREFIX].copy_from_slice(&0u32.to_be_bytes());
            buf[..LEN_PREFIX].copy_from_slice(&len_bytes);
            buf[LEN_PREFIX..need].copy_from_slice(payload);
            self.head
                .0
                .store(head + contiguous + need, Ordering::Release);
        } else {
            // Fewer than 4 bytes: zero padding, frame starts at 0.
            for b in &mut buf[at..] {
                *b = 0;
            }
            buf[..LEN_PREFIX].copy_from_slice(&len_bytes);
            buf[LEN_PREFIX..need].copy_from_slice(payload);
            self.head
                .0
                .store(head + contiguous + need, Ordering::Release);
        }
        Ok(())
    }

    /// Read the next frame into `first`, spilling to `spill` when larger.
    /// The returned slice borrows `first` or `spill`. `Empty` also covers
    /// "only lap-boundary padding remains".
    pub fn read_frame_into<'a>(
        &self,
        first: &'a mut [u8],
        spill: &'a mut Vec<u8>,
    ) -> Result<&'a [u8], RingError> {
        let cap = self.capacity();
        let head = self.head.0.load(Ordering::Acquire);
        let mut tail = self.tail.0.load(Ordering::Relaxed);
        let buf = unsafe { &*self.buf.get() };

        loop {
            if head == tail {
                self.tail.0.store(tail, Ordering::Release);
                return Err(RingError::Empty);
            }
            let at = tail & (cap - 1);
            // End-of-lap padding (<4 bytes) or skip record: next lap.
            if at + LEN_PREFIX > cap {
                tail = (tail / cap + 1) * cap;
                self.tail.0.store(tail, Ordering::Release);
                continue;
            }
            let len = u32::from_be_bytes(buf[at..at + LEN_PREFIX].try_into().expect("4")) as usize;
            if len == 0 {
                tail = (tail / cap + 1) * cap;
                self.tail.0.store(tail, Ordering::Release);
                continue;
            }
            let total = LEN_PREFIX + len;
            // Frame fully inside this lap by construction.
            let payload_at = at + LEN_PREFIX;
            if len > first.len() {
                spill.clear();
                spill.resize(len, 0);
                spill.copy_from_slice(&buf[payload_at..payload_at + len]);
                self.tail.0.store(tail + total, Ordering::Release);
                return Ok(spill.as_slice());
            }
            first[..len].copy_from_slice(&buf[payload_at..payload_at + len]);
            self.tail.0.store(tail + total, Ordering::Release);
            return Ok(&first[..len]);
        }
    }

    /// Length of the next user frame WITHOUT consuming it. Skip records and
    /// lap padding are released (they are not user frames), so calling this
    /// repeatedly makes progress. `Empty` when no frame is available.
    ///
    /// Used by the FFI layer to honor the "-2 = frame left intact" contract:
    /// the consumer can check `len` against its buffer before taking.
    pub fn next_frame_len(&self) -> Result<usize, RingError> {
        let cap = self.capacity();
        let head = self.head.0.load(Ordering::Acquire);
        let mut tail = self.tail.0.load(Ordering::Relaxed);
        let buf = unsafe { &*self.buf.get() };
        loop {
            if head == tail {
                self.tail.0.store(tail, Ordering::Release);
                return Err(RingError::Empty);
            }
            let at = tail & (cap - 1);
            if at + LEN_PREFIX > cap {
                tail = (tail / cap + 1) * cap;
                self.tail.0.store(tail, Ordering::Release);
                continue;
            }
            let len = u32::from_be_bytes(buf[at..at + LEN_PREFIX].try_into().expect("4")) as usize;
            if len == 0 {
                tail = (tail / cap + 1) * cap;
                self.tail.0.store(tail, Ordering::Release);
                continue;
            }
            return Ok(len);
        }
    }

    /// Approximate used-byte count (watchdog telemetry; inexact under races).
    pub fn used_bytes(&self) -> usize {
        let head = self.head.0.load(Ordering::Acquire);
        let tail = self.tail.0.load(Ordering::Acquire);
        head.wrapping_sub(tail)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_then_full_semantics() {
        let ring = SpscRing::new(64).unwrap();
        let mut spill = Vec::new();
        let mut empty: [u8; 0] = [];
        assert!(matches!(
            ring.read_frame_into(&mut empty, &mut spill),
            Err(RingError::Empty)
        ));
        assert!(ring.write_frame(&[1; 10]).is_ok());
        assert!(ring.used_bytes() > 0);
        ring.write_frame(&[2; 10]).unwrap();
        let mut first = [0u8; 10];
        let mut spill = Vec::new();
        let got = ring.read_frame_into(&mut first, &mut spill).unwrap();
        assert_eq!(got, &[1u8; 10]);
        let mut spill = Vec::new();
        let got = ring.read_frame_into(&mut first, &mut spill).unwrap();
        assert_eq!(got, &[2u8; 10]);
        assert!(matches!(
            ring.read_frame_into(&mut first, &mut Vec::new()),
            Err(RingError::Empty)
        ));
    }

    #[test]
    fn too_large_frames_are_rejected() {
        let ring = SpscRing::new(64).unwrap();
        assert_eq!(ring.max_frame(), 64 - 4 - 1);
        assert!(matches!(
            ring.write_frame(&[0u8; 4096]),
            Err(RingError::TooLarge)
        ));
        assert!(matches!(
            ring.write_frame(&vec![0u8; ring.max_frame() + 1]),
            Err(RingError::TooLarge)
        ));
    }

    #[test]
    fn max_frame_roundtrip_hits_boundary_cases() {
        let ring = SpscRing::new(64).unwrap();
        ring.write_frame(&vec![9u8; ring.max_frame()]).unwrap();
        let max = ring.max_frame();
        let mut first = vec![0u8; max];
        let mut spill = Vec::new();
        let got = ring.read_frame_into(&mut first, &mut spill).unwrap();
        assert_eq!(got.len(), ring.max_frame());
    }

    #[test]
    fn wrap_around_stress_sequential() {
        let ring = SpscRing::new(256).unwrap();
        let mut spill = Vec::new();
        for i in 0..2000u32 {
            let payload: Vec<u8> = (0..(i % 44 + 1))
                .map(|b| (i as u8).wrapping_add(b as u8))
                .collect();
            ring.write_frame(&payload)
                .unwrap_or_else(|e| panic!("write {i}: {e:?}"));
            let mut first = vec![0u8; payload.len()];
            let got = ring
                .read_frame_into(&mut first, &mut spill)
                .unwrap_or_else(|e| panic!("read {i}: {e:?}"));
            assert_eq!(got, &payload[..], "iteration {i}");
        }
    }

    #[test]
    fn full_ring_backpressures_then_drains() {
        let ring = SpscRing::new(128).unwrap();
        let mut written = 0usize;
        for _ in 0..1000 {
            match ring.write_frame(&[7u8; 40]) {
                Ok(()) => written += 1,
                Err(RingError::Full) => break,
                Err(e) => panic!("{e:?}"),
            }
        }
        assert!(written >= 1);
        let mut first = [0u8; 40];
        let mut drained = 0usize;
        let mut spill = Vec::new();
        while ring.read_frame_into(&mut first, &mut spill).is_ok() {
            drained += 1;
        }
        assert_eq!(written, drained);
        // Ring is usable again after a full drain (space fully released).
        ring.write_frame(&[1u8; 10]).unwrap();
    }

    #[test]
    fn zero_length_write_is_invalid() {
        let ring = SpscRing::new(64).unwrap();
        assert!(matches!(ring.write_frame(&[]), Err(RingError::Invalid)));
    }

    #[test]
    fn next_frame_len_peeks_without_consuming() {
        let ring = SpscRing::new(256).unwrap();
        assert!(matches!(ring.next_frame_len(), Err(RingError::Empty)));
        ring.write_frame(&[1, 2, 3]).unwrap();
        ring.write_frame(&[4, 5]).unwrap();
        for _ in 0..3 {
            assert_eq!(ring.next_frame_len().unwrap(), 3, "peek must not consume");
        }
        let mut first = [0u8; 8];
        let mut spill = Vec::new();
        assert_eq!(
            ring.read_frame_into(&mut first, &mut spill).unwrap(),
            &[1, 2, 3]
        );
        assert_eq!(ring.next_frame_len().unwrap(), 2);
        assert_eq!(
            ring.read_frame_into(&mut first, &mut spill).unwrap(),
            &[4, 5]
        );
        assert!(matches!(ring.next_frame_len(), Err(RingError::Empty)));
    }

    #[test]
    fn new_rejects_absurd_capacity() {
        assert!(matches!(
            SpscRing::new(usize::MAX),
            Err(RingError::TooLarge)
        ));
        assert!(matches!(SpscRing::new(1 << 41), Err(RingError::TooLarge)));
    }

    #[test]
    fn concurrent_spsc_100k_frames() {
        let ring = std::sync::Arc::new(SpscRing::new(4096).unwrap());
        const N: usize = 100_000;
        let producer = {
            let ring = ring.clone();
            std::thread::spawn(move || {
                for i in 0..N {
                    let payload = i.to_be_bytes();
                    loop {
                        match ring.write_frame(&payload) {
                            Ok(()) => break,
                            Err(RingError::Full) => std::hint::spin_loop(),
                            Err(e) => panic!("unexpected {e:?}"),
                        }
                    }
                }
            })
        };
        let consumer = {
            let ring = ring.clone();
            std::thread::spawn(move || {
                let mut first = [0u8; 8];
                let mut spill = Vec::new();
                for i in 0..N {
                    let expect = i.to_be_bytes();
                    loop {
                        match ring.read_frame_into(&mut first, &mut spill) {
                            Ok(got) => {
                                assert_eq!(got, &expect[..], "frame {i}");
                                break;
                            }
                            Err(RingError::Empty) => std::hint::spin_loop(),
                            Err(e) => panic!("unexpected {e:?}"),
                        }
                    }
                }
            })
        };
        producer.join().unwrap();
        consumer.join().unwrap();
    }
}
