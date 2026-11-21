# APK anti-tamper and server-side app authenticity

## Status

Planning and isolated implementation branch: `security/server-app-attestation`. No production enforcement is enabled. Do not deploy a partial verifier, change the Plus grant key, or merge this branch into the release branch until the compatibility and security gates below pass.

## Security objective

Make routine APK repackaging, resource/smali edits, signature spoofing, and local Plus bypasses through MT Manager ineffective against the service. A modified client must not be able to obtain or keep premium authorization merely by making local checks return success.

No client-side scheme can make an APK literally impossible to modify on a device controlled by its owner or running with root. The practical objective is to make tampering detectable and, most importantly, prevent a modified client from obtaining server-authorized premium access. Local checks are defense-in-depth, not the trust anchor.

## Evidence from the supplied modified APK

The supplied decompiled sample alters the Application inheritance/initialization path and hooks Android package-signature lookup, returning a configured certificate to callers. It also interferes with PackageManager/Parcel signature metadata and caches. This defeats checks that rely on the PackageManager's reported signer, including simple Java/JNI checks that query that same API.

**Class names, package names, filenames, suffixes, and numeric identifiers in the sample are arbitrary and must never be used as detection rules.** The sample's class name is evidence only for describing that one artifact. Detection research must focus on behavior and security invariants (unexpected Application entry-point/superclass changes, signature metadata substitution, PackageManager/Parcel creator/cache tampering, runtime hooking, altered DEX/native payloads). These client-side indicators are diagnostics and defense-in-depth only; they are not sufficient authorization conditions.

This sample demonstrates client-side signature-lookup spoofing. It does not demonstrate that the Worker validated and accepted an altered APK. The current Worker authenticates a device bearer token and signs a Plus JWT; it does not verify Android app attestation.

## Layered protection model

### Layer 1 — APK build and signing integrity

- Keep the release signing key private and outside the repository/CI logs.
- Verify the final APK's package, version, ABI contents, signer certificate, and reproducible build outputs before publishing.
- Add CI checks that fail if the release signer differs from the pinned release certificate or if unexpected manifest/Application/DEX/native changes appear.
- Preserve the current release certificate and Plus JWT verification key; do not rotate either as part of this work.

### Layer 2 — Client anti-tamper (Java + native)

- Retain the existing Java and JNI signature guard as a friction/detection layer, not as proof of authenticity.
- Strengthen checks using independent signals: installed APK signer through more than one OS interface where available, APK/DEX/native integrity measurements, expected package and Application wiring, debugger/instrumentation indicators, and native self-integrity checks.
- Avoid brittle string/class-name blacklists. The MT Manager sample's identifiers are not stable indicators.
- Fail closed only for a clearly verified integrity violation when the server-side compatibility policy is known. During development, report diagnostic reason codes without locking users out.
- Assume an attacker with root, runtime instrumentation, or full control of the APK can patch or bypass local checks.

### Layer 3 — Server-verified device key and Android app attestation

1. The app creates an asymmetric key pair in Android Keystore. The private key is non-exportable.
2. The server issues a random, short-lived, one-use challenge bound to the device record and public-key fingerprint.
3. The client signs a canonical challenge message. The Worker verifies proof of possession, expires/consumes the nonce atomically, and rejects replay.
4. App authenticity additionally requires a verifiable Android Key Attestation certificate chain. The Worker (or a separately audited verifier service) validates the chain to explicitly trusted Google/OEM roots, certificate status/revocation where available, attestation challenge, verified boot/device state policy, and the attested application ID package name and signing-certificate digest.
5. Only a successfully attested device can receive a premium grant under the strict policy. The existing Plus grant remains RS256 signed with the existing private key.

A Keystore challenge by itself is not app authenticity: a repackaged app can call Keystore too. The attested application identity and server-side certificate-chain validation are essential. A package name or certificate digest merely sent by the client is not evidence.

### Layer 4 — Server authorization and grant lifecycle

- Require fresh server-verified app/device proof before issuing Plus grants.
- Bind the grant to the device record and enrolled key identity, and keep its lifetime short.
- Require proof on premium cloud-sync and device-management operations where appropriate, not just on the initial grant endpoint.
- Re-check license/device revocation server-side. Do not rely on a local Plus boolean or cached entitlement as final authority.
- Preserve activation codes, device limits, device-token hashes, D1 license records, existing grant issuer/audience, and free catalog behavior.

## Compatibility policy

Do not assume Play Integrity or hardware-backed Key Attestation is available on every Android TV box. Probe:
- RT-X2 Realtek RTD1325 (Android 14, 32-bit ARM userspace);
- X88 Pro X5M;
- Teclast P30T.

Record key security level (TEE/StrongBox/software), certificate-chain availability, attested package name and signer digest, verified-boot state, and failure reason. Test rooted and non-rooted configurations separately.

Do not silently downgrade an attestation failure to bearer-token-only authorization. Before enforcement, explicitly decide whether unsupported devices are denied Plus, allowed under a clearly weaker compatibility tier, or supported through a separately reviewed owner-issued exception. Compatibility must be measured before choosing.

## Required implementation gates

- Add a new D1 migration; never edit an already-applied migration.
- Implement identical security behavior in `license-server/src/index.ts` and synchronized `license-server/src/worker.js`.
- Rate-limit challenge issuance and bind each nonce to device, key fingerprint, expiry, and one-time consumption.
- Use atomic D1 updates/transactions to prevent replay and enrollment races.
- Define a safe authenticated enrollment/re-enrollment path for existing installations without breaking activation recovery.
- Add tests for invalid signatures, wrong key, expired/replayed nonce, altered package name, altered signer digest, untrusted/revoked chain, missing attestation, software-only keys, unsupported hardware, revoked license/device, token theft/replay, and modified APK.
- Add CI APK inspection/signature checks and retain Java/JNI guard tests.
- Never store private keys in the APK, Worker source, D1, or logs.
- Do not change `PLUS_SIGNING_PRIVATE_KEY` or the embedded Plus public key.

## Pairip-like protection evaluation

Commercial packers/protectors (including Pairip-style approaches) may add obfuscation, loader checks, anti-debugging, and code/resource integrity checks. They can raise the cost of casual patching, but do not replace server-side attestation and authorization. Evaluate only after a clean baseline build and device compatibility probe. Any integration must be reversible, preserve native ABIs (especially `armeabi-v7a`), avoid WebView changes, and pass startup, activation, playback, and Plus regression tests on all supported devices. Do not inject unknown protection code into the production APK.

## Deployment rule

No Worker deployment, D1 migration, or release APK until the complete attestation-chain validation is implemented and reviewed, tests pass, and capability results are known for the supported devices. No challenge-only or client-only mechanism may be described as protection against MT Manager.
