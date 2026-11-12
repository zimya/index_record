# Threads parser fixtures

Reduced from local `research/threads/*-body_gzip.clean.json` captures. Original dumps must remain untracked.

- `35a98fbc.json`: one main post, caption, author, counts, text fragments.
- `402a47a0.json`: four thread containers, each holding a first-level reply and its second-level reply (eight posts total). Numeric direct-parent IDs retain their JSON number representation and 19-digit precision.
- `6b83e418.json`: two main repost objects. Their `share_info.reposted_post` attachments include a three-item carousel (two videos and one image), and a text post. Attachments are rendered inline, not inserted as separate records.
- `notifications.json`: reduced `new_stories/args/extra/media_dict` notification shape containing a real post-shaped attachment, which must not be recorded.
- `users.json`, `recommendations.json`: reduced profile and account recommendation response shapes, which must not be recorded.
- `replies_streaming_initial.ndjson`: four chunks reduced from `1794502041440_47209997275061.bin` (`/api/v1/text_feed/<id>/replies_streaming/`), including empty/heartbeat chunks, repeated `containing_thread`, and four `reply_threads`. Five distinct main post objects: one root with six carousel images and four replies (one with two carousel images). Real root counters include repost=2, reshare=4, quote=0.
- `replies_streaming_page.ndjson`: two chunks reduced from `1794502042068_47210625315113.bin`, with two then three additional reply threads, no containing post. IDs are consistently remapped across these two streams. Each page reply directly references the initial stream root.
- `timeline_1.ndjson`, `timeline_2.ndjson`, `timeline_3.ndjson`: 10/6/5 chunks reduced from `1794502305503_47474060262096.bin`, `1794502308255_47476812342563.bin`, `1794502316073_47484630963342.bin` (`/api/v1/feed/text_post_app_timeline/`). Actual post path: `feed_items/text_post_app_thread/thread_items/post`. Each stream has six post wrappers, including one ad identified by `post.injected.ad_id`; some ads also have `ad_action` (including carousel items). The second stream also has one `feed_items/suggested_users` unit with `suggestion_cards/user_card/user` and empty `media_infos`. Its 20 cards are reduced to one. Each stream yields five organic records; all three yield 13 unique records because of cross-page overlap. Timeline IDs are remapped consistently within this three-stream group, preserving overlaps. All three lack `view_count`.

IDs are consistently remapped across fixtures; authors, captions, timestamps, codes and every media/avatar/permalink URL are replaced. Tracking tokens, private profile fields, original text, and original CDN query strings are removed. Only structural and parser-relevant fields remain.

The streaming captures contain empty `sibling_threads` and `related_threads` arrays; nonempty sibling support and related recommendation exclusion are exercised synthetically. Neither streaming capture contains `view_count`; numeric-string counts at post/info level and null/invalid fallback are synthetic tests, not observed values. Malformed lines and separator-free/pretty-printed consecutive objects are also synthetic framing variants.

Timeline `injected.ad_id`, `ad_action`, and the suggested-users unit are observed fields/shapes. Synthetic tests move the injected marker to ancestor containers, populate recommendation prefetch media, or combine conflicting wrappers to enforce exclusion boundaries.

Other ad flags, nonempty medias/media wrappers, GraphQL data wrappers, sparse patches, quote variants, and additional counter variants in Kotlin tests are explicitly synthetic boundary cases, not claims that those variants were observed in these captures. The captured `medias` arrays are empty. Arbitrary GraphQL edges, prefetch containers, rich fragment variants, and other unobserved attachment schemas are intentionally not generalized.
