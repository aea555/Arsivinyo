# Arsivinyo device pairing

The phone and the Mac implement this. The frozen Qt desktop (branch `desktop-qt`)
implemented version 1 of the ceremony and speaks nothing newer.

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
Both apps read and write that format now — see `shared/crypto/FORMAT.md`. Until they did,
this paragraph described a route that only existed at one end.

**Not a remote control.** `download` hands over a URL. It does not expose the peer's
settings, library management, or vault.

## Threat model

Defended against:

- **A passive listener on the network.** All traffic after the handshake is inside TLS —
  1.3 where both ends support it, 1.2 on Android releases that do not.
- **An active attacker during pairing.** This is the moment that matters. Key exchange is
  authenticated out of band, by six digits shown on both devices and compared by the user.
  The digits come from a commit-reveal exchange, so an attacker who intercepts it has one
  chance in a million of making both screens agree, not a search it can run.
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

mDNS / DNS-SD, service type `_arsivinyo._tcp`. Android uses `NsdManager`, the Mac
the system's DNS-SD. The instance name is the first sixteen characters of the fingerprint.
TXT records:

```
v=1                 protocol version
id=<fingerprint>    so a known peer is recognised without connecting
name=<device name>  for the UI only, never trusted
```

Discovery reveals that an Arsivinyo device exists on the network. It reveals nothing about
its library. Peers may be added by address if mDNS is unavailable.

## Pairing ceremony

The step that has to be right, because everything after it inherits this trust.

1. Both devices open a pairing window (two minutes). On one of them the user picks the
   other from the devices found on the network; that one connects and is the TLS client.
2. Both send `auth` (below), which binds each device's key to this connection.
3. They agree on six digits by committing, then revealing:

   ```jsonc
   {"t":"pair-commit","c":"<hex sha256(\"arsivinyo-pairing-commit-v2\\0\" || clientNonce)>"}  // client
   {"t":"pair-nonce","n":"<hex serverNonce>"}                                              // server
   {"t":"pair-reveal","n":"<hex clientNonce>"}                                             // client
   ```

   Nonces are 32 random bytes. The server refuses a reveal that does not match the
   commitment. Both then compute

   ```
   code = first four bytes, big-endian, of
          sha256("arsivinyo-pairing-code-v2\0" || sorted(keyA, keyB) || clientNonce || serverNonce)
          modulo one million, zero-padded to six digits
   ```

4. **Both devices display the six digits**; the user confirms on each that they match.
5. Each stores the other's public key and a user-visible name, and sends
   `{"t":"pair-confirm"}`.

The two confirmations are never simultaneous. Until its own user confirms, a device
ignores everything but the ceremony, so the side that confirmed first holds its requests
until the other's `pair-confirm` arrives, rather than sending them into nothing. And each
side makes its session before it sends `pair-confirm`, so the answer to it never reaches a
ceremony that has just ended.

**Why commit-reveal.** Version 1 derived the digits from the two public keys alone. Both
keys are known before anyone compares digits, so a man in the middle, holding one leg to
each device, could generate key pairs until both legs showed the same six digits: about a
million tries, seconds of work, and nothing times out while it runs. Now the client commits
to its nonce before it sees the server's, and the code covers both. An attacker has to
commit on each leg before it learns what it would have to aim at, which leaves it a guess
with one chance in a million. This is the numeric comparison of Bluetooth pairing, for the
same reason.

A QR code carrying the fingerprint, which earlier versions of this document described,
was never built. The commitment gives the same guarantee without a camera.

Unpairing is local and one-sided: forget the key. There is no protocol message for it,
because a device that has been forgotten should not be told.

**Reconnecting.** A paired device found on the network is connected to without asking, and
tried again every twenty seconds while it is announced and not connected. When both ends
connect at once, both keep the connection opened by the device with the smaller
fingerprint and close the other, so they settle on the same one without negotiating.

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

{"t":"list","kind":"music"}                 // or "playlists", "backups"
{"t":"listing","kind":"music","items":[
  {"id":"...","title":"...","artist":"...","durationSec":0,"sizeBytes":0,"sha256":"..."}
]}

{"t":"listing","kind":"playlists","items":[
  {"id":"...","name":"Road trip","favorites":false,"count":12}
]}                                          // Favorites: "name" empty, "favorites" true

{"t":"get","id":"..."}
{"t":"get","playlist":"<id from the playlists listing>"}
                                            // the other device sends that playlist
{"t":"put","name":"...","kind":"music","sizeBytes":0,"sha256":"...",
 "artwork":"<base64, optional, at most 1 MiB>","artworkName":"cover.jpg",
 "playlist":{"name":"Road trip"},           // or {"favorites":true}; optional
 "batch":{"index":3,"count":12},            // optional: where it is in a run of sends
 "title":"...","artist":"...",              // optional, music: what the sender's library says
 "have":true}                               // this sender understands reason "have"
                                            // both apps keep covers beside the files, so
                                            // the cover travels in the offer or not at all
{"t":"accept","transferId":"..."}           // receiver agrees; sender then streams type 1
{"t":"reject","reason":"..."}
{"t":"reject","reason":"have"}              // not a refusal: it has these bytes already
{"t":"complete","transferId":"...","sha256":"..."}
{"t":"cancel","transferId":"..."}

{"t":"download","url":"...","mediaKind":"audio"}
{"t":"error","code":"...","message":"..."}
```

## Playlists

A track sent as part of a playlist carries `playlist` in its `put`: the playlist's name, or
`{"favorites": true}` for Favorites, which is each device's own whatever its language calls
it. The receiver files the track as it would any, then adds it at the end of its own
playlist of that name, making it if it has none; a track for Favorites becomes a favorite.
The tracks of a playlist are sent one after another in its order, so they arrive in it.
Favorites are sent oldest first: the Mac keeps them newest first and the phone oldest
first, and each puts what arrives where its own order says.

A track's offer carries the `title` and `artist` the sender's library has for it. The receiver
reads the file's own tags first and uses these only where the file says nothing, as a
rendered track's file may not.

Several tracks sent together carry `batch` (`index` from 1, and `count`), so the receiver can
say "3 of 12" while they come; the phone shows it in a notification, wherever the user is.

A playlist can also be asked for. `list` with kind `playlists` gives the device's playlists
that have tracks, and `get` with `playlist` makes it send that one exactly as its own Send
would: its tracks, one after another, each carrying the playlist. An older device answers
the listing with nothing and the `get` with `NOT_FOUND`.

A receiver never takes the same bytes twice. Before it accepts a `music` offer it looks for
a track with the same size and SHA-256 (hashing only files of that size), and if it has one
it answers `reject` with reason `have`, adds that track to the offer's playlist, and the
sender counts the track as sent. Sending a playlist whose songs the other device already has
therefore only makes the playlist. It answers `have` only to a sender that offered
`"have": true`, so an older sender never meets a reply it cannot read; a receiver that does
not know `playlist` ignores it and keeps the track.

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
