package com.melonhu.indexrecord.hook;

import static com.melonhu.indexrecord.hook.BilibiliValues.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class BilibiliComments {

    private BilibiliComments() {}

    static JSONObject project(
        Object response,
        boolean detail
    ) throws JSONException {
        List<Object> items = new ArrayList<>();
        if (detail) {
            Object root = nondefault(response, "getRoot");
            if (root != null) items.add(root);
        } else {
            items.addAll(list(response, "getReplies"));
            items.addAll(list(response, "getTopReplies"));
            for (String getter : new String[] {
                "getUpTop",
                "getAdminTop",
                "getVoteTop",
            }) {
                Object top = nondefault(response, getter);
                if (top != null) items.add(top);
            }
        }
        for (Object item : items) {
            long oid = id(item, "getOid");
            long type = id(item, "getType");
            if (oid <= 0 || type <= 0 || id(item, "getId") <= 0) continue;
            JSONArray rows = new JSONArray();
            Set<Long> ids = new HashSet<>();
            for (Object reply : items) append(rows, ids, reply, oid, type, 0);
            return new JSONObject()
                .put("kind", "comments")
                .put("topic_id", oid)
                .put("topic_type", type)
                .put("replies", rows);
        }
        return null; // An empty page cannot establish its topic from response rows.
    }

    private static void append(
        JSONArray rows,
        Set<Long> ids,
        Object reply,
        long oid,
        long type,
        int depth
    ) throws JSONException {
        if (
            depth >= 16 ||
            id(reply, "getOid") != oid ||
            id(reply, "getType") != type
        ) return;
        long replyId = id(reply, "getId");
        if (replyId <= 0 || !ids.add(replyId)) return;
        Object content = nondefault(reply, "getContent");
        Object member = nondefault(reply, "getMember");
        Object basic = nondefault(nondefault(reply, "getMemberV2"), "getBasic");
        String author = text(member, "getName");
        if (author == null) author = text(basic, "getName");
        String avatar = text(member, "getFace");
        if (avatar == null) avatar = text(basic, "getFace");
        JSONObject row = new JSONObject()
            .put("id", replyId)
            .put("parent", id(reply, "getParent"))
            .put("root", id(reply, "getRoot"))
            .put("text", text(content, "getMessage"))
            .put("author", author)
            .put("author_avatar", avatar)
            .put("published_at", number(reply, "getCtime"))
            .put("like", number(reply, "getLike"))
            .put("reply", number(reply, "getCount"));
        JSONArray images = new JSONArray();
        for (Object picture : list(content, "getPictures")) {
            String url = text(picture, "getImgSrc");
            if (url != null) images.put(url);
        }
        if (images.length() > 0) row.put("images", images);
        rows.put(row);
        for (Object child : list(reply, "getReplies"))
            append(rows, ids, child, oid, type, depth + 1);
    }
}
