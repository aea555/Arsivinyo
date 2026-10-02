# Memes: the contract

What both apps build for finding a meme again, and what they must agree on to exchange one.
Agreed with the maintainer before any code was written; change it here first, then in code.

## The problem

The memes worth keeping come from Turkish X/Twitter and Instagram, and they are mostly
**videos with no text on them**: people or things that fit a situation, used as a joke. Arda
Turan being careless to Turkish-German trap, shared as "bizim laubalilik seviyesi". The
meaning is not in the pixels as words, so text recognition finds almost nothing. It lives in:

- **what the meme signifies**: a reaction, a vibe, an emotion, an action, a context;
- **who is in it**: Abdullah Avcı, Fatih Terim, Aykut Kocaman, the recurring cast;
- **where it came from**: the post's caption, which is the poster saying what it is for.

## Scope

**In:** what is in Arsivinyo. Downloads, and files the user hands over: the photo picker on
the phone, file import on the Mac.

**Out:** the rest of the phone's gallery. The phone app holds no permission to read the
photo library, by design, and this feature does not change that.

Each app keeps its own collection ("both, independently"). Nothing syncs; a meme sent to the
other device, or restored from a backup, carries its labels with it.

## The labels

### What it signifies: tags

A **tag** is free: any word or phrase the user chooses, in any language. A meme has any
number of tags. A tag may also carry any number of **facets**, which describe what kind of
meaning it is and serve as search filters. A tag with no facet is fine.

| Facet id (wire) | Shown as | Examples |
|---|---|---|
| `reaction` | Tepki | iştah, beğeni, onay (Abdullah Avcı licking his lips) |
| `vibe` | Hava | rahat, keyif, yazlık (Fatih Terim in summer clothes) |
| `emotion` | Duygu | hüzün, çaresizlik (Aykut Kocaman edits) |
| `action` | Eylem | çarpma, kaos (the car knocking the rider off) |
| `context` | Bağlam | futbol, siyaset, iş |

Facet ids are wire values: never renamed in place. Labels are localised.

### Who is in it: people

A **person** is a name. A meme has any number of people. Separate from tags because a person
is an identity, and in phase 2 people are what face grouping fills in.

### Where it came from: source

Captured at download time, never typed:

| Field | From |
|---|---|
| `platform` | the downloader's site match (`twitter`, `instagram`, …) or `import` |
| `account` | the uploader handle |
| `caption` | the post's text, as the site gave it |
| `url` | the post's link |
| `postedAt` | when the post was made, if the site says |
| `savedAt` | when it entered Arsivinyo |

The downloader already receives all of this and today throws it away once the file lands.
Keeping it is the cheapest label there is. For an existing download whose link is known, the
source can be fetched again on request.

## The data

One index per app, per collection:

```jsonc
{
  "version": 1,
  "tags":   [{"id": "t…", "name": "laubalilik", "facets": ["vibe", "action"]}],
  "people": [{"id": "p…", "name": "Arda Turan"}],
  "items":  [{
    "id": "m…",
    "kind": "video",                  // "video" | "image"
    "private": false,                 // true: the file is in the vault
    "file": { … },                    // how this app finds the file; not exchanged
    "sha256": "…",                    // content hash, for duplicates and reconciliation
    "source": {"platform": "twitter", "account": "…", "caption": "bizim laubalilik seviyesi",
               "url": "…", "postedAt": 0, "savedAt": 0},
    "tags": ["t…"], "people": ["p…"],
    "addedAt": 0, "taggedAt": 0       // taggedAt 0: in the untagged inbox
  }]
}
```

Timestamps are milliseconds, as everywhere else in the apps. Ids are random, never derived
from content, so two copies of a meme can carry different labels.

**Where the files are.** Phone: an app-owned MediaStore collection (the owner model the music
library uses, no permission needed); a file from the photo picker is **copied** in, because a
picker grant does not last. Mac: a folder the user chooses, like the music folder. Private
items: the vault, exactly as vault videos are today.

## Privacy

What someone saved and why is as revealing as the files themselves.

- **The index is encrypted at rest.** Items that are not private use a device key that needs
  no prompt: the Android Keystore on the phone, the login Keychain on the Mac. So searching
  public memes never asks for a passphrase, but a copied disk or backup of app data reveals
  nothing. Private items and their labels live in a second index, under the vault's key, and
  appear in search only while the vault is unlocked.
- **Nothing from the index reaches a log or a notification.** Not a tag, not a caption, not
  an account. The quick prompt's notification says only that a meme finished downloading.
- **The opt-in meme vault.** Any meme can be made private, which moves its file into the vault
  and its labels into the private index. A setting can make downloads from chosen sources
  private by default. Off unless turned on.

## Tagging

**The quick prompt**, after a meme download completes (a notification on the phone that opens
it, a small panel on the Mac): the video looping, the post's caption, suggested tags as chips,
a field with autocomplete over tags and people that can also create a tag, **Save** and
**Later**. Later, or ignoring it, leaves the meme in the untagged inbox. It can be turned off.

**Suggestions**, without any model: caption words matching existing tags (Turkish-aware, see
below), tags used on memes from the same account, and recent tags.

**Batch tagging:** select many, apply a tag sheet to all; tags already on some are shown as
partial and can be added to all or removed from all.

**Review mode:** the untagged inbox one meme at a time, video playing, tap tags, advance.

## Search

One field. Words are matched, all of them required, against tag names, people, the caption,
and the account, by prefix. Facets, people, platform and "private" are filter chips.

**Turkish matching.** Case is folded the Turkish way (I→ı, İ→i), then letters are folded to
their plain forms (ı→i, ş→s, ğ→g, ç→c, ö→o, ü→u) on both sides, so "laubalilik",
"LAUBALİLİK" and "laubalılık" all match. The stored text keeps its real spelling.

## Between devices

**Pairing.** A `put` of a meme gains an optional `meme` object; a receiver that does not know
it ignores it, and older builds keep working.

```jsonc
{"t":"put", …, "kind":"meme",
 "meme": {"kind":"video", "source":{…}, "tags":[{"name":"…","facets":["…"]}],
          "people":[{"name":"…"}]}}
```

Tags and people travel **by name**, not id, and are merged into the receiver's by
Turkish-folded name. A private meme is never sent: the vault does not travel over pairing.

**Backup.** A new section, `memes`, alongside vault/music/settings/cookies: each file as a
`media` entry (meta: the item without `file`), then a `memes-index` blob with tags and people.
Private items go in the `vault` section with their labels in their entry meta. A reader that
does not know `memes` skips it.

## Phases, and what "done" means

**Phase 1: the collection and the labels.** Both apps.

- Meme downloads and imports land in the collection with their source captured.
- Free tags with facets, people, the quick prompt, the untagged inbox, batch tagging, review
  mode.
- Search as above, with filters.
- The opt-in meme vault.
- Pairing and backup carry memes with their labels.

Done when, on both apps:
1. A download from X or Instagram is found by a word of its caption with no tagging at all.
2. "laubalilik", "LAUBALİLİK" and "laubalılık" find the same meme.
3. A meme tagged on the phone and sent to the Mac arrives with its tags and people merged
   into the Mac's by name; a backup restores the same.
4. A private meme is absent from search while locked and present when unlocked, and its file
   is not readable outside the vault.
5. No tag, caption or account appears in the app's logs or in any notification (checked by
   test, as the vault's rule is).
6. The index file contains none of its tags or captions in plain text.

**Phase 2: people by face.** Both apps. Specified in full under "Faces" below.

**Phase 3, if phases 1 and 2 leave gaps:** speech in the videos transcribed on device
(Whisper, Turkish), and visual suggestions: "looks like memes you tagged *rahat*", learned
from the user's own labels rather than an English vocabulary.

**Not planned:** sending images to a cloud service to be described (it breaks the app's
promise that nothing leaves the device), song identification (needs Apple's paid Shazam
service), indexing the gallery at large (see Scope).

## Faces (phase 2)

Name a face once; every meme with that face is labelled with that person, past and future.
Agreed with the maintainer: **sure matches label on their own, borderline ones ask**, and **a
person's face signature travels with the person** between the two devices.

### One pipeline, both apps

Apple's Vision finds faces but has no public way to say whose they are, so the Mac needs a
recognition model as much as the phone does. Both apps therefore run the same pipeline,
written once in C++ in `shared/faces/` and compiled into each, over the same ONNX Runtime
version and the same two model files:

| Step | What | Model |
|---|---|---|
| Detect | faces and five landmarks | YuNet 2023mar (MIT, 230 KB) |
| Align | similarity transform of the landmarks onto the 112×112 ArcFace template, bilinear | — |
| Embed | 128 numbers, L2-normalised | SFace 2021dec int8 (Apache 2.0, 9.9 MB) |

Both are from the OpenCV model zoo. `shared/faces/MODELS.json` pins each file's SHA-256 and
the runtime version; an app refuses a model file that does not match. Because the files and
the code are the same, a signature made on the phone is comparable with one made on the Mac,
which is what lets a person's face travel.

**What is looked at.** An image once. A video at one frame a second, at most 12 frames,
spread evenly over the length when it is longer. A face smaller than 40 px on its short side
is ignored: meme video is low-resolution, and a smaller face yields a signature that matches
everyone. Detections below a score of 0.8 are dropped; overlapping ones are merged (IoU 0.3).

**Within one meme** the same person appears in many frames. Faces whose signatures are
closer than the "sure" threshold are one *face* of that meme: its signature is the mean of
theirs, normalised, and it keeps the frame and box of its best detection for showing.

### Matching

Similarity is the cosine of two signatures. Against a person, it is the highest cosine over
that person's signature set.

| Similarity | Meaning |
|---|---|
| ≥ 0.50 | **sure**: the person is added to the meme on its own |
| 0.36 – 0.50 | **ask**: "Is this Arda Turan?" waits in a queue |
| < 0.36 | no match: the face joins the unnamed groups |

These start from SFace's published threshold (0.363) and are constants in `shared/faces/`,
so both apps move together if they are tuned.

**Unnamed groups.** A face with nobody known joins the unnamed group whose mean signature
it is most like, at or above the sure threshold, or starts a group of its own. The group is
decided when the face becomes unnamed and stored with it, so showing the groups is reading
them, never working them out again: keeping them costs one pass over the faces when the
collection changes. Groups are shown largest first. Naming a group confirms all its faces as
that person. Groups are this device's own and do not travel.

**Corrections.** Confirming a queued face adds the person. Rejecting it records that this
face is not that person, and it is never asked again for them. Removing a label that a face
added on its own is a rejection too; a label added by hand is never removed by the faces.

**A person's signature set** is up to 8 signatures from confirmed faces, chosen to be as
different from each other as possible (a new one replaces the one closest to the rest), so
a person seen from several angles and ages is matched from all of them.

### The data

Each meme gains `faces`, and each person gains `signatures`:

```jsonc
"people": [{"id": "p…", "name": "Arda Turan", "signatures": ["<base64>", …]}],
"items":  [{ …,
  "faces": [{
    "id": "f…",
    "signature": "<base64>",           // 128 × float16, little endian
    "frameMs": 3000, "box": [x, y, w, h],   // where to show it from; video time, source pixels
    "person": "p…" | null,
    "state": "auto" | "confirmed" | "asked" | "unnamed",
    "group": "g…" | null,              // its unnamed group; null once it has a person
    "rejected": ["p…"],                 // never asked again for these; a "no" leaves it unnamed
    "added": true                       // this face put its person's label on the meme
  }],
  "facesVersion": 1                     // the pipeline that scanned it; 0 or absent: not yet
}]
```

A signature is biometric data. It lives only inside the two encrypted indexes: a private
meme's faces in the private index, like its other labels. It is never logged. A face crop
shown on screen is made from the meme's file when needed; for a private meme it is never
written outside the vault.

### Scanning

A meme is scanned after it joins the collection, in the background, one at a time. The
existing collection is scanned once, resumably: `facesVersion` says what is done, so a scan
cut short carries on where it stopped, and a later pipeline version rescans. Scanning never
blocks the screens, and pauses while a download is running on the phone.

### Between devices

A person travels by name, as before, and now with their signature set:

```jsonc
"people": [{"name": "Arda Turan", "signatures": ["<base64>", …]}]
```

in a pairing `meme` object and in the backup's `memes-index`. The receiver merges the set
into its person of the same folded name (keeping 8 as above), then rescans its unlabelled
faces against it, so naming someone on one device teaches the other. A face's own signature
does not travel: the receiver scans the file itself.

### Done when, on both apps

1. The fixture frames in `shared/faces/VECTORS.json` yield the same detections (boxes within
   1 px) and signatures (cosine ≥ 0.999 to the pinned values) on the Mac and the phone.
2. Naming an unnamed group labels its memes; a new meme with that face is labelled on its own
   when sure and queued when not.
3. A rejected suggestion is gone and never asked again; rejecting an automatic label removes
   it; a label added by hand stays.
4. A person named on the phone is recognised on the Mac after a pairing send or a backup
   restore, with no naming on the Mac, and the other way round.
5. Neither index holds a signature in plain text, none reaches a log, and no face crop of a
   private meme is written outside the vault.
6. A scan cut short resumes where it stopped, and the screens stay responsive during one.
