package com.melonhu.indexrecord.hook;

import static com.melonhu.indexrecord.hook.BilibiliValues.get;
import static com.melonhu.indexrecord.hook.BilibiliValues.list;
import static com.melonhu.indexrecord.hook.BilibiliValues.nondefault;
import static com.melonhu.indexrecord.hook.BilibiliValues.number;
import static com.melonhu.indexrecord.hook.BilibiliValues.text;

import org.json.JSONException;
import org.json.JSONObject;

final class BilibiliVideo {

    private static final String VIEW = "com.bapis.bilibili.app.viewunite.v1.";
    private static final String COMMON =
        "com.bapis.bilibili.app.viewunite.common.";

    private BilibiliVideo() {}

    static JSONObject project(Object reply) throws JSONException {
        if (reply == null) return null;
        String name = reply.getClass().getName();
        boolean kotlin = name.equals(VIEW + "KViewReply");
        if (!kotlin && !name.equals(VIEW + "ViewReply")) return null;

        Object arc = nondefault(reply, "getArc");
        Number aid = number(arc, "getAid");
        if (aid == null || aid.longValue() <= 0) return null;

        Object owner = nondefault(reply, "getOwner");
        JSONObject video = new JSONObject()
            .put("kind", "video")
            .put("aid", aid.longValue())
            .put("bvid", text(arc, "getBvid"))
            .put("title", text(arc, "getTitle"))
            .put("cover", text(arc, "getCover"))
            .put("duration", number(arc, "getDuration"))
            .put("author", text(owner, "getTitle"))
            .put("author_avatar", text(owner, "getFace"));

        Object stat = nondefault(arc, "getStat");
        if (stat != null) {
            video.put("view", number(nondefault(stat, "getVt"), "getValue"));
            video.put(
                "danmaku",
                number(nondefault(stat, "getDanmaku"), "getValue")
            );
            video.put("like", number(stat, "getLike"));
            video.put("reply", number(stat, "getReply"));
            video.put("coin", number(stat, "getCoin"));
            video.put("favorite", number(stat, "getFav"));
            video.put("share", number(stat, "getShare"));
        }

        Object introduction = ugcIntroduction(
            nondefault(reply, "getTab"),
            kotlin
        );
        if (introduction != null) {
            video.put("published_at", number(introduction, "getPubdate"));
            StringBuilder description = new StringBuilder();
            for (Object segment : list(introduction, "getDesc")) {
                String part = text(segment, "getText");
                if (part != null) description.append(part);
            }
            video.put("description", description.toString());
        }
        return video;
    }

    private static Object ugcIntroduction(Object tab, boolean kotlin) {
        for (Object tabModule : list(tab, "getTabModule")) {
            Object introduction = kotlin
                ? get(get(tabModule, "getTab"), "getValue")
                : nondefault(tabModule, "getIntroduction");
            if (
                introduction == null ||
                (kotlin &&
                    !introduction
                        .getClass()
                        .getName()
                        .equals(VIEW + "KIntroductionTab"))
            ) continue;

            for (Object module : list(introduction, "getModules")) {
                Object ugc = kotlin
                    ? get(get(module, "getData"), "getValue")
                    : nondefault(module, "getUgcIntroduction");
                if (
                    ugc != null &&
                    (!kotlin ||
                        ugc
                            .getClass()
                            .getName()
                            .equals(COMMON + "KUgcIntroduction"))
                ) return ugc;
            }
        }
        return null;
    }
}
