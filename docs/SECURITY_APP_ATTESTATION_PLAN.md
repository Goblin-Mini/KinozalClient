# Server-side app authenticity: implementation plan

## Status

Design only. No production enforcement is enabled by this document. Do not deploy a partially implemented verifier or change the existing Plus grant key.

## Evidence from the supplied MT Manager project

The decompiled package contains `bin/mt/signature/KillerApplication430` and related classes. Its `KinozalApplication` superclass was changed to that class. The injected code hooks package-signature lookup and returns a configured certificate to callers. This defeats checks that trust the PackageManager result, including the Java/native checks that currently read the installed package signature.

This is not evidence that the Worker verified and accepted an altered APK. The current Worker authenticates a device bearer token and signs a Plus JWT; it does not verify Android app attestation.

## Security objective

The Worker must decide whether a device is eligible for a Plus grant based on evidence it can independently validate, not on a boolean or certificate digest supplied by Java code.

The existing activation code, device limit, device token hashes, D1 license records, Plus RSA signing key, grant issuer/audience, and current free catalog behavior must remain intact.

## Proposed proof

1. Android generates a fresh asymmetric key pair in Android Keystore. The private key is non-exportable. The public key is sent during an authenticated enrollment step.
2. The server issues a random, short-lived, one-use challenge bound to the device record and the submitted public key.
3. The device returns a signature over a canonical challenge message. The Worker verifies the signature using the stored public key, consumes the nonce atomically, and rejects expired/replayed challenges.
4. For app authenticity, enrollment must additionally include a verifiable Android Key Attestation certificate chain. The server must validate the chain to an explicitly trusted Google/OEM root, revocation/status where available, the attestation challenge, verified boot/device state policy, and the attested application ID package name and signing-certificate digest.
5. Only after the attestation policy succeeds may the Worker issue a Plus grant. The grant remains RS256 signed by the existing Plus private key.

## Important distinction

A Keystore proof-of-possession challenge alone is not proof that the official APK is running. A modified APK can call Keystore too. The Android Key Attestation application identity extension is the part that can bind the generated key to package name and signing-certificate digest, provided the device has a trustworthy hardware-backed attestation implementation and the server validates its certificate chain.

Likewise, a local Java/C++ signature check is defense-in-depth only. It must not be treated as server authorization.

## Compatibility policy

Do not assume Play Integrity or hardware-backed Key Attestation is available on every Android TV box. Before enforcing attestation, run a capability probe on:
- RT-X2 Realtek RTD1325 (Android 14, 32-bit ARM userspace);
- X88 Pro X5M;
- Teclast P30T.

Record whether the device can create an attested key, whether the key is TEE/StrongBox-backed, whether the attestation certificate chain is present, and what application identity/signing digest is attested.

Do not silently downgrade an attestation failure to bearer-token-only authorization. Decide explicitly whether unsupported devices are denied Plus, allowed under a clearly weaker compatibility tier, or supported through a separately reviewed owner-issued exception. A rooted device may fail verified-boot policy; that must be tested before choosing a policy.

## Required implementation gates

- Add a new D1 migration; never edit an already-applied migration.
- Implement the same security behavior in both `license-server/src/index.ts` and the synchronized `license-server/src/worker.js` entrypoint.
- Make challenge issuance rate-limited and bind each nonce to a device, key fingerprint, expiry, and one-time consumption.
- Use atomic D1 updates to prevent replay.
- Require proof on Plus grant issuance and on premium cloud-sync/device-management endpoints where appropriate.
- Keep existing activation and recovery semantics working; enroll existing devices through an explicit authenticated migration path.
- Add tests for invalid signatures, wrong key, expired nonce, replay, altered package name, altered signing digest, untrusted chain, revoked certificate, missing attestation, revoked license, and device revocation.
- Never store private keys in the APK, Worker source, D1, or logs.
- Do not change `PLUS_SIGNING_PRIVATE_KEY` or the embedded Plus public key as part of this work.

## Deployment rule

No Worker deployment, D1 migration, or APK release until the attestation chain validation is implemented, tests pass, and capability results are known for the supported devices. This prevents an incomplete challenge-only mechanism from being mistaken for protection against the MT Manager signature-killer technique.
