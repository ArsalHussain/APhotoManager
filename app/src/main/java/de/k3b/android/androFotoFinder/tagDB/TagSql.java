/*
 * Copyright (c) 2016-2017 by k3b.
 *
 * This file is part of AndroFotoFinder / #APhotoManager.
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>
 */

package de.k3b.android.androFotoFinder.tagDB;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.provider.MediaStore;
import android.util.Log;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import de.k3b.android.androFotoFinder.Global;
import de.k3b.android.androFotoFinder.queries.FotoSql;
import de.k3b.database.SelectedFiles;
import de.k3b.database.SelectedItems;
import de.k3b.io.GalleryFilterParameter;
import de.k3b.io.GeoRectangle;
import de.k3b.io.IGalleryFilter;
import de.k3b.database.QueryParameter;
import de.k3b.tagDB.TagConverter;

/**
 * Database related code to handle non standard image processing (Tags, Description)
 *
 * Created by k3b on 30.09.2016.
 */
public class TagSql extends FotoSql {


    public static final String SQL_COL_EXT_TAGS = MediaStore.Video.Media.TAGS;

    public static final String SQL_COL_EXT_DESCRIPTION = MediaStore.Images.Media.DESCRIPTION;
    public static final String SQL_COL_EXT_TITLE = MediaStore.Images.Media.TITLE;
    public static final String SQL_COL_EXT_RATING = MediaStore.Video.Media.BOOKMARK;

    /** The date & time when last non standard media-scan took place
     *  <P>Type: INTEGER (long) as milliseconds since jan 1, 1970</P> */
    private static final String SQL_COL_EXT_XMP_LAST_MODIFIED_DATE = MediaStore.Video.Media.DURATION;

    public static final int EXT_LAST_EXT_SCAN_UNKNOWN = 0;
    public static final int EXT_LAST_EXT_SCAN_NO_XMP_IN_CSV = 5;
    public static final int EXT_LAST_EXT_SCAN_NO_XMP = 10;

    protected static final String FILTER_EXPR_PATH_LIKE_XMP_DATE = FILTER_EXPR_PATH_LIKE
            + " and ("
            + SQL_COL_EXT_XMP_LAST_MODIFIED_DATE + " is null or "
            + SQL_COL_EXT_XMP_LAST_MODIFIED_DATE + " < ?) ";

    protected static final String FILTER_EXPR_ANY_LIKE = "((" + SQL_COL_PATH + " like ?) OR  (" + SQL_COL_EXT_DESCRIPTION
            + " like ?) OR  (" + SQL_COL_EXT_TAGS + " like ?) OR  (" + SQL_COL_EXT_TITLE + " like ?))";

    protected static final String FILTER_EXPR_TAGS_NONE_OR_LIST = "((" + SQL_COL_EXT_TAGS
            + " is null) or (" + SQL_COL_EXT_TAGS +" like ?))";
    protected static final String FILTER_EXPR_TAGS_LIST         = "(" + SQL_COL_EXT_TAGS + " like ?)";
    protected static final String FILTER_EXPR_TAGS_NONE         = "(" + SQL_COL_EXT_TAGS + " is null)";
    protected static final String FILTER_EXPR_NO_TAG            = "(" + SQL_COL_EXT_TAGS + " not like ?)";

    /** translates a query back to filter */
    public static IGalleryFilter parseQueryEx(QueryParameter query, boolean remove) {
        if (query != null) {
            GalleryFilterParameter resultFilter = (GalleryFilterParameter) parseQuery(query,remove);
            parseTagsFromQuery(query, remove, resultFilter);

            String[] params;
            if ((params = getParams(query, FILTER_EXPR_ANY_LIKE, remove)) != null) {
                resultFilter.setInAnyField(params[0]);
            }

            if (getParams(query, FILTER_EXPR_PRIVATE_PUBLIC, remove) != null) {
                resultFilter.setVisibility(IGalleryFilter.VISIBILITY_PRIVATE_PUBLIC);
            }
            if (getParams(query, FILTER_EXPR_PRIVATE, remove) != null) {
                resultFilter.setVisibility(IGalleryFilter.VISIBILITY_PRIVATE);
            }
            if (getParams(query, FILTER_EXPR_PUBLIC, remove) != null) {
                resultFilter.setVisibility(IGalleryFilter.VISIBILITY_PUBLIC);
            }

            return resultFilter;
        }
        return null;
    }

    private static void parseTagsFromQuery(QueryParameter query, boolean remove, GalleryFilterParameter resultFilter) {
        // 0..n times for excluded expressions
        String param = getParam(query, FILTER_EXPR_NO_TAG, remove);
        if (param != null) {
            resultFilter.setTagsAllExcluded(null);
            List<String> excluded = resultFilter.getTagsAllExcluded();
            do {
                if ((param.startsWith("%;") && (param.endsWith(";%")))) param = param.substring(2, param.length() -2);
                excluded.add(param);
                param = getParam(query, FILTER_EXPR_NO_TAG, remove);
            } while (remove && (param != null));
        }

        // different possible combinations of "tags is null" and "tag list"
        String[] params;
        if (getParams(query, FILTER_EXPR_TAGS_NONE, remove) != null) {
            resultFilter.setWithNoTags(true).setTagsAllIncluded(null);
        }


        if ((param = getParam(query, FILTER_EXPR_TAGS_NONE_OR_LIST, remove)) != null) {
            resultFilter.setWithNoTags(true).setTagsAllIncluded(TagConverter.fromString(param));
        }

        if ((param = getParam(query, FILTER_EXPR_TAGS_LIST, remove)) != null) {
            resultFilter.setWithNoTags(false).setTagsAllIncluded(TagConverter.fromString(param));
        }
    }

    public static void filter2QueryEx(QueryParameter resultQuery, IGalleryFilter filter, boolean clearWhereBefore) {
        if ((resultQuery != null) && (!GalleryFilterParameter.isEmpty(filter))) {
            filter2Query(resultQuery, filter, clearWhereBefore);
            if (Global.Media.enableNonStandardMediaFields) {
                String any = filter.getInAnyField();
                if ((any != null) && (any.length() > 0)) {
                    if (!any.contains("%")) {
                        any = "%" + any + "%";
                    }
                    resultQuery.addWhere(FILTER_EXPR_ANY_LIKE, any, any, any, any);
                }

                List<String> includes = filter.getTagsAllIncluded();
                boolean withNoTags = filter.isWithNoTags();
                addWhereTagsIncluded(resultQuery, includes, withNoTags);
                List<String> excludes = filter.getTagsAllExcluded();
                if (excludes != null) {
                    for (String tag : excludes) {
                        if ((tag != null) && (tag.length() > 0)) {
                            resultQuery.addWhere(FILTER_EXPR_NO_TAG, "%;" + tag + ";%");
                        }
                    }
                }

                setWhereVisibility(resultQuery, filter.getVisibility());
            }
        }
    }

    public static void addWhereTagsIncluded(QueryParameter resultQuery, List<String> includes, boolean withNoTags) {
        if ((includes != null) && (includes.size() == 0)) includes = null;
        if (includes != null) {
            String includesWhere = TagConverter.asDbString("%", includes);
            if (includesWhere != null) {
                if (withNoTags) {
                    resultQuery.addWhere(FILTER_EXPR_TAGS_NONE_OR_LIST, includesWhere);
                } else {
                    resultQuery.addWhere(FILTER_EXPR_TAGS_LIST, includesWhere);
                }
            }
        } else if (withNoTags) {
            resultQuery.addWhere(FILTER_EXPR_TAGS_NONE);
        }
    }

    public static void setTags(ContentValues values, Date xmpFileModifyDate, String... tags) {
        values.put(SQL_COL_EXT_TAGS, TagConverter.asDbString("", tags));
        setXmpFileModifyDate(values, xmpFileModifyDate);
    }

    public static void setDescription(ContentValues values, Date xmpFileModifyDate, String description) {
        values.put(SQL_COL_EXT_DESCRIPTION, description);
        setXmpFileModifyDate(values, xmpFileModifyDate);
    }

    public static void setRating(ContentValues values, Date xmpFileModifyDate, Integer value) {
        values.put(SQL_COL_EXT_RATING, value);
        setXmpFileModifyDate(values, xmpFileModifyDate);
    }

    public static void setXmpFileModifyDate(ContentValues values, Date xmpFileModifyDate) {
        long lastScan = (xmpFileModifyDate != null)
                ? xmpFileModifyDate.getTime() // millisec since 1970-01-01
                : EXT_LAST_EXT_SCAN_UNKNOWN;
        setXmpFileModifyDate(values, lastScan);
    }

    public static void setXmpFileModifyDate(ContentValues values, long xmpFileModifyDateMilliSecs) {
        if ((values != null)
                && (xmpFileModifyDateMilliSecs != EXT_LAST_EXT_SCAN_UNKNOWN)
                && Global.Media.enableNonStandardMediaFieldsUpdateLastScanTimestamp) {
            values.put(SQL_COL_EXT_XMP_LAST_MODIFIED_DATE, xmpFileModifyDateMilliSecs);
        }
    }

    public static ContentValues getDbContent(Context context, final long id) {
        ContentResolver resolver = context.getContentResolver();

        Cursor c = null;
        try {
            c = resolver.query(SQL_TABLE_EXTERNAL_CONTENT_URI_FILE, new String[]{"*"}, FILTER_COL_PK, new String[]{"" + id}, null);
            if (c.moveToNext()) {
                ContentValues values = new ContentValues();
                DatabaseUtils.cursorRowToContentValues(c, values);
                return values;
            }
        } catch (Exception ex) {
            Log.e(Global.LOG_CONTEXT, "FotoSql.getDbContent(id=" + id + ") failed", ex);
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    public static int execUpdate(Context context, String path, long xmpFileDate, ContentValues values, int visibility) {
        if ((!Global.Media.enableXmpNone) || (xmpFileDate == EXT_LAST_EXT_SCAN_UNKNOWN)) {
            return execUpdate(context, path, values, visibility);
        }
        return context.getContentResolver().update(FotoSql.SQL_TABLE_EXTERNAL_CONTENT_URI_FILE
                , values
                , FILTER_EXPR_PATH_LIKE_XMP_DATE, new String[]{path, Long.toString(xmpFileDate)});
    }

    static class TagWorflowItem {
        final long id;
        final long xmpLastModifiedDate;
        final String path;
        final List<String> tags;

        public TagWorflowItem(long id, String path, List<String> tags, long xmpLastModifiedDate) {
            this.tags = tags;
            this.path = path;
            this.id = id;
            this.xmpLastModifiedDate = xmpLastModifiedDate;
        }
    }

    public static List<TagWorflowItem> loadTagWorflowItems(Context context, String selectedItems) {
        QueryParameter query = new QueryParameter()
                .addColumn(TagSql.SQL_COL_PK, TagSql.SQL_COL_PATH, TagSql.SQL_COL_EXT_TAGS, TagSql.SQL_COL_EXT_XMP_LAST_MODIFIED_DATE);


        TagSql.setWhereSelectionPks(query, selectedItems);

        Cursor c = null;
        List<TagWorflowItem> result = new ArrayList<TagWorflowItem>();
        try {
            c = createCursorForQuery(context, query, IGalleryFilter.VISIBILITY_PRIVATE_PUBLIC);
            if (c.moveToFirst()) {
                c = createCursorForQuery(context, query, IGalleryFilter.VISIBILITY_PRIVATE_PUBLIC);
                while (c.moveToNext()) {
                    result.add(new TagWorflowItem(c.getInt(0), c.getString(1), TagConverter.fromString(c.getString(2)),
                            c.getInt(3)));
                }
            }
        } catch (Exception ex) {
            Log.e(Global.LOG_CONTEXT, "FotoSql.execGetGeoRectangle(): error executing " + query, ex);
        } finally {
            if (c != null) c.close();
        }
        return result;
    }

}