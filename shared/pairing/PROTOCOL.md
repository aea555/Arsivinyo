# Arsivinyo device pairing

The desktop implements this. The phone implements the shared halves — framing, the code
derivation and the auth transcript — and its transport is not wired up yet.

Two devices you own, on the same network, exchanging files directly. No cloud, no
account, no relay — the same reason the downloader has no backend.

## What it is for

| verb | direction | carries |
|---|---|---|
| `put` | either | a media file, or a `.avsbck` backup |
| `list` / `get` | either | browse the peer's library and pull an item |
| `download` | either | a URL for the peer to fetch itself |

## What it is not for

**Not sync.** There is no reconciliation, no conflict resolution, no shared state. Every
transfer is an explicit act by the user. Two peers with different storage models and
different platforms have no coherent continuous-sync story, and inventing one would add a
class of bug — silent divergence — that a transfer model simply does not have.

**Not a vault channel.** A vault is confined to the device that made it. Its keys are
bound to that device's keystore and are not exportable by design. A `.avsbck` backup is
the supported way to move vault contents, and it is a deliberate, password-protected act.

**Not a remote control.** `download` hands over a URL. It does not expose the peer's
settings, library management, or vault.

## Threat model

Defended against:

- **A passive listener on the network.** All traffic after pairing is inside TLS 1.3.
- **An active attacker during pairing.** This is the moment that matters. Key exchange is
  authenticated out of band — by a QR code the user scans, and a confirmation code shown
  on both devices. An attacker who intercepts the exchange cannot make both codes agree.
- **An unpaired device on the same network.** Discovery is public; a connection from an
  unknown key is refused before any request is read.

Not defended against:

- **A compromised endpoint.** If the phone or the desktop is already owned, pairing gives
  the attacker nothing it did not already have.
- **Traffic analysis.** Sizes and timings are visible. Names and content are not.

## Identity

Each device generates one **Ed25519 keypair** on first run and keeps it for life. The
public key is the device's identity; its SHA-256 is the **fingerprint** shown to the user.

Both platforms keep the private key in storage only the app can read. The Android Keystore
would be the obvious home on the phone and is where the vault's keys live, but it has no
Ed25519: `KeyProperties` offers EC with the NIST curves and RSA, and an Ed25519 key cannot
be imported into it either. Picking the curve to fit the Keystore would mean a different
signature scheme per platform, which is a worse trade than app-private storage — and the
desktop has no better option than a user-only file until the platform keychains are wired
up. Neither change would alter a byte on the wire.

The phone uses BouncyCastle's low-level API rather than the JDK's `KeyPairGenerator`, which
needs API 33 while this app supports API 24. On the older releases the JDK path throws, and
the failure is silent: the device ends up with no identity at all.

A device that loses its key is a new device and must pair again. This is intended: it is
the same property that makes a stolen phone's pairing useless once wiped.

## Discovery

mDNS / DNS-SD, service type `_arsivinyo._tcp`. Android uses `NsdManager` from the
platform; the desktop implements the wire format itself, in `desktop/src/DnsSd.cpp`,
because Qt has no mDNS and the alternatives are per-platform daemons that do not ship with
the app. TXT records:

```
v=1                 protocol version
id=<fingerprint>    so a known peer is recognised without connecting
name=<device name>  for the UI only, never trusted
```

Discovery reveals that an Arsivinyo device exists on the network. It reveals nothing about
its library. Peers may be added by address if mDNS is unavailable.

## Pairing ceremony

The step that has to be right, because everything after it inherits this trust.

1. Desktop displays a **QR code**: its fingerprint, address and port.
2. Phone scans it. The phone now knows the desktop's real key, from a channel an attacker
   on the network cannot reach.
3. Phone connects. TLS is pinned to that key, so a man in the middle fails here.
4. Phone sends its own public key over that authenticated channel.
5. **Both devices display the same six digits.** Hash the two public keys, ordered
   lexicographically and concatenated; take the digest's first four bytes big-endian,
   modulo one million, zero-padded. The user confirms they match, which authenticates the
   phone's key to the desktop. Sorting means neither device has to be "first".
6. Each stores the other's public key and a user-visible name.

The QR authenticates one direction; the confirmation code authenticates the other. Both
are needed. Sorting the keys before hashing keeps the code identical on both ends.

Unpairing is local and one-sided: forget the key. There is no protocol message for it,
because a device that has been forgotten should not be told.

## Transport

TLS, with each side presenting an ordinary self-signed P-256 certificate. No certificate
authority is involved and nothing checks a name or a chain, because the certificate is not
what identifies anyone.

**Why the certificate does not carry the identity.** The first draft of this document said
each side would present a certificate whose public key *is* its Ed25519 identity key. That
cannot be built: Android's TLS stack does not accept Ed25519 certificates, and this app
supports API 24. Rather than run a different signature scheme on each platform, the
identity is bound to the connection instead of to the certificate.

After the handshake each side sends an `auth` message carrying its public key and an
Ed25519 signature over a fixed transcript:

```
"arsivinyo-pairing-auth-v1\0" || sha256(server cert DER) || sha256(client cert DER) || role
```

`role` is `'S'` or `'C'`. The server's hash always comes first, so both ends build the same
bytes without negotiating an order, and the role byte is what stops a signature captured
from one direction being replayed as the other's.

This is what keeps a man in the middle out. An attacker terminating TLS on both legs
presents different certificates on each, so a signature made for one leg does not verify on
the other, and it cannot forge one without the Ed25519 key. A connection that fails this
check is closed before a single request is read, and so is one whose key has not been
paired.

The session certificate is generated per process and never written to disk. Keygen costs
under a millisecond, so persisting it would buy nothing, and not persisting it means there
is no second private key at rest and a captured transcript signature is useless past the
run that produced it.

Framing — every message:

```
[uint32 big-endian length][uint8 type][payload]
```

| type | payload |
|---|---|
| `0` | JSON control message, UTF-8 |
| `1` | raw bytes of the transfer in progress |

One connection carries control and bulk together, so a transfer can be cancelled by a
control message without tearing down the connection.

## Messages

```jsonc
// on connect, both directions
{"t":"auth","v":1,"key":"<hex ed25519 public key>","name":"Desktop","sig":"<hex>"}
// the first message in each direction; nothing else is read until it verifies

{"t":"list","kind":"music"}                 // or "backups"
{"t":"listing","kind":"music","items":[
  {"id":"...","title":"...","artist":"...","durationSec":0,"sizeBytes":0,"sha256":"..."}
]}

{"t":"get","id":"..."}
{"t":"put","name":"...","kind":"music","sizeBytes":0,"sha256":"..."}
{"t":"accept","transferId":"..."}           // receiver agrees; sender then streams type 1
{"t":"reject","reason":"..."}
{"t":"complete","transferId":"...","sha256":"..."}
{"t":"cancel","transferId":"..."}

{"t":"download","url":"...","mediaKind":"audio"}
{"t":"error","code":"...","message":"..."}
```

## Holding the two implementations together

Neither platform can call the other's code, so the framing and the code derivation exist
twice — `shared/pairing/wire.cpp` and `PairingWire.kt`. Prose does not keep two
implementations honest, so both test suites read the same file:
**`shared/pairing/VECTORS.json`**, which fixes the exact bytes for encoding, for every
rejection case, for key ordering, for the code, for the fingerprint, and for the auth
transcript and its signature.

This is not belt and braces. An implementation can be perfectly self-consistent and still
disagree with the other end — reversing the key sort passes every internal check and
produces a device that pairs with itself and nothing else. The vectors are what turns that
into a failing test instead of an unexplainable bug in the field.

## Integrity

Every transfer declares its size and SHA-256 up front and is verified on receipt. A
mismatch discards the file. This mirrors the backup container's entry trailer, which
records size, hash and a `complete` flag for exactly this reason — a truncated transfer
whose hash matches its own truncated bytes is the failure mode worth designing against.

A received file is written to a temporary path and moved into place only after the hash
verifies, so a partial transfer never appears in a library.

## Privacy

Titles and file names travel over this channel. They are inside TLS and must **never** be
written to logs or shown in a notification — the same rule the rest of the app follows.
The `name` TXT record is chosen by the user and is the only unencrypted human-readable
field.

## Open questions

- **Resuming a large transfer** after a dropped connection. Range requests on `get` would
  cover it; not designed yet.
- **Where a received file lands.** Each side decides — MediaStore on Android, a configured
  folder on desktop. Deliberately outside the protocol.
- **Multiple peers.** The design allows many pairings; the UI story is unwritten.
- **Android background transfers.** A large `put` needs the foreground service the
  downloader already uses.
- **Desktop key at rest.** A file with user-only permissions is the starting point; the
  platform keystores are better and can come later without a protocol change.
