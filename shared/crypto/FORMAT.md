# The portable security core

What both apps must agree on byte for byte, and why it is shaped this way. Companion to
`shared/pairing/PROTOCOL.md`; the same rule applies — an implementation can be perfectly
self-consistent and still disagree with the other end, so `VECTORS.json` is the contract and
this file is only the explanation.

## Two implementations, one format

The desktop implements this in C++ over OpenSSL. The phone keeps Kotlin, over Tink and
BouncyCastle, because both already ship there and the NDK build has no OpenSSL — vendoring a
crypto library for parity would buy nothing. What the two share is not code but
`VECTORS.json`, which both test suites read.

That is the arrangement `shared/pairing` already has, and it earns its keep: the pairing
vectors caught a certificate-ordering bug that was invisible to same-platform tests, and the
first run of the crypto vectors caught an empty password being dropped rather than passed as
zero bytes. Here the stakes are higher than a failed handshake — a drift means a vault that
will not open.

The vectors are asymmetric on purpose. Tink will not let a caller choose a stream's header,
so Kotlin can only check that it decrypts what it recorded. C++ can inject the recorded
header, so it checks byte-for-byte equality with what Tink produced. That is the direction
that matters: C++ output has to be readable by the phone.

## Primitives

All three are in OpenSSL's default provider, so the desktop adds no dependency to the
`libcrypto` it already links for Ed25519 and certificates.

| | |
|---|---|
| **Argon2id** | v=0x13, m=65536 KiB, t=3, p=4, 16-byte salt, 32-byte output. Verified byte-identical to BouncyCastle 1.79. `threads` is always 1: the output is defined by `lanes`, and BouncyCastle is single-threaded regardless. |
| **HKDF-SHA256** | An absent salt means Tink's null, which is a zero-filled block of the digest length. Both libraries agree, and the vectors pin it — if they had not, every subkey would diverge. |
| **AES-256-GCM** | 12-byte nonce, 16-byte tag. |

A password is not a key, and the Argon2id parameters are the part that matters most: without
a deliberately slow derivation, a short passphrase falls to offline brute force no matter how
good the cipher is. The parameters live in the file, not in the code, or a future build
cannot open today's data.

## The streaming AEAD

Wire-compatible with Tink's `AesGcmHkdfStreaming`, which is what the vault and `.avsbck`
already use on the phone. This is the only construction here with no library behind it on the
desktop side, so it is where a bug is most likely.

```
header:  [0]      = headerLength = 1 + 32 + 7 = 40
         [1..33)  = 32-byte random salt
         [33..40) = 7-byte nonce prefix
key:     HKDF-SHA256(ikm = key, salt = headerSalt, info = associatedData, L = 32)
nonce:   noncePrefix(7) || segmentIndex(4, big-endian) || lastSegmentFlag(1)
segment: 1 MiB of ciphertext including a trailing 16-byte GCM tag
```

The associated data enters through the HKDF **info** parameter. Each segment's GCM AAD is
empty. Getting that backwards produces a self-consistent implementation that the other end
cannot read.

Segment 0 is short by the header, so it carries **1048520** plaintext bytes against
**1048560** for every later one. A plaintext of exactly 1048520 bytes stays one segment — the
last-segment flag is set on it rather than on an empty segment after it — and 1048521 forces
a second. Both are pinned, because that is a `>` against a `>=` in someone else's file. Empty
input still costs 56 bytes: a header and one empty final segment.

## The keybox

A keystore is a container, not a source of secrecy. What a platform keystore actually
contributes is a root secret the app cannot be tricked into surrendering — a TEE key on
Android, a login-derived key in a desktop keyring. A file we write ourselves holds and
organises keys just as well; what it cannot do is *be* a secret.

So the master key is 32 random bytes, generated once, wrapped by **pluggable slots**, and each
platform uses the strongest root it actually has:

| slot kind | root |
|---|---|
| `passphrase` | Argon2id-derived key-encryption key |
| `keyfile` | a random key in an owner-only file — convenience, not secrecy |
| `platform-keystore` | Android's non-exportable Keystore key |
| `recovery` | a second passphrase, so a lost root is not a lost vault |

Nothing depends on a keystore *API* existing. Android simply has a better root available, and
a slot is how the format says so.

```
kek  --HKDF("arsivinyo/keybox/verify/v1")--> verifier   (stored, compared in constant time)
     --HKDF("arsivinyo/keybox/wrap/v1")----> wrapKey    (never stored)
wrapped = nonce(12) || AES-256-GCM(wrapKey, masterKey) || tag(16)
          AAD = "arsivinyo/keybox/v1/" + slotId

masterKey --HKDF("arsivinyo/key/v1/<purpose>")--> cookies | vault | vault-index | thumbs
```

Two subkeys from one key-encryption key, so the stored verifier can never act as an oracle on
the key that actually unwraps. The verifier is checked first, so a mistyped passphrase is
reported as wrong rather than as corruption. The slot id is the wrap's associated data, so one
slot's blob pasted into another's entry fails the tag.

Because the master key is random rather than derived, adding a slot and changing a passphrase
both re-wrap 32 bytes and touch no content. That is the whole point of the design — and the
reason **removing the last slot has to be refused**: nothing would be left that can unwrap it,
and every encrypted file would be lost.

## The `.avsbck` container

The backup format the phone already writes. Both apps implement it now, which is what makes
`shared/pairing/PROTOCOL.md`'s claim true — it names a backup as the only supported route for
vault contents between devices, and until this the desktop could not open one.

```
magic "AVSBCK\0" | formatVersion u16 | headerLength u32 | header JSON   <- all plaintext
then, per section, in the order the header lists them:
  repeat { chunkLength u32 | chunk }  terminated by u32 0
  -- the concatenated chunks are ONE streaming-AEAD stream, AAD = the section id
  and inside that stream:
    repeat {
      entryHeaderLength u32 | entry header JSON
      repeat { chunkLength u32 | chunk } terminated by u32 0     <- the payload
      trailerLength u32 | trailer JSON
    } terminated by u32 0
```

Sections carry no offsets, because a section's ciphertext length is not known until it has
been written and the stream it is written to is not always seekable. Self-delimiting chunks
cost four bytes a megabyte and leave both directions forward-only.

The header is plaintext and versioned on purpose: a backup outlives the build that wrote it,
and a future version has to be able to read today's KDF parameters. Nothing authenticates it,
which is inherited rather than chosen — the binding is indirect. Changing a section's id
changes both its key and its associated data, and changing a slot's salt or parameters makes
the verifier mismatch. What is *not* protected is the advisory `itemCount`, `plaintextBytes`
and `producer`, so an edited header can make a preview lie.

Each entry's trailer records the plaintext size and SHA-256, checked as the payload streams
past. That is what catches an item truncated at export: its own bytes would hash consistently
against a hash taken over the same truncation, so the writer marks it incomplete and the
reader refuses it.

## What this defends against

| Defended | Not defended |
|---|---|
| A stolen disk or a stray backup copy | Anything running as the user while the app is unlocked |
| Another account on the same machine | A keylogger, or malware that reads the process |
| A file that has been altered — every read is authenticated | The `keyfile` slot against someone who has the disk: the key sits beside the data, which is convenience and is labelled as such |
| A mistyped passphrase, reported as such and not as damage | A forgotten passphrase, unless a `recovery` slot exists |

There is deliberately **no wrong-passphrase counter**. An attacker holding the files bypasses
the app entirely, so a counter would lock out only the owner. Argon2id is the rate limit.
