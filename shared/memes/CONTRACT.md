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

**Phase 2: people by face.** On-device face grouping: Vision on the Mac, a small embedding
model on the phone. The user names a group once; it labels every meme with that face, past
and future.

**Phase 3, if phases 1 and 2 leave gaps:** speech in the videos transcribed on device
(Whisper, Turkish), and visual suggestions: "looks like memes you tagged *rahat*", learned
from the user's own labels rather than an English vocabulary.

**Not planned:** sending images to a cloud service to be described (it breaks the app's
promise that nothing leaves the device), song identification (needs Apple's paid Shazam
service), indexing the gallery at large (see Scope).
