# Tieba capture fixtures

## Real LSPosed protobuf captures

These are binary protobuf projections, **not JSON converted to invented protobuf**.
`project_captures.py` recursively selects fields from the original wire bytes,
preserving each retained scalar/string value and field occurrence order. Only
length-delimited framing is recalculated after removing nested fields.

Run from the repository root:

```sh
python3 app/src/test/resources/tieba/project_captures.py
```

Sources are under `research/tieba/lsposed/`. `binary-manifest.json` records full
source basenames, the meta first-line URLs, sizes, and SHA-256 of both originals
and fixtures. The source dumps are not necessary for normal fixture tests. When
present locally, `TiebaBinaryCaptureTest` also parses the unmodified originals
and compares every record's identity, type, parent, title, complete Markdown,
author ID/name, publish time, floor, reply count, reply target and image URLs
against its projection. For the floor capture, exact avatar URLs are compared too. Those optional source-audit tests explicitly skip if
research files are absent.

| Original filename stem              | Original bytes | Fixture         | Fixture bytes | Records                                    |
| ----------------------------------- | -------------: | --------------- | ------------: | ------------------------------------------ |
| 1794417060889_19771_127037029400026 |          7,486 | page-empty.bin  |            26 | 0                                          |
| 1794417075762_19771_127051900145646 |        149,242 | list-149242.bin |        11,087 | 1 thread + 15 floors + 18 preview comments |
| 1794417075778_19771_127051915726844 |        156,592 | list-156592.bin |        11,709 | 1 thread + 15 floors + 18 preview comments |
| 1794417546508_19771_127522652130362 |         41,963 | floor-41963.bin |         4,352 | 1 floor + 11 complete comments             |

Both large captures really use **PbList**, matching their `/c/f/pb/list` meta
URLs. They are two responses for the same thread, not two different threads.
The three initial 7,486-byte `/page` captures are byte-identical; they have
`errorno=0`, but **no thread or posts**. Only one is represented as a fixture.
An empty PbPage must not be treated as a PbList or synthesized into a thread.

### Selection/privacy

Preserved: public thread/post IDs, titles, body text, rich-content fragments,
media URLs, reply mentions and mention UIDs, author IDs and selected public
display names, timestamps, counts, forum name/ID, page and relation fields.
No retained values are pseudonymized. The floor fixture additionally preserves
portrait tokens and the exact nested avatar URL, to verify that URLs are read
from the response rather than synthesized from tokens. The older list fixtures
still omit avatar/portrait fields. Authentication (`BDUSS`, passwords, etc.),
unrelated user profile information, anti/request data, advertising,
telemetry, client UI payloads and recommendations are removed by an explicit
recursive allowlist. Unknown `Post=40` and content `34/35` scalar fields are
retained where present to exercise unknown-field skipping. New raw captures
should be reviewed before extending the allowlist.

The list fixtures cover all 16 top-level posts and all 18 previews in each list source,
not a hand-picked single post. They retain 74 content segments per capture:
51 text (`type=0`), 7 images (`3`), 11 emoticons (`2`), 5 user mentions (`4`).
All 18 comments are previews, not evidence of a captured PbFloor pagination.

### Verified tags and UI anchors

Top-level: `error=1`, `data=2`.

PbList data: `thread=1`, `forum=2`, `user_list=5` (32 occurrences),
`first_floor=6` (1), `post_list=7` (15), `page=8`.
PbPage data uses a different layout: `post_list=6`, `thread=8`, `user_list=13`.
The empty real page response has none of these populated, so it cannot establish
how populated page records look. Existing constructed tests cover that layout.

`Post`: `id=1`, `title=2`, `floor=3`, `time=4`, repeated `content=5`,
`sub_post_number=13`, `sub_post_list=15`, `author_id=19`.
`SubPost`: `pid=1`, repeated `SubPostList=2`.
`SubPostList`: `id=1`, repeated `content=2`, `time=3`, `author_id=4`, `author=7`.
`ThreadInfo.id=1` is `11180147101`; the response URL contains no tid/pid.

- Thread: `tieba_thread_11180147101`, title **谁能告诉我外战看内啥来着？**,
  forum **抗压背锅**, thread reply count **88**.
- First post ID: `154122455593`, floor **1**, publish time `1794191960000` ms.
  Body starts `有没有那种淘汰了自家人然后吹自己外战的宝藏队伍推荐一下`,
  followed by an actual image; the original image URL is preferred.
- Direct floor: `tieba_post_154122637221`, parent `tieba_thread_11180147101`,
  reply count **11**, body starts `别的都是粉丝带节奏，al经理自己说是真的闻所未闻`,
  ending with the **黑线** emoticon.
- Preview: `tieba_post_154123008959`, parent `tieba_post_154122637221`,
  `reply_to_name=再见我的劫℃`, `reply_to_id=1022753941`.
  Body contains `别人的外战：T1 hle dk geng 我的外战：blg`.
- Image-only preview: `tieba_post_154122699285`, same parent; retains its image.

**All 15 direct replies have wire `floor=0`.** They remain `recordType=floor`
with an unknown numeric floor; neither their ranked list order nor their post
IDs justify inventing 2楼/3楼/etc. All 18 previews inherit their parent floor's
unknown number, while their parent IDs remain exact. Five previews have explicit
reply mentions. Their prefix is `" 回复 "` (leading whitespace), so reply target
extraction must trim leading whitespace without losing the body or mention UID.
There are no video or link content types in these two list captures; those still
have constructed-protobuf coverage rather than new real-binary evidence.

## Real complete PbFloor capture

Source `1794417546508_19771_127522652130362.bin` is a successful `/c/f/pb/floor`
response. The original 41,963 bytes are directly parsed in the local source audit,
not only through the 4,352-byte projected fixture. Its floor layout is confirmed:
`page=1`, `post=3`, repeated `subpost_list=4` (11 occurrences), `thread=5`,
`forum=6`, `subpost_num=13` (11). `page.current_page=1`, `total_page=1`,
`total_count=11`, `page_size=30`. This is all 11 replies reported by this
response, not a claim that future captures can never paginate.

- Parent: `tieba_post_154122637221`, **floor 5**, still `recordType=floor`,
  parent `tieba_thread_11180147101`. No separate thread record is fabricated.
- Parent `Post.sub_post_number=0` is misleading in this endpoint; actual
  `DataRes.subpost_num=11` and `Page.total_count=11` correctly supply reply count.
- The parent contains no nested preview list. All 11 top-level subposts are
  complete `comment` records, `is_preview=false`, attached to that exact parent.
  Their own `SubPostList.floor` is zero; metadata inherits parent floor **5**.
- Seven comments have explicit reply-to mentions, one is image-only, and the
  parent's **黑线** plus the third comment's **笑眼** emoticons are preserved.
- Four comments overlap the earlier list previews. Merging preserves parent IDs,
  upgrades `floor=0` to **5** and changes `is_preview=true` to **false**. The seven
  additional comments bring the combined list+floor unique record count to **41**.

### Avatars: present in all 12 records

The raw binary provides a portrait token in `User.portrait=5` and an actual URL
at `User.155 -> 1 -> 1 -> 1`. The latter matches the decoded JSON names
`user_show_info.feed_head.image_data.img_url`; the parser now decodes that exact
minimal path. No navigation URLs or unrelated head-card data are needed.

Parent's supplied URL (unchanged, including HTTP scheme and query):

```text
http://tb.himg.baidu.com/sys/portrait/item/tb.1.f40997ca.Th6Curf_v1BnGYwPj9cp6g?t=1794417546
```

All 12 records have an observed URL (11 unique authors); none requires portrait
URL synthesis. A derived missing-avatar test removes the observed URL message,
retains the portrait token, and verifies `authorAvatar` remains empty.

### Time: present in all 12 records, no zero values

The parent has `Post.time=4` = `1794195035` seconds. Each of the 11 replies has
`SubPostList.time=3` with a positive timestamp. `ThreadInfo.create_time` is
**absent** here; it must not be used to invent child or parent times. Tests
verify all 12 exact millisecond conversions and also a derived missing-time
case: deleting timestamps leaves `publishTime=0`, never capture/parent time.

| Complete comment post ID | Wire time (seconds) |
| ------------------------ | ------------------: |
| 154123008959             |          1794202300 |
| 154122699285             |          1794196155 |
| 154123440978             |          1794210883 |
| 154123055637             |          1794203278 |
| 154122732956             |          1794196778 |
| 154123919962             |          1794220089 |
| 154125527322             |          1794266506 |
| 154131300618             |          1794403675 |
| 154131172709             |          1794401194 |
| 154124344418             |          1794228270 |
| 154123927328             |          1794220243 |

## Earlier decoded JSON fixture

`floor-decoded.json` is an exact field projection of the `response` object in
`research/tieba/1794413979309629000.json`. Its source really contains **one
parent post at floor 5 and one subpost**, not a complete thread with only the
fifth floor retained. Every selected scalar value is unchanged and neither
array is truncated; a local source-audit test recursively verifies this.

Thread: `11177080968`, title `反T1装甲就剩俩了`, forum `抗压背锅`.
Parent: `154118752470`, content `身子和腿还在`, record type `floor`.
Child: `154131512696`, content `器官活化，互相掐架`, record type `comment`.

At the time of this audit, the 17 earlier decoded captures comprise three
PbFloor captures of this same parent/child and fourteen PbPage responses with
no populated thread/post list. They provide no additional real page-body or
floor-pagination evidence. The separate new raw PbFloor fixture above now supplies
complete-response evidence for a different parent/thread.
