package me.melon.record.parser.reddit;

import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Shared by the injected Java hook and the recorder. Never traverses arbitrary roots as records. */
public final class RedditCaptureFilter {

    private RedditCaptureFilter() {}

    private static final String[] POST_FIELDS = {
        "__typename",
        "id",
        "postTitle",
        "title",
        "createdAt",
        "editedAt",
        "permalink",
        "url",
        "score",
        "isScoreHidden",
        "commentCount",
        "crosspostCount",
        "postStats",
        "postStatsPrivate",
        "flair",
        "authorFlair",
        "authorCommunityBadge",
        "awardings",
        "isNsfw",
        "isSpoiler",
        "isArchived",
        "isLocked",
        "isSelfPost",
        "isStickied",
        "isRemoved",
        "removedByCategory",
        "distinguishedAs",
        "media",
        "outboundLink",
        "poll",
    };
    private static final String[] COMMENT_FIELDS = {
        "__typename",
        "id",
        "postId",
        "parentId",
        "createdAt",
        "editedAt",
        "permalink",
        "score",
        "isScoreHidden",
        "commentCount",
        "replyCount",
        "commentStats",
        "authorFlair",
        "authorCommunityBadge",
        "awardings",
        "isArchived",
        "isLocked",
        "isStickied",
        "isRemoved",
        "isAdminTakedown",
        "isOP",
        "removedByCategory",
        "distinguishedAs",
        "tombstone",
    };

    /** Ads are identified by their payload/context, never by a ProfilePost or brand author alone. */
    public static boolean isAd(JSONObject object) {
        String type = object.optString("__typename", "");
        if (
            type.equals("AdPost") ||
            type.equals("AdPayload") ||
            type.equals("AdMetadataCell")
        ) return true;
        for (String key : new String[] {
            "isPromoted",
            "promoted",
            "isCreatedFromAdsUi",
            "isSponsored",
        }) {
            if (Boolean.TRUE.equals(object.opt(key))) return true;
        }
        for (String key : new String[] {
            "adPayload",
            "adUrl",
            "adLinkUrl",
            "adInstanceId",
            "adsCorrelationId",
            "adEvents",
            "callToAction",
            "promotedCommunityPost",
        }) {
            if (present(object.opt(key))) return true;
        }
        // isCommercialCommunication can also label an ordinary disclosed affiliate post.
        return false;
    }

    public static String fullname(String value, String prefix) {
        if (value == null) return "";
        String id = value.trim().toLowerCase(Locale.ROOT);
        String platformPrefix = prefix.equals("t3_")
            ? "reddit_post_"
            : "reddit_comment_";
        if (id.startsWith(platformPrefix)) id = id.substring(
            platformPrefix.length()
        );
        else if (id.startsWith(prefix)) id = id.substring(prefix.length());
        if (id.isEmpty()) return "";
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'z')) return "";
        }
        return prefix + id;
    }

    public static void collectAdvertisingIds(Object value, Set<String> ids) {
        collectAdvertisingIds(value, ids, false, 0);
    }

    private static void collectAdvertisingIds(
        Object value,
        Set<String> ids,
        boolean inAd,
        int depth
    ) {
        if (depth > 40) return;
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) collectAdvertisingIds(
                array.opt(i),
                ids,
                inAd,
                depth + 1
            );
        } else if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            boolean advertising = inAd || isAd(object);
            if (advertising) {
                for (String key : new String[] { "id", "postId", "groupId" }) {
                    String raw = object.optString(key, "");
                    if (
                        raw.startsWith("t3_") || raw.startsWith("reddit_post_")
                    ) {
                        String id = fullname(raw, "t3_");
                        if (!id.isEmpty()) ids.add(id);
                    }
                }
                String instance = object
                    .optString("adInstanceId", "")
                    .split("-", 2)[0];
                if (instance.startsWith("t3_")) {
                    String id = fullname(instance, "t3_");
                    if (!id.isEmpty()) ids.add(id);
                }
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                boolean childAd =
                    advertising ||
                    key.equals("pdpCommentsAds") ||
                    key.equals("adPosts") ||
                    key.equals("adPost") ||
                    key.equals("adPayload");
                collectAdvertisingIds(object.opt(key), ids, childAd, depth + 1);
            }
        }
    }

    /** Keeps batch request/response indices paired while removing irrelevant operations completely. */
    public static JSONObject envelope(
        Object response,
        Object request,
        Set<String> advertisingIds
    ) throws JSONException {
        collectAdvertisingIds(response, advertisingIds);
        if (response instanceof JSONArray) {
            JSONArray source = (JSONArray) response;
            JSONArray responses = new JSONArray();
            JSONArray requests = new JSONArray();
            for (int i = 0; i < source.length(); i++) {
                JSONObject projected = projectResponse(
                    source.optJSONObject(i),
                    advertisingIds
                );
                if (projected == null) continue;
                responses.put(projected);
                requests.put(
                    request instanceof JSONArray
                        ? ((JSONArray) request).opt(i)
                        : JSONObject.NULL
                );
            }
            if (responses.length() == 0) return null;
            return new JSONObject()
                .put("request", requests)
                .put("response", responses)
                .put("captureVersion", 1);
        }
        JSONObject projected = projectResponse(
            response instanceof JSONObject ? (JSONObject) response : null,
            advertisingIds
        );
        return projected == null
            ? null
            : new JSONObject()
                  .put("request", request == null ? JSONObject.NULL : request)
                  .put("response", projected)
                  .put("captureVersion", 1);
    }

    private static JSONObject projectResponse(
        JSONObject response,
        Set<String> ads
    ) throws JSONException {
        if (response == null) return null;
        JSONObject data = response.optJSONObject("data");
        if (data == null) return null;
        JSONObject result = new JSONObject();
        Iterator<String> roots = data.keys();
        while (roots.hasNext()) {
            String key = roots.next();
            Object value = data.opt(key);
            Object projected = null;
            if (key.equals("homeV3")) projected = projectFeed(
                data.optJSONObject(key),
                ads
            );
            else if (key.equals("postInfoById")) projected = projectPost(
                data.optJSONObject(key),
                ads
            );
            else if (key.equals("postsInfoByIds")) projected = projectRecords(
                data.optJSONArray(key),
                ads,
                true
            );
            else if (key.equals("commentInfoById")) projected = projectComment(
                data.optJSONObject(key),
                ads
            );
            else if (key.equals("commentsInfoByIds")) projected =
                projectRecords(data.optJSONArray(key), ads, false);
            else if (value instanceof JSONObject) {
                JSONObject object = (JSONObject) value;
                String type = object.optString("__typename", "");
                if (
                    type.equals("SubredditPost") || type.equals("ProfilePost")
                ) projected = projectPost(object, ads);
                else if (type.equals("Comment")) projected = projectComment(
                    object,
                    ads
                );
                else if (type.equals("CommentForest")) projected =
                    projectForest(object, ads);
            } else if (value instanceof JSONArray) {
                // An alias may name an explicit list of posts/comments, but not a config/user list.
                projected = projectRecords((JSONArray) value, ads, null);
            }
            if (projected != null) result.put(key, projected);
        }
        return result.length() == 0
            ? null
            : new JSONObject().put("data", result);
    }

    private static JSONArray projectRecords(
        JSONArray source,
        Set<String> ads,
        Boolean posts
    ) throws JSONException {
        if (source == null) return null;
        JSONArray result = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject object = source.optJSONObject(i);
            if (object == null) continue;
            String type = object.optString("__typename", "");
            JSONObject projected = null;
            if (
                Boolean.TRUE.equals(posts) ||
                (posts == null &&
                    (type.equals("SubredditPost") ||
                        type.equals("ProfilePost")))
            ) {
                projected = projectPost(object, ads);
            } else if (
                Boolean.FALSE.equals(posts) ||
                (posts == null && type.equals("Comment"))
            ) {
                projected = projectComment(object, ads);
            }
            if (projected != null) result.put(projected);
        }
        return result.length() == 0 ? null : result;
    }

    private static JSONObject projectPost(JSONObject source, Set<String> ads)
        throws JSONException {
        return projectPost(source, ads, 0);
    }

    private static JSONObject projectPost(
        JSONObject source,
        Set<String> ads,
        int depth
    ) throws JSONException {
        if (depth > 8) return null;
        if (source == null || isAd(source)) return null;
        String id = fullname(source.optString("id", ""), "t3_");
        String type = source.optString("__typename", "");
        if (
            id.isEmpty() ||
            ads.contains(id) ||
            !(
                type.isEmpty() ||
                type.equals("SubredditPost") ||
                type.equals("ProfilePost")
            )
        ) return null;
        // ID/title-only profile detail requests cannot distinguish a never-seen ad from an organic post.
        // Full organic ProfilePosts remain supported; do not classify the entire type as advertising.
        if (
            type.equals("ProfilePost") &&
            !hasAny(
                source,
                "authorInfo",
                "createdAt",
                "permalink",
                "content",
                "media",
                "gallery",
                "score",
                "commentCount",
                "commentForest"
            )
        ) return null;
        JSONObject result = copy(source, POST_FIELDS);
        add(result, "content", projectContent(source.optJSONObject("content")));
        add(
            result,
            "authorInfo",
            projectAuthor(source.optJSONObject("authorInfo"))
        );
        add(
            result,
            "subreddit",
            copyNullable(
                source.optJSONObject("subreddit"),
                "__typename",
                "id",
                "name",
                "prefixedName",
                "title"
            )
        );
        add(
            result,
            "profile",
            copyNullable(
                source.optJSONObject("profile"),
                "__typename",
                "id",
                "name"
            )
        );
        add(result, "gallery", projectGallery(source.optJSONObject("gallery")));
        add(
            result,
            "commentForest",
            projectForest(source.optJSONObject("commentForest"), ads)
        );
        add(
            result,
            "crosspostRoot",
            projectPost(source.optJSONObject("crosspostRoot"), ads, depth + 1)
        );
        Iterator<String> keys = result.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (
                !key.equals("id") &&
                !key.equals("__typename") &&
                result.opt(key) != JSONObject.NULL
            ) return result;
        }
        return null;
    }

    private static JSONObject projectComment(JSONObject source, Set<String> ads)
        throws JSONException {
        if (
            source == null ||
            isAd(source) ||
            fullname(source.optString("id", ""), "t1_").isEmpty()
        ) return null;
        String type = source.optString("__typename", "");
        if (!(type.isEmpty() || type.equals("Comment"))) return null;
        String postId = fullname(source.optString("postId", ""), "t3_");
        if (!postId.isEmpty() && ads.contains(postId)) return null;
        JSONObject result = copy(source, COMMENT_FIELDS);
        add(result, "content", projectContent(source.optJSONObject("content")));
        add(
            result,
            "authorInfo",
            projectAuthor(source.optJSONObject("authorInfo"))
        );
        return result;
    }

    private static JSONObject projectForest(JSONObject source, Set<String> ads)
        throws JSONException {
        if (source == null) return null;
        if (
            ads.contains(fullname(source.optString("postId", ""), "t3_"))
        ) return null;
        JSONObject result = copy(
            source,
            "__typename",
            "postId",
            "appliedSort",
            "pageInfo"
        );
        JSONArray trees = source.optJSONArray("trees");
        JSONArray projected = new JSONArray();
        if (trees != null) for (int i = 0; i < trees.length(); i++) {
            JSONObject tree = trees.optJSONObject(i);
            if (tree == null || isAd(tree)) continue;
            JSONObject node = projectComment(tree.optJSONObject("node"), ads);
            if (node != null || tree.optJSONObject("more") != null) {
                JSONObject item = copy(
                    tree,
                    "parentId",
                    "depth",
                    "childCount",
                    "more"
                );
                item.put("node", node == null ? JSONObject.NULL : node);
                projected.put(item);
            }
        }
        // An explicit empty tree still identifies a zero-comment detail after repeated projection.
        if (
            trees == null && projected.length() == 0 && result.length() == 0
        ) return null;
        return result.put("trees", projected);
    }

    private static JSONObject projectFeed(JSONObject source, Set<String> ads)
        throws JSONException {
        if (source == null) return null;
        JSONObject elements = source.optJSONObject("elements");
        JSONArray edges =
            elements == null ? null : elements.optJSONArray("edges");
        if (edges == null) return null;
        JSONArray result = new JSONArray();
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            JSONObject node = edge == null ? null : edge.optJSONObject("node");
            if (node == null || isAd(node)) continue;
            String id = fullname(node.optString("groupId", ""), "t3_");
            if (id.isEmpty() || ads.contains(id)) continue;
            JSONArray cells = node.optJSONArray("cells");
            JSONArray projected = new JSONArray();
            if (cells != null) for (int j = 0; j < cells.length(); j++) {
                JSONObject cell = cells.optJSONObject(j);
                if (cell == null || isAd(cell)) continue;
                String type = cell.optString("__typename", "");
                JSONObject kept = null;
                switch (type) {
                    case "TitleCell":
                        kept = copy(cell, "__typename", "title");
                        break;
                    case "PreviewTextCell":
                        kept = copy(cell, "__typename", "text");
                        break;
                    case "MetadataCell":
                        kept = copy(
                            cell,
                            "__typename",
                            "authorName",
                            "createdAt",
                            "detailsString",
                            "detailsLink",
                            "joinSubredditId",
                            "statusIndicators"
                        );
                        break;
                    case "ActionCell":
                        kept = copy(
                            cell,
                            "__typename",
                            "score",
                            "isScoreHidden",
                            "commentCount",
                            "shareCount",
                            "crosspostCount"
                        );
                        break;
                    case "ImageCell":
                    case "VideoCell":
                        kept = copy(cell, "__typename", "media");
                        break;
                    case "GalleryCell":
                        kept = copy(cell, "__typename", "pages");
                        break;
                    case "FlairCell":
                        kept = copy(cell, "__typename", "text");
                        break;
                    default:
                        break;
                }
                if (kept != null) projected.put(kept);
            }
            if (projected.length() > 0) result.put(
                new JSONObject().put(
                    "node",
                    new JSONObject()
                        .put("__typename", "CellGroup")
                        .put("groupId", id)
                        .put("cells", projected)
                )
            );
        }
        return result.length() == 0
            ? null
            : new JSONObject().put(
                  "elements",
                  new JSONObject().put("edges", result)
              );
    }

    private static JSONObject projectContent(JSONObject source)
        throws JSONException {
        return copyNullable(
            source,
            "markdown",
            "html",
            "preview",
            "richtext",
            "richtextMedia",
            "typeHint"
        );
    }

    private static JSONObject projectAuthor(JSONObject source)
        throws JSONException {
        if (source == null) return null;
        JSONObject result = copy(
            source,
            "__typename",
            "id",
            "name",
            "iconSmall",
            "snoovatarIcon"
        );
        JSONObject attributes = source.optJSONObject("attributes");
        if (attributes != null) add(
            result,
            "attributes",
            copy(attributes, "redditHandleInfo", "verificationStatus")
        );
        return result;
    }

    private static JSONObject projectGallery(JSONObject source)
        throws JSONException {
        if (source == null) return null;
        JSONArray items = source.optJSONArray("items");
        JSONArray result = new JSONArray();
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && !isAd(item)) result.put(
                copy(
                    item,
                    "__typename",
                    "id",
                    "caption",
                    "media",
                    "outboundUrl"
                )
            );
        }
        return result.length() == 0
            ? null
            : new JSONObject().put("items", result);
    }

    private static boolean hasAny(JSONObject object, String... keys) {
        for (String key : keys) {
            Object value = object.opt(key);
            if (
                value instanceof Number ||
                Boolean.TRUE.equals(value) ||
                present(value)
            ) return true;
        }
        return false;
    }

    private static boolean present(Object value) {
        if (
            value == null ||
            value == JSONObject.NULL ||
            Boolean.FALSE.equals(value)
        ) return false;
        if (value instanceof String) return !((String) value).trim().isEmpty();
        if (value instanceof JSONArray) return ((JSONArray) value).length() > 0;
        if (value instanceof JSONObject) return (
            ((JSONObject) value).length() > 0
        );
        return (
            !(value instanceof Number) || ((Number) value).doubleValue() != 0
        );
    }

    private static JSONObject copy(JSONObject source, String... keys)
        throws JSONException {
        JSONObject result = new JSONObject();
        for (String key : keys)
            if (source.has(key)) result.put(key, source.opt(key));
        return result;
    }

    private static JSONObject copyNullable(JSONObject source, String... keys)
        throws JSONException {
        return source == null ? null : copy(source, keys);
    }

    private static void add(JSONObject object, String key, Object value)
        throws JSONException {
        if (value != null) object.put(key, value);
    }
}
