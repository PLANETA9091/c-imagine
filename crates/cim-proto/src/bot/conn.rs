//! Blocking TCP connection with the Minecraft frame envelope
//! (length prefix + optional zlib compression after Set Compression).

use crate::frame::{decode_frame, encode_frame, PacketFrame};
use crate::ProtoError;
use std::io::{Read, Write};
use std::net::TcpStream;
use std::time::Duration;

pub struct Conn {
    stream: TcpStream,
    buf: Vec<u8>,
    /// zlib threshold (None until login compression arrives).
    pub compression: Option<u32>,
    pub packets_read: u64,
    pub packets_written: u64,
}

impl Conn {
    pub fn connect(addr: &str, timeout: Duration) -> Result<Self, ProtoError> {
        let stream = TcpStream::connect(addr)
            .map_err(|e| ProtoError::InvalidFrame(format!("tcp connect: {e}")))?;
        stream
            .set_read_timeout(Some(timeout))
            .map_err(|e| ProtoError::InvalidFrame(format!("tcp timeout: {e}")))?;
        stream
            .set_write_timeout(Some(timeout))
            .map_err(|e| ProtoError::InvalidFrame(format!("tcp timeout: {e}")))?;
        Ok(Self {
            stream,
            buf: Vec::with_capacity(16 * 1024),
            compression: None,
            packets_read: 0,
            packets_written: 0,
        })
    }

    /// Blocking frame read. Returns `Ok(None)` on a clean EOF at a frame
    /// boundary (server closed the connection).
    pub fn read_frame(&mut self) -> Result<Option<PacketFrame>, ProtoError> {
        loop {
            // try to decode from what we have
            if !self.buf.is_empty() {
                match decode_frame(&self.buf, self.compression) {
                    Ok((frame, consumed)) => {
                        self.buf.drain(..consumed);
                        self.packets_read += 1;
                        return Ok(Some(frame));
                    }
                    Err(ProtoError::UnexpectedEnd { .. }) => {}
                    Err(e) => return Err(e),
                }
            }
            // need more bytes
            let mut chunk = [0u8; 16 * 1024];
            let n = self
                .stream
                .read(&mut chunk)
                .map_err(|e| ProtoError::InvalidFrame(format!("tcp read: {e}")))?;
            if n == 0 {
                return Ok(None); // EOF
            }
            self.buf.extend_from_slice(&chunk[..n]);
        }
    }

    pub fn write_frame(&mut self, frame: &PacketFrame) -> Result<(), ProtoError> {
        let wire = encode_frame(frame, self.compression)?;
        self.stream
            .write_all(&wire)
            .map_err(|e| ProtoError::InvalidFrame(format!("tcp write: {e}")))?;
        self.packets_written += 1;
        Ok(())
    }
}
