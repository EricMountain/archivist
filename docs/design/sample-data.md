# Sample data

Nine assets as they'd actually sit in `archivist-media`, chosen to exercise the awkward
parts of `design.md` rather than the happy path: multi-rendition grouping, all four
`takenAtSrc` rungs, three `tzSrc` rungs, chunked encryption, and a sort-key tie.

## Conventions

* Owner is `01J7XQP4M2N8VBKD3RTYFW9GHC` throughout, abbreviated to `O#01J7X…` in `pk`
  columns only. ULIDs are shown in full elsewhere — they're 26 characters, and the
  width is part of what you're checking.
* Encrypted material (`encDek`, `encIv`) is shown as `<b64>`; `contentHash` truncated.
* Buckets: `pa-originals` and `pa-derived`.
* `thumbs` is written on one line as `{size: bytes}` — each entry really holds
  `{bucket, key, iv, bytes}`, with keys of the form `th/<ownerId>/<photoId>/<size>`.

## The nine assets

| # | Asset | Renditions | Exercises |
| --- | --- | --- | --- |
| A1 | Temple, Kyoto | 1 (heic) | the ordinary case |
| A2 | Canon R5 frame | 2 (cr3 + jpg) | grouping, chunking, device tz default |
| A3 | Live Photo | 2 (heic + mov) | motion rendition, no stray video in timeline |
| A4 | Scanned 1998 wedding photo | 1 (jpg) | no EXIF at all → `file-mtime`, `assumed-utc` |
| A5 | Alps, wrong camera clock | 1 (jpg) | `upload-forced` offset override |
| A6 | Burst frame 1 | 1 (jpg) | identical `takenAt` … |
| A7 | Burst frame 2 | 1 (jpg) | … resolved by `photoId` tiebreak |
| A8 | Video clip | 1 (mp4) | 480 MB, chunked, video as its own asset |
| A9 | Trashed duplicate | 1 (jpg) | soft delete — absent from the timeline, present in trash |

---

## A1 — the ordinary case

One file, EXIF offset present, nothing surprising. Shown with its full facet set; later
assets have theirs summarised.

```text
pk                                sk          attributes
O#01J7X…#M#01K5A2Q8ZCV1D9KXM3BQNR7T2F
                                  #META       ownerId     01J7XQP4M2N8VBKD3RTYFW9GHC
                                              photoId     01K5A2Q8ZCV1D9KXM3BQNR7T2F
                                              stem        2026/07-japan/IMG_4021
                                              primaryRend 01K5A2Q8ZCW4MB7XKQNV2HTRF3
                                              renditions  1
                                              mime        image/heic
                                              width       4032
                                              height      3024
                                              enc         AES-256-GCM
                                              encDek      <b64>
                                              encKeyId    mk-2026-03
                                              takenAt     2026-07-14T09:22:05.000Z
                                              tzOffsetMin 540
                                              tzSrc       exif-offset
                                              takenAtSrc  exif
                                              deviceKey   google|pixel 9|-
                                              uploadedAt  2026-08-04T11:31:00.000Z
                                              thumbs      {256: 14336, 1024: 152576,
                                                           2048: 358400}
                                              exifEnc     <b64>   ← includes GPS
                                              exifIv      <b64>
                                              groupSrc    stem
                                              status      ready
                                              gsi1pk      O#01J7XQP4M2N8VBKD3RTYFW9GHC
                                              gsi1sk      2026-07-14T09:22:05.000Z#
                                                          01K5A2Q8ZCV1D9KXM3BQNR7T2F

                                  R#01K5A2Q8ZCW4MB7XKQNV2HTRF3
                                              renditionId  01K5A2Q8ZCW4MB7XKQNV2HTRF3
                                              role         display
                                              path         2026/07-japan/IMG_4021.HEIC
                                              ext          heic
                                              mime         image/heic
                                              s3Bucket     pa-originals
                                              s3Key        raw/01J7XQP4…/01K5A2Q8ZCW4…
                                              contentHash  hmac-sha256:9f2c41ab…
                                              bytes        4823931
                                              plainBytes   4823919
                                              width        4032
                                              height       3024
                                              encIv        <b64>
                                              encChunkSize 0
                                              addedAt      2026-08-04T11:31:00.000Z

                                  F#CAMERA#Google Pixel 9
                                  F#DEVICE#google|pixel 9|-
                                  F#LABEL#architecture
                                  F#LABEL#building
                                  F#LABEL#shrine
                                  F#LABEL#temple
                                  F#LABEL#tree
                                  F#REND#display
                                  F#YEAR#2026
```

Every facet item carries the same shape. One expanded, to show that it holds enough to
paint a grid cell without touching `#META`:

```text
pk           O#01J7X…#M#01K5A2Q8ZCV1D9KXM3BQNR7T2F
sk           F#LABEL#temple
facetType    LABEL
facetValue   temple
confidence   0.93
labelSrc     rekognition
takenAt      2026-07-14T09:22:05.000Z
tzOffsetMin  540
thumbs       {256: 14336, 1024: 152576, 2048: 358400}
encDek       <b64>
encKeyId     mk-2026-03
width        4032
height       3024
gsi2pk       O#01J7XQP4M2N8VBKD3RTYFW9GHC#F#LABEL#temple
gsi2sk       2026-07-14T09:22:05.000Z#01K5A2Q8ZCV1D9KXM3BQNR7T2F
```

---

## A2 — RAW + JPEG, one asset

The R5 writes no `OffsetTimeOriginal` and this frame has no GPS, so the offset comes
from the device config item (rung 5, `tzSrc: device`). The CR3 is 50 MB, over the 32 MB
threshold, so it's chunked; the JPEG isn't.

```text
O#01J7X…#M#01K5A2QB3HN7WYP2GKD4RVXM8C
                #META    stem        2026/07-japan/IMG_8123
                         primaryRend 01K5A2QB3HTF9WNX2MHQVRD6BY   ← the JPEG
                         renditions  2
                         mime        image/jpeg
                         width       8192
                         height      5464
                         takenAt     2026-07-13T16:48:20.000Z
                         tzOffsetMin 540
                         tzSrc       device
                         takenAtSrc  exif
                         deviceKey   canon|eos r5|042024001234
                         thumbs      {256: 15360, 1024: 168960, 2048: 401408}
                         groupSrc    stem
                         status      ready
                         gsi1sk      2026-07-13T16:48:20.000Z#
                                     01K5A2QB3HN7WYP2GKD4RVXM8C

                R#01K5A2QB3HQ8VMT5XKND7WBFR2
                         role         raw
                         path         2026/07-japan/IMG_8123.CR3
                         ext          cr3
                         mime         image/x-canon-cr3
                         plainBytes   52428800          ← 50 MB, over threshold
                         bytes        52429624
                         encChunkSize 1048576           ← chunked
                         encIv        <b64>

                R#01K5A2QB3HTF9WNX2MHQVRD6BY
                         role         display
                         path         2026/07-japan/IMG_8123.JPG
                         ext          jpg
                         mime         image/jpeg
                         plainBytes   8388608
                         bytes        8388620
                         encChunkSize 0                 ← whole-object
                         encIv        <b64>

                F#CAMERA#Canon EOS R5
                F#DEVICE#canon|eos r5|042024001234
                F#LENS#RF24-70mm F2.8 L IS USM
                F#REND#display
                F#REND#raw                              ← makes access pattern 8 work
                F#YEAR#2026
                … 14 LABEL facets
```

**One `#META`, so one row in GSI1.** The RAW never reaches the timeline.

---

## A3 — Live Photo

`IMG_4022.HEIC` and `IMG_4022.MOV` share a stem, so the MOV attaches as `motion`
instead of appearing as a separate video two rows away.

```text
O#01J7X…#M#01K5A2QDM4TZB6QNH9FKW3PXR2
                #META    stem        2026/07-japan/IMG_4022
                         primaryRend 01K5A2QDM4VXK2BQ7NTWHRD9FM
                         renditions  2
                         takenAt     2026-07-14T10:05:41.000Z
                         tzOffsetMin 540
                         tzSrc       exif-offset
                         takenAtSrc  exif
                         deviceKey   google|pixel 9|-

                R#01K5A2QDM4VXK2BQ7NTWHRD9FM
                         role  display   path  2026/07-japan/IMG_4022.HEIC
                         plainBytes 3906560   encChunkSize 0

                R#01K5A2QDM4WBN8XQ3KTVYHRF2D
                         role  motion    path  2026/07-japan/IMG_4022.MOV
                         mime  video/quicktime
                         plainBytes 3355443   encChunkSize 0

                F#REND#display   F#REND#motion   F#CAMERA#Google Pixel 9
                … 9 more facets
```

---

## A4 — scanned photo, no EXIF whatsoever

The honest failure case. The wedding was in 1998; the scan's file mtime is 2011, so
that's where it lands in the timeline. Deterministic and sortable, but wrong — which is
what `takenAtSrc: file-mtime` exists to tell the UI.

```text
O#01J7X…#M#01K5A2QF7NKD3VYB8MQXTG5HW9
                #META    stem        archive/scans/wedding-1998
                         primaryRend 01K5A2QF7NPXM2BQK9NTWVRD4H
                         renditions  1
                         mime        image/jpeg
                         width       2400
                         height      1600
                         takenAt     2011-03-02T19:44:10.000Z   ← scan date
                         tzOffsetMin 0
                         tzSrc       assumed-utc
                         takenAtSrc  file-mtime
                         deviceKey   (absent — no Make/Model in EXIF)
                         exifEnc     (absent — no EXIF to encrypt)
                         status      ready

                R#01K5A2QF7NPXM2BQK9NTWVRD4H
                         role  display   path  archive/scans/wedding-1998.jpg
                         plainBytes 1258291   encChunkSize 0

                F#REND#display   F#YEAR#2011
                … 6 LABEL facets
```

No `CAMERA`, `DEVICE` or `LENS` facets — nothing to index. `YEAR` is
2011, following `takenAt` rather than the truth, which is the same limitation wearing a
different hat.

---

## A5 — camera clock was wrong all trip

Nikon body set to the wrong zone. The uploader passed `tzOffsetMin: 120` with
`offsetMode: force`, so it beats the EXIF the camera wrote (rung 1).

```text
O#01J7X…#M#01K5A2QH2WPB9NXK4TDMR6YFV3
                #META    stem        2026/06-alps/DSC_0042
                         renditions  1
                         takenAt     2026-06-21T14:30:00.000Z
                         tzOffsetMin 120
                         tzSrc       upload-forced      ← beat exif-offset
                         takenAtSrc  exif
                         deviceKey   nikon|d750|3021447

                R#01K5A2QH2WQD7VMB3KNXWTRH9F
                         role  display   path  2026/06-alps/DSC_0042.JPG
                         plainBytes 6291456   encChunkSize 0

                F#CAMERA#Nikon D750   F#DEVICE#nikon|d750|3021447
                F#REND#display        F#YEAR#2026
                … 11 more facets
```

---

## A6 / A7 — burst frames, identical timestamp

Both exposed within the same second, so EXIF gives both `11:03:12`. The `photoId`
suffix in `gsi1sk` is what keeps the ordering total, and therefore what keeps cursor
pagination from skipping or repeating a row.

```text
O#01J7X…#M#01K5A2QKB8YM5RVT7NQXHD2WFG
                #META    stem     2026/07-japan/IMG_4030
                         takenAt  2026-07-15T11:03:12.000Z
                         gsi1sk   2026-07-15T11:03:12.000Z#01K5A2QKB8YM5RVT7NQXHD2WFG
                R#01K5A2QKB82NRK8WXQ5BTMVDH7
                         role display   path 2026/07-japan/IMG_4030.JPG

O#01J7X…#M#01K5A2QMD3ZQ8WKN6BVYTX4HRP
                #META    stem     2026/07-japan/IMG_4031
                         takenAt  2026-07-15T11:03:12.000Z
                         gsi1sk   2026-07-15T11:03:12.000Z#01K5A2QMD3ZQ8WKN6BVYTX4HRP
                R#01K5A2QMD33PWD6KQX9NMTVBRF
                         role display   path 2026/07-japan/IMG_4031.JPG
```

Identical prefix, different suffix. Because ULIDs sort by creation time, the frames also
happen to land in shutter order.

---

## A8 — video

Its own asset: no sibling shares the stem `VID_0009`, so nothing to group with.

```text
O#01J7X…#M#01K5A2QPF9XT2HMB5RKWNVQ3YD
                #META    stem        2026/07-japan/VID_0009
                         renditions  1
                         mime        video/mp4
                         width       3840
                         height      2160
                         takenAt     2026-07-16T04:15:33.000Z
                         tzOffsetMin 540
                         tzSrc       exif-offset
                         takenAtSrc  exif
                         thumbs      {256: 12288, 1024: 141312, 2048: 331776}

                R#01K5A2QPF94QXM7BND2VTKWRHG
                         role  display   path  2026/07-japan/VID_0009.MP4
                         plainBytes   503316480      ← 480 MB
                         bytes        503324160      ← +16 B tag per 1 MiB chunk
                         encChunkSize 1048576        ← chunked, so seeking works

                F#REND#display   F#YEAR#2026   F#DEVICE#google|pixel 9|-
                … 8 more facets
```

The ciphertext is 7,680 bytes larger than the plaintext: 480 chunks × 16-byte GCM tag.
That's the overhead that makes the range arithmetic in `design.md` necessary.

---

## A9 — trashed

Deleted on 6 August, so it holds a trash `gsi1pk` instead of the timeline one. Nothing
filters it out of the timeline: it simply isn't in that partition of the index.

```text
O#01J7X…#M#01K5A2QRG2WBK4XN7QTMVD3HRY
                #META    stem        2026/07-japan/IMG_4025
                         renditions  1
                         mime        image/jpeg
                         takenAt     2026-07-14T12:40:18.000Z   ← kept, for restore
                         tzOffsetMin 540
                         tzSrc       exif-offset
                         takenAtSrc  exif
                         deviceKey   google|pixel 9|-
                         deletedAt   2026-08-06T20:14:52.000Z
                         deletedBy   "Pixel 9"
                         status      ready                      ← orthogonal to deletion
                         gsi1pk      O#01J7XQP4M2N8VBKD3RTYFW9GHC#TRASH
                         gsi1sk      2026-08-06T20:14:52.000Z#
                                     01K5A2QRG2WBK4XN7QTMVD3HRY

                R#01K5A2QRG25XMQ7BN3KWTVDH9F
                         role  display   path  2026/07-japan/IMG_4025.JPG
                         plainBytes 3407872   encChunkSize 0

                F#LABEL#temple        ← still exists, but with NO gsi2pk/gsi2sk
                F#CAMERA#Google Pixel 9
                F#REND#display
                … 9 more facets, all with their GSI2 keys removed
```

Two things to notice. `gsi1sk` sorts by `deletedAt`, not `takenAt` — the trash is
ordered by when you deleted things, and that's also what makes the purge sweep a range
query. And `takenAt` is untouched, which is what lets a restore recompute the timeline
sort key without consulting anything else.

Its pointers are **still present**:

```text
O#01J7X…#STEM#2026/07-japan/IMG_4025        #PTR   → photoId 01K5A2QRG2…
O#01J7X…#PATH#2026/07-japan/IMG_4025.JPG    #PTR   → photoId …, renditionId …
O#01J7X…#HASH#hmac-sha256:4d81f0c6…         #PTR   → photoId …, renditionId …
```

So re-uploading that exact path gets a clear "in the trash" error rather than a
collision on restore, and a hash-matching re-import is a conflict rather than a silent
resurrection.

## Pointer items

Flat, tiny, and the only place a path appears outside an `R#` item.

```text
pk                                              sk     resolves to
O#01J7X…#STEM#2026/07-japan/IMG_4021            #PTR   photoId 01K5A2Q8ZCV1D9…
O#01J7X…#STEM#2026/07-japan/IMG_8123            #PTR   photoId 01K5A2QB3HN7WY…
O#01J7X…#STEM#archive/scans/wedding-1998        #PTR   photoId 01K5A2QF7NKD3V…

O#01J7X…#PATH#2026/07-japan/IMG_4021.HEIC       #PTR   photoId …, renditionId …
O#01J7X…#PATH#2026/07-japan/IMG_8123.CR3        #PTR   photoId …, renditionId …
O#01J7X…#PATH#2026/07-japan/IMG_8123.JPG        #PTR   photoId …, renditionId …
                                                       ↑ same photoId, different rend

O#01J7X…#HASH#hmac-sha256:9f2c41ab…             #PTR   kind live, photoId …, rend …
O#01J7X…#HASH#hmac-sha256:c7e0b83d…             #PTR   kind live, photoId …, rend …

O#01J7X…#HASH#hmac-sha256:e5a90271…             #PTR   kind purged
                                                       purgedAt 2026-06-30T08:11:04Z
```

The last one is a **tombstone** — an asset deleted and then purged in June. It resolves
to no photo; it exists so that the file still sitting on the phone isn't re-uploaded on
the next sync. Its `PATH` and `STEM` pointers were removed at purge, because a path is
a reusable name while a hash is content identity.

The two `IMG_8123` path pointers resolving to one `photoId` *is* the grouping, viewed
from the other end.

## User and owner items

The identity indirection, at the scale it actually runs at today: one user, one
library, two sign-in methods pointing at the same person.

```text
pk                                    sk            attributes
O#01J7XQP4M2N8VBKD3RTYFW9GHC          #SETTINGS     ownerId      01J7XQP4M2N8VBKD3RT…
                                                    displayName  "Home photos"
                                                    homeTz       Europe/Paris
                                                    createdAt    2026-08-08T09:12:00.000Z

U#01J7XRB6K3PQ8WNVD2MTYX4HFG          #PROFILE      userId       01J7XRB6K3PQ8WNVD2M…
                                                    email        …
                                                    displayName  "Sam"
                                                    createdAt    2026-08-08T09:12:00.000Z

U#01J7XRB6K3PQ8WNVD2MTYX4HFG          M#01J7XQP4M2N8VBKD3RTYFW9GHC
                                                    role         owner

IDP#cognito#a7f3e19c-4b82-4d6e-…      #PTR          userId       01J7XRB6K3PQ8WNVD2M…
IDP#google#116384927461028374651      #PTR          userId       01J7XRB6K3PQ8WNVD2M…
```

Both `IDP` pointers resolve to one `userId`, which is the whole reason for the
indirection: adding Google alongside Cognito was one `PutItem`, not a migration. Note
`homeTz` is `Europe/Paris` rather than `+60` — offset rung 6 resolves it against each
photo's local date, so summer photos get `+120` and winter ones `+60`.

## Owner-level items

```text
pk                        sk                              attributes
O#01J7X…#DEVICES          D#canon|eos r5|042024001234     label       "Canon R5"
                                                          tzOffsetMin 540
                                                          firstSeenAt 2026-03-02T…
                                                          photoCount  4821
O#01J7X…#DEVICES          D#google|pixel 9|-              label       "Pixel 9"
                                                          tzOffsetMin (unset)
                                                          photoCount  11204
O#01J7X…#DEVICES          D#nikon|d750|3021447            label       "Nikon D750"
                                                          tzOffsetMin 60
                                                          photoCount  392

O#01J7X…#KEYS             W#01K5A2P4XNVBQ7MK3NTXWD9HF2    kind        device
                                                          label       "Pixel 9"
                                                          wrapAlg     RSA-OAEP-256
                                                          masterKeyVer mk-2026-03
O#01J7X…#KEYS             W#01K5A2P4XNWD3KQB8NMXVTRH5Y    kind        passkey
                                                          label       "Firefox / desktop"
                                                          credentialId <b64>
                                                          prfSalt      <b64>
O#01J7X…#KEYS             W#01K5A2P4XNXM9BVQ2KTNWRDH4G    kind        recovery
                                                          kdfSalt      <b64>
                                                          kdfParams    argon2id m=64MiB
                                                                       t=3 p=1
```

Note the Pixel 9 has no `tzOffsetMin` — it writes `OffsetTimeOriginal`, so it never
needs rung 5. The D750 does need one.

## GSI1 — the timeline

Every `#META` item, one row each, no renditions, no facets, no pointers. Queried
descending, this is the infinite scroll.

```text
gsi1pk                        gsi1sk                                              asset
O#01J7XQP4M2N8VBKD3RTYFW9GHC  2026-07-16T04:15:33.000Z#01K5A2QPF9XT2HMB5RKWNVQ3YD  A8
O#01J7XQP4M2N8VBKD3RTYFW9GHC  2026-07-15T11:03:12.000Z#01K5A2QMD3ZQ8WKN6BVYTX4HRP  A7
O#01J7XQP4M2N8VBKD3RTYFW9GHC  2026-07-15T11:03:12.000Z#01K5A2QKB8YM5RVT7NQXHD2WFG  A6
O#01J7XQP4M2N8VBKD3RTYFW9GHC  2026-07-14T10:05:41.000Z#01K5A2QDM4TZB6QNH9FKW3PXR2  A3
O#01J7XQP4M2N8VBKD3RTYFW9GHC  2026-07-14T09:22:05.000Z#01K5A2Q8ZCV1D9KXM3BQNR7T2F  A1
O#01J7XQP4M2N8VBKD3RTYFW9GHC  2026-07-13T16:48:20.000Z#01K5A2QB3HN7WYP2GKD4RVXM8C  A2
O#01J7XQP4M2N8VBKD3RTYFW9GHC  2026-06-21T14:30:00.000Z#01K5A2QH2WPB9NXK4TDMR6YFV3  A5
O#01J7XQP4M2N8VBKD3RTYFW9GHC  2011-03-02T19:44:10.000Z#01K5A2QF7NKD3VYB8MQXTG5HW9  A4
```

Eight rows for **nine** assets, from twelve files. A2 and A3 each contribute two files
and one row; A9 contributes none, because its `gsi1pk` points at a different partition
of this same index:

```text
gsi1pk                              gsi1sk                                        asset
O#01J7XQP4M2N8VBKD3RTYFW9GHC#TRASH  2026-08-06T20:14:52.000Z#01K5A2QRG2WBK4XN…    A9
```

Query 3 ("July 2026") is the same timeline query with
`BETWEEN 2026-07-01… AND 2026-08-01…`, which takes the top six. A9 doesn't appear even
though it was taken on 14 July, because the range condition only ever runs against the
live partition.

## GSI2 — facets

A slice, showing three different facet types sharing one index:

```text
gsi2pk                                          gsi2sk                        asset
O#01J7X…#F#LABEL#temple                         2026-07-14T09:22:05.000Z#01K5…  A1
O#01J7X…#F#LABEL#temple                         2026-07-13T16:48:20.000Z#01K5…  A2

O#01J7X…#F#REND#raw                             2026-07-13T16:48:20.000Z#01K5…  A2

O#01J7X…#F#DEVICE#canon|eos r5|042024001234     2026-07-13T16:48:20.000Z#01K5…  A2

O#01J7X…#F#CAMERA#Google Pixel 9                2026-07-16T04:15:33.000Z#01K5…  A8
O#01J7X…#F#CAMERA#Google Pixel 9                2026-07-14T10:05:41.000Z#01K5…  A3
O#01J7X…#F#CAMERA#Google Pixel 9                2026-07-14T09:22:05.000Z#01K5…  A1
```

Same sort key shape as GSI1, which is why "temples, newest first" and "temples in July"
are one query with a different `BETWEEN`.

## What to check while reading

1. **Twelve files, nine assets, eight timeline rows.** A2 and A3 each contribute two
   files and one row; A9 is trashed and contributes none. Nothing filters either case
   out — renditions never get GSI1 keys, and A9's point at a different partition.
2. **A6 and A7 share a timestamp to the millisecond.** Only the ULID suffix separates
   them, which is the whole reason `gsi1sk` isn't just `takenAt`.
3. **A4 sits in 2011.** The design can't do better without lying, and `takenAtSrc` is
   how the UI knows to say so.
4. **Three different `tzSrc` values across A1, A2 and A5**, each resolved from a
   different rung, with A2's coming from a device config item that A1's device doesn't
   need.
5. **Facet items are self-sufficient.** Each carries `thumbs`, `encDek` and dimensions,
   so a facet query paints a grid with no second read.
6. **`path` appears in exactly two places**: `R#` items and pointer items. Not on
   `#META`, not in facets, not in either GSI — which is what makes renaming cheap.
7. **Nothing in the media partitions references a `userId`.** Media hangs off
   `ownerId`; users reach it through a membership item. That's what would let a second
   person be added without touching a single photo row.

## Queries

Every access pattern from `design.md` as a real call, against the rows above. Owner is
abbreviated `01J7X…` for width; substitute the full ULID.

### The timeline (patterns 2, 3)

The one that runs constantly. Note `ScanIndexForward: false` for newest-first, and that
GSI1's `INCLUDE` projection is what makes this a single round-trip.

```js
{
  TableName: "archivist-media",
  IndexName: "GSI1",
  KeyConditionExpression: "gsi1pk = :o",
  ExpressionAttributeValues: { ":o": "O#01J7X…" },
  ScanIndexForward: false,
  Limit: 50
}
// → A8, A7, A6, A3, A1, A2, A5, A4 — eight assets, never a rendition
```

Next page: pass back the previous response's `LastEvaluatedKey` verbatim.

```js
{
  …,
  ExclusiveStartKey: {
    gsi1pk: "O#01J7X…",
    gsi1sk: "2026-07-14T09:22:05.000Z#01K5A2Q8ZCV1D9KXM3BQNR7T2F",
    pk:     "O#01J7X…#M#01K5A2Q8ZCV1D9KXM3BQNR7T2F",
    sk:     "#META"
  }
}
```

A GSI cursor carries **four** attributes — both index keys *and* both table keys. Easy
to get wrong by storing only the index half. This is also why the cursor is base64'd
opaquely for clients: it exposes key structure that the sharding escape hatch would
change.

July 2026 only (pattern 3) is the same query with a range condition:

```js
{
  IndexName: "GSI1",
  KeyConditionExpression: "gsi1pk = :o AND gsi1sk BETWEEN :from AND :to",
  ExpressionAttributeValues: {
    ":o":    "O#01J7X…",
    ":from": "2026-07-01T00:00:00.000Z",
    ":to":   "2026-08-01T00:00:00.000Z"
  },
  ScanIndexForward: false
}
// → A8, A7, A6, A3, A1, A2 — the top six; A5 (June) and A4 (2011) fall outside
```

Because the sort key is `<takenAt>#<photoId>`, `:to` needs no `#` suffix — every real
key at that instant sorts after the bare timestamp.

### Facets (patterns 4, 5, 7, 8)

All four are one query shape with a different partition key.

```js
{
  IndexName: "GSI2",
  KeyConditionExpression: "gsi2pk = :f",
  ExpressionAttributeValues: { ":f": "O#01J7X…#F#LABEL#temple" },
  ScanIndexForward: false
}
// → A1, A2
```

```js
":f": "O#01J7X…#F#REND#raw"                          // → A2         (pattern 8)
":f": "O#01J7X…#F#CAMERA#Google Pixel 9"             // → A8, A3, A1 (pattern 5)
":f": "O#01J7X…#F#DEVICE#canon|eos r5|042024001234"  // → A2         (pattern 7)
```

And a facet restricted to a date range needs nothing new, because GSI2 sorts by the
same key as GSI1:

```js
{
  IndexName: "GSI2",
  KeyConditionExpression: "gsi2pk = :f AND gsi2sk BETWEEN :from AND :to",
  ExpressionAttributeValues: {
    ":f":    "O#01J7X…#F#CAMERA#Google Pixel 9",
    ":from": "2026-07-15T00:00:00.000Z",
    ":to":   "2026-07-17T00:00:00.000Z"
  },
  ScanIndexForward: false
}
// → A8 only
```

### One photo (patterns 1, 1b, 1c)

Everything about an asset — metadata, renditions and facets — in one read:

```js
{
  TableName: "archivist-media",
  KeyConditionExpression: "pk = :p",
  ExpressionAttributeValues: { ":p": "O#01J7X…#M#01K5A2QB3HN7WYP2GKD4RVXM8C" }
}
// → #META, R#…(cr3), R#…(jpg), and A2's ~20 facet items
```

Just the renditions, for a detail view offering "download RAW":

```js
{
  KeyConditionExpression: "pk = :p AND begins_with(sk, :r)",
  ExpressionAttributeValues: { ":p": "O#01J7X…#M#01K5A2QB3H…", ":r": "R#" }
}
// → the two R# items, nothing else
```

Sort-key prefixes are why `#META` starts with `#`: it sorts before `F#` and `R#`, so a
forward query always returns metadata first.

By path (pattern 1b) is two reads, pointer then item:

```js
// 1
{ TableName: "archivist-media",
  Key: { pk: "O#01J7X…#PATH#2026/07-japan/IMG_8123.CR3", sk: "#PTR" },
  ConsistentRead: true }
// → { photoId: "01K5A2QB3H…", renditionId: "01K5A2QB3H…" }

// 2
{ TableName: "archivist-media",
  Key: { pk: "O#01J7X…#M#01K5A2QB3H…", sk: "#META" },
  ProjectionExpression: "photoId, stem, takenAt, thumbs, #st",
  ExpressionAttributeNames: { "#st": "status" } }
```

`status` is a DynamoDB reserved word, as are `path`, `role`, `bytes` and `size` — all of
which appear in this schema, so projections touching them need
`ExpressionAttributeNames`.

### Identity and settings (patterns 6, 9, 10)

```js
// login: JWT sub → user
{ Key: { pk: "IDP#cognito#a7f3e19c-4b82-4d6e-…", sk: "#PTR" }, ConsistentRead: true }
// → userId 01J7XRB6K3…

// which libraries can they see
{ KeyConditionExpression: "pk = :u AND begins_with(sk, :m)",
  ExpressionAttributeValues: { ":u": "U#01J7XRB6K3…", ":m": "M#" } }
// → one membership, role owner, ownerId 01J7XQP4M2…

// the settings screen's device list
{ KeyConditionExpression: "pk = :d",
  ExpressionAttributeValues: { ":d": "O#01J7X…#DEVICES" } }
// → Canon R5, Pixel 9, Nikon D750

// ingest resolving one device's default offset
{ Key: { pk: "O#01J7X…#DEVICES", sk: "D#canon|eos r5|042024001234" } }
// → tzOffsetMin 540
```

### Trash (patterns 11, 12)

Same index, same query shape, different partition:

```js
{
  IndexName: "GSI1",
  KeyConditionExpression: "gsi1pk = :t",
  ExpressionAttributeValues: { ":t": "O#01J7X…#TRASH" },
  ScanIndexForward: false
}
// → A9, most recently deleted first
```

The purge sweep is that query with an upper bound, so it returns exactly what's due:

```js
{
  IndexName: "GSI1",
  KeyConditionExpression: "gsi1pk = :t AND gsi1sk < :cutoff",
  ExpressionAttributeValues: {
    ":t":      "O#01J7X…#TRASH",
    ":cutoff": "2026-07-07T00:00:00.000Z"   // now − trashRetentionDays
  }
}
// → nothing yet; A9 becomes due on 2026-09-05
```

Trashing an asset, which is what removes it from the timeline:

```js
// #META — move it between partitions of GSI1
{
  UpdateExpression: `SET gsi1pk = :trash, gsi1sk = :dsk,
                         deletedAt = :now, deletedBy = :dev`,
  ConditionExpression: "attribute_not_exists(deletedAt)",
  ExpressionAttributeValues: {
    ":trash": "O#01J7X…#TRASH",
    ":dsk":   "2026-08-06T20:14:52.000Z#01K5A2QRG2WBK4XN7QTMVD3HRY",
    ":now":   "2026-08-06T20:14:52.000Z",
    ":dev":   "Pixel 9"
  }
}

// each F# item — drop out of GSI2 entirely
{ UpdateExpression: "REMOVE gsi2pk, gsi2sk" }
```

The `ConditionExpression` makes a double-delete a no-op rather than something that
overwrites the original `deletedAt` and quietly extends the retention window.

Restore is the inverse, and needs no stored state beyond `takenAt`:

```js
{
  UpdateExpression: `SET gsi1pk = :live, gsi1sk = :tsk
                     REMOVE deletedAt, deletedBy`,
  ExpressionAttributeValues: {
    ":live": "O#01J7X…",
    ":tsk":  "2026-07-14T12:40:18.000Z#01K5A2QRG2WBK4XN7QTMVD3HRY"
  }
}
```

### Writes

Ingest is covered in `design.md` under "Writing them". The dedup check that precedes
it, though, has three outcomes rather than two:

```js
{ Key: { pk: "O#01J7X…#HASH#hmac-sha256:e5a90271…", sk: "#PTR" }, ConsistentRead: true }
```

| Result | Meaning | Action |
| --- | --- | --- |
| no item | new bytes | ingest normally |
| `kind: live` | already held | skip, idempotent re-import |
| `kind: purged` | deliberately deleted | skip **silently**, unless `reAddDeleted` |

The third row is what stops a phone re-uploading a photo the user deleted from the
archive but kept on the device. Without it the file returns on the first sync after the
trash purges.

The others:

**Rename one file** — three items, atomic, no S3 traffic:

```js
TransactWriteItems: [
  { Delete: { Key: { pk: "O#01J7X…#PATH#2026/07-japan/IMG_8123.CR3", sk: "#PTR" } } },
  { Put:    { Item: { pk: "O#01J7X…#PATH#2026/07-japan/kyoto-01.CR3", sk: "#PTR", … },
              ConditionExpression: "attribute_not_exists(pk)" } },
  { Update: { Key: { pk: "O#01J7X…#M#01K5A2QB3H…", sk: "R#01K5A2QB3HQ8VM…" },
              UpdateExpression: "SET #p = :new",
              ExpressionAttributeNames: { "#p": "path" } } }
]
```

Twenty facet items, both GSIs and every thumbnail are untouched.

**Delete an asset** — query the partition, derive the pointers from what comes back,
batch-delete both:

```js
// 1. Query pk = O#01J7X…#M#01K5A2QB3H…   → #META + 2 R# + ~20 F#
// 2. BatchWriteItem deletes:
//      those ~23 items
//      O#01J7X…#STEM#<#META.stem>
//      O#01J7X…#PATH#<path>  ×2      from the R# items
// 3. and converts, rather than deletes:
//      O#01J7X…#HASH#<hash>  ×2      → kind: purged, drop photoId/renditionId
```

No secondary index is needed to find the pointers — every one is reconstructible from
the partition's own contents.

### What you cannot do

**Intersect two facets.** "Temples shot on the R5" is not a query. Run the more
selective side and filter:

```js
// F#REND#raw → 1 asset;  F#LABEL#temple → 2 assets.  Query the former,
// then Query pk = <each result> with begins_with(sk, "F#LABEL#temple")
```

Fine at two facets and a few hundred candidates. If this becomes a normal user action
rather than an occasional one, it's the signal to add OpenSearch — not a third GSI.

**Search text.** There is no substring or fuzzy matching. `F#LABEL#temp` matches
nothing; facet values are exact. Autocomplete over an owner's known label vocabulary
would need its own item or a client-side cache of the facet list.

**Sort by anything but time.** Both GSIs sort by `takenAt`. "Largest files first" or
"by camera then date" means a scan, or another index.

## Maintenance

This file is part of the design, not an illustration of it. It must be updated whenever
the data model in `design.md` changes — key structure, item types, attribute names, GSI
keys or projections — in the same change, not afterwards.

Keep the bias towards awkward cases. The value here is that A4 has no EXIF, A6 and A7
collide on `takenAt`, A2 carries two renditions and A9 is trashed; a sample of nine
straightforward photos would confirm nothing. When the design grows a new edge case,
give it an asset.
