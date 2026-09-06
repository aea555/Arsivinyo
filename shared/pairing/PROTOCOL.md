# Arsivinyo device pairing

Draft. Nothing implements this yet.

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

- Android: private key in the Keystore, hardware-backed where available, non-exportable.
- Desktop: private key in a file readable only by the user, until each platform's keystore
  is wired up.

A device that loses its key is a new device and must pair again. This is intended: it is
the same property that makes a stolen phone's pairing useless once wiped.

## Discovery

mDNS / DNS-SD, service type `_arsivinyo._tcp`. TXT records:

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
5. **Both devices display the same six digits**, derived as
   `SHA-256(sorted(pubkey_a, pubkey_b))` truncated to 20 bits. The user confirms they
   match, which authenticates the phone's key to the desktop.
6. Each stores the other's public key and a user-visible name.

The QR authenticates one direction; the confirmation code authenticates the other. Both
are needed. Sorting the keys before hashing keeps the code identical on both ends.

Unpairing is local and one-sided: forget the key. There is no protocol message for it,
because a device that has been forgotten should not be told.

## Transport

TLS 1.3. Each side presents a self-signed certificate whose public key is its identity
key, and verifies the peer's certificate key against the fingerprint stored at pairing.
No certificate authority is involved: the pairing *is* the trust.

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
{"t":"hello","v":1,"name":"Desktop","caps":["put","get","list","download"]}

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
