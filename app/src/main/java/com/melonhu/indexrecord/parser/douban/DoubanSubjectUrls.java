package com.melonhu.indexrecord.parser.douban;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared by the host hook and parser; must not depend on Android or app storage. */
public final class DoubanSubjectUrls {
    private static final String BASE = "^https?://(?:[^/]+\\.)?douban\\.com/api/v2/";
    private static final String SUBJECT = "(movie|book|music|game|tv|drama|subject)/(\\d+)";
    private static final Pattern DETAIL = Pattern.compile(BASE + SUBJECT + "/?$");
    private static final Pattern FORUM = Pattern.compile(BASE + "subject/\\d+/forum_topic/forum/?$");
    private static final Pattern INTERESTS = Pattern.compile(BASE + SUBJECT + "/(?:hot_interests|interests)/?$");

    private DoubanSubjectUrls() {}

    private static String clean(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    public static boolean isDetail(String url) {
        return url != null && DETAIL.matcher(clean(url)).matches();
    }

    public static boolean isForum(String url) {
        return url != null && FORUM.matcher(clean(url)).matches();
    }

    public static boolean shouldCapture(String url) {
        return isDetail(url) || isForum(url)
            || (url != null && INTERESTS.matcher(clean(url)).matches());
    }

    public static String subjectType(String url) {
        if (url == null) return "";
        Matcher matcher = DETAIL.matcher(clean(url));
        return matcher.matches() ? matcher.group(1) : "";
    }
}
