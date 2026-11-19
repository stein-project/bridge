/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

//! The proxy's certificate, pinned by the SHA-256 fingerprint the server
//! list announced.
//!
//! A Stein Sync proxy has a self-signed certificate (no CA would sign a
//! certificate for a bare IP or a home server's name). The client trusts
//! exactly the certificate whose fingerprint came in the server's status,
//! the same unauthenticated channel that already says which server it is:
//! someone who can rewrite that status can also rewrite the game's own
//! traffic, which the game never authenticated either. What the pin buys
//! is that nobody *else* on the path can read or change the QUIC
//! connection once it is up. The TLS handshake signature is still checked
//! with the pinned certificate's key, so presenting the certificate
//! without its private key fails.

use std::sync::Arc;

use rustls::{
    client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier},
    crypto::{verify_tls12_signature, verify_tls13_signature, CryptoProvider},
    pki_types::{CertificateDer, ServerName, UnixTime},
    CertificateError, DigitallySignedStruct, Error, SignatureScheme,
};

/// The SHA-256 of a certificate (DER).
pub type Fingerprint = [u8; 32];

/// The SHA-256 fingerprint of a DER certificate.
pub fn fingerprint(der: &[u8]) -> Fingerprint {
    let digest = ring::digest::digest(&ring::digest::SHA256, der);
    let mut out = [0u8; 32];
    out.copy_from_slice(digest.as_ref());
    out
}

/// Parses the status form of a fingerprint, `sha256:` and 64 hex digits
/// (either case); `None` for anything else.
pub fn parse_fingerprint(text: &str) -> Option<Fingerprint> {
    let hex = text.strip_prefix("sha256:")?;
    if hex.len() != 64 {
        return None;
    }
    let mut out = [0u8; 32];
    for (i, pair) in hex.as_bytes().chunks(2).enumerate() {
        let digit = |b: u8| (b as char).to_digit(16);
        out[i] = u8::try_from(digit(pair[0])? * 16 + digit(pair[1])?).ok()?;
    }
    Some(out)
}

/// Accepts exactly the certificate with the pinned fingerprint.
#[derive(Debug)]
pub struct PinnedVerifier {
    pin: Fingerprint,
    provider: Arc<CryptoProvider>,
}

impl PinnedVerifier {
    /// A verifier for `pin`, checking handshake signatures with `provider`.
    pub fn new(pin: Fingerprint, provider: Arc<CryptoProvider>) -> Self {
        Self { pin, provider }
    }
}

impl ServerCertVerifier for PinnedVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, Error> {
        // Constant-time comparison is not needed: the pin is public (it is
        // in the server list), only the private key behind it is secret.
        if fingerprint(end_entity.as_ref()) == self.pin {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(Error::InvalidCertificate(
                CertificateError::ApplicationVerificationFailure,
            ))
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        // QUIC is TLS 1.3 only; kept correct rather than unreachable.
        verify_tls12_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        verify_tls13_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn fingerprints_parse_in_either_case() {
        let pin = fingerprint(b"certificate");
        let hex: String = pin.iter().map(|b| format!("{b:02x}")).collect();
        assert_eq!(parse_fingerprint(&format!("sha256:{hex}")), Some(pin));
        assert_eq!(
            parse_fingerprint(&format!("sha256:{}", hex.to_uppercase())),
            Some(pin)
        );
        assert_eq!(parse_fingerprint(&hex), None);
        assert_eq!(parse_fingerprint("sha256:abc"), None);
        assert_eq!(
            parse_fingerprint(&format!("sha256:{}", "g".repeat(64))),
            None
        );
    }
}
