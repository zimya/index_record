package com.melonhu.indexrecord.hook;

import static com.melonhu.indexrecord.hook.BilibiliValues.get;
import static com.melonhu.indexrecord.hook.BilibiliValues.list;
import static com.melonhu.indexrecord.hook.BilibiliValues.nondefault;
import static com.melonhu.indexrecord.hook.BilibiliValues.number;
import static com.melonhu.indexrecord.hook.BilibiliValues.text;

import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class BilibiliOpus {

    private BilibiliOpus() {}

    static JSONObject project(Object reply) throws JSONException {
        Object item = nondefault(reply, "getOpusItem");
        Object extend = nondefault(item, "getExtend");
        Object topic = nondefault(extend, "getReply");
        Number id = number(topic, "getReplyBizId");
        Number type = number(topic, "getReplyBizType");
        // Type 1 belongs to videos; neither the opus ID nor its oid is a topic fallback.
        if (
            id == null ||
            id.longValue() <= 0 ||
            type == null ||
            type.longValue() <= 1
        ) return null;

        JSONObject opus = new JSONObject()
            .put("kind", "opus")
            .put("topic_id", id.longValue())
            .put("topic_type", type.longValue());
        Number opusId = number(item, "getOpusId");
        if (opusId != null && opusId.longValue() > 0) opus.put(
            "opus_id",
            opusId.longValue()
        );

        String author = null;
        String avatar = null;
        String title = null;
        String articleTitle = null;
        String description = null;
        Object summary = null;
        StringBuilder body = new StringBuilder();
        Set<String> images = new LinkedHashSet<>();
        for (Object module : list(item, "getModules")) {
            Object value = get(get(module, "getModuleItem"), "getValue");
            Number kind =
                value == null
                    ? null
                    : number(get(module, "getModuleType"), "getValue");
            // Kotlin's DynModuleType wire values identify the payload, not its obfuscated name.
            if (author == null || avatar == null) {
                Object byline = part(module, value, kind, 1, "getModuleAuthor");
                Object user = nondefault(byline, "getAuthor");
                author = first(author, text(user, "getName"));
                avatar = first(avatar, text(user, "getFace"));
            }
            if (description == null) {
                Object desc = part(module, value, kind, 3, "getModuleDesc");
                description = first(
                    text(desc, "getText"),
                    segments(desc, "getDesc", "getText")
                );
            }
            Object stat = part(module, value, kind, 9, "getModuleStat");
            if (stat != null) {
                putCount(opus, "like", number(stat, "getLike"));
                putCount(opus, "reply", number(stat, "getReply"));
            }
            if (title == null) title = text(
                part(module, value, kind, 27, "getModuleTitle"),
                "getTitle"
            );
            if (summary == null) summary = part(
                module,
                value,
                kind,
                30,
                "getModuleOpusSummary"
            );

            Object block = part(module, value, kind, 32, "getModuleParagraph");
            Object paragraph = nondefault(block, "getParagraph");
            if (paragraph == null) continue;
            Object content = get(get(paragraph, "getContent"), "getValue");
            Object pic =
                content != null ? content : nondefault(paragraph, "getPic");
            addImages(images, nondefault(pic, "getPics"), "getItems");
            String line = nodeText(
                content != null ? content : nondefault(paragraph, "getText")
            );
            if (line == null) continue;
            if (Boolean.TRUE.equals(get(block, "getIsArticleTitle"))) {
                articleTitle = first(articleTitle, line);
            } else {
                if (body.length() > 0) body.append("\n\n");
                body.append(line);
            }
        }

        if (summary == null) summary = nondefault(extend, "getOpusSummary");
        if (body.length() == 0 && description == null) {
            description = first(
                nodeText(nondefault(extend, "getDescTextOpus")),
                segments(extend, "getDesc", "getText")
            );
            if (description == null) description = paragraphText(
                nondefault(summary, "getSummary")
            );
        }
        if (images.isEmpty()) addImages(images, summary, "getCovers");
        if (!images.isEmpty()) opus.put("images", new JSONArray(images));
        return opus
            .put(
                "title",
                first(
                    articleTitle,
                    first(title, paragraphText(nondefault(summary, "getTitle")))
                )
            )
            .put("text", first(body.toString(), description))
            .put("author", first(author, text(extend, "getUpName")))
            .put("author_avatar", first(avatar, text(extend, "getUpFace")));
    }

    private static Object part(
        Object module,
        Object value,
        Number kind,
        int expected,
        String getter
    ) {
        if (value == null) return nondefault(module, getter);
        return kind != null && kind.intValue() == expected ? value : null;
    }

    private static String paragraphText(Object paragraph) {
        Object content = get(get(paragraph, "getContent"), "getValue");
        return nodeText(
            content != null ? content : nondefault(paragraph, "getText")
        );
    }

    private static String nodeText(Object paragraph) {
        StringBuilder joined = new StringBuilder();
        for (Object node : list(paragraph, "getNodes")) {
            String raw = text(node, "getRawText");
            if (raw == null || raw.isEmpty()) {
                Object word = nondefault(node, "getWord");
                if (word == null) word = get(get(node, "getText"), "getValue");
                raw = text(word, "getWords");
            }
            if (raw != null) joined.append(raw);
        }
        return first(joined.toString(), null);
    }

    private static String segments(Object owner, String items, String getter) {
        StringBuilder joined = new StringBuilder();
        for (Object row : list(owner, items)) {
            String part = text(row, getter);
            if (part != null) joined.append(part);
        }
        return first(joined.toString(), null);
    }

    private static void addImages(
        Set<String> images,
        Object owner,
        String getter
    ) {
        for (Object image : list(owner, getter)) {
            String url = text(image, "getSrc");
            if (url != null && !url.isEmpty()) images.add(url);
        }
    }

    private static String first(String value, String fallback) {
        return value != null && !value.isEmpty() ? value : fallback;
    }

    private static void putCount(
        JSONObject opus,
        String key,
        Number count
    ) throws JSONException {
        if (!opus.has(key) && count != null && count.longValue() >= 0) opus.put(
            key,
            count.longValue()
        );
    }
}
