/**
 * Copyright (C) 2013- Iordan Iordanov
 * <p>
 * This is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this software; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307,
 * USA.
 */


package com.undatech.opaque;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatImageView;

import com.qihua.bVNC.Constants;
import com.qihua.bVNC.NewConnection;
import com.qihua.bVNC.Utils;
import com.qihua.bVNC.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LabeledImageApapter extends BaseAdapter {
    List<Connection> filteredConnectionsByPosition = new ArrayList<>();
    String[] filter = null;
    private Context context;
    private int numCols = 2;
    private String defaultLabel = "Untitled";
    private boolean doNotShowDesktopThumbnails = false;
    /** 缩略图开关在构造时读一次,getView 里不再碰 SharedPreferences */
    private final boolean smallThumbnails;
    private final int overlayColor;
    private final int screenWidthDp;
    /** 缩略图缓存:跨 adapter 实例共享,从远程桌面返回后不用重新解码 */
    private static final LruCache<String, Bitmap> thumbCache = new LruCache<String, Bitmap>(8 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };
    /** 截图解码绝不能放在 UI 线程:720p PNG 解一张就是 10ms 起,滚动时直接掉帧 */
    private static final ExecutorService DECODER = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public LabeledImageApapter(Context context, Map<String, Connection> connectionsByPosition, String[] filter) {
        this.context = context;
        this.filter = filter;
        if (connectionsByPosition != null) {
            for (Connection c : connectionsByPosition.values()) {
                boolean include = true;
                for (String item : this.filter) {
                    if (!c.getLabel().toLowerCase().contains(item)) {
                        include = false;
                    }
                }
                if (include) {
                    this.filteredConnectionsByPosition.add(c);
                }
            }
        }

        // add the last one to connect
        this.filteredConnectionsByPosition.add(new NewConnection());


        doNotShowDesktopThumbnails = Utils.querySharedPreferenceBoolean(context, Constants.doNotShowDesktopThumbnails);
        smallThumbnails = Utils.querySharedPreferenceBoolean(context, Constants.useSmallThumbnails, true);
        overlayColor = context.getColor(R.color.black_overlay);
        screenWidthDp = context.getResources().getConfiguration().screenWidthDp;
    }

    public View getView(int position, View convertView, ViewGroup parent) {
        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        Connection c = filteredConnectionsByPosition.get(position);

        String label = c.getLabel();

        GridView gView = (GridView) ((Activity) context).findViewById(R.id.gridView);

        // 这个设置只在真的要变时才调用:getView 跑在 GridView 的布局过程中,
        // 无条件 setNumColumns 会 requestLayout,滚动时每一格都重排一次
        if (smallThumbnails) {
            int cols = screenWidthDp < 600 ? 3 : 4;
            if (gView.getNumColumns() != cols) {
                gView.setNumColumns(cols);
            }
        }
        numCols = gView.getNumColumns();

        int width = gView.getWidth() / numCols;
        int height = width;

        View gridView;
        if (convertView != null) {
            gridView = convertView;
        } else {
            gridView = inflater.inflate(R.layout.grid_item, null);
        }

        // setLayoutParams 会 requestLayout;getView 本来就在布局过程中,
        // 每绑定一次就重排一次是滚动卡顿的主要来源之一
        ViewGroup.LayoutParams lp = gridView.getLayoutParams();
        if (lp == null) {
            lp = new GridView.LayoutParams(height, height);
            gridView.setLayoutParams(lp);
        } else if (lp.height != height || lp.width != height) {
            lp.height = height;
            lp.width = height;
            gridView.setLayoutParams(lp);
        }

        TextView textView = (TextView) gridView.findViewById(R.id.grid_item_text);

        if (label.equals("$NEW$")) {
            textView.setText(context.getString(R.string.new_connection));
        } else {
            if ("".equals(label)) {
                textView.setText(defaultLabel);
            } else {
                if (c.getPriority() > 10000) {
                    textView.setText(String.format("★ %s", label));
                } else {
                    textView.setText(label);
                }
            }
        }



        String screenshotFilePath = context.getFilesDir() + "/" + c.getScreenshotFilename();
        AppCompatImageView imageView = gridView.findViewById(R.id.grid_item_image);
        imageView.setBackgroundColor(overlayColor);
        if (doNotShowDesktopThumbnails) {
            imageView.setImageResource(R.drawable.rdp_connection);
            imageView.setScaleType(ImageView.ScaleType.CENTER);
        } else {
            String key = screenshotFilePath + "#" + width;
            imageView.setTag(key);
            Bitmap gridImage = thumbCache.get(key);
            if (gridImage != null) {
                imageView.setImageBitmap(gridImage);
                imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            } else {
                // 先摆占位图,解码在后台线程做完再替换 —— UI 线程不碰磁盘
                imageView.setImageResource(c.getId().equals("$NC")
                        ? R.drawable.ic_add_connection_48 : R.drawable.rdp_connection);
                imageView.setScaleType(ImageView.ScaleType.CENTER);
                decodeAsync(imageView, key, screenshotFilePath, width);
            }
        }


        // This ID in a hidden TextView is used to send the right connection for editing or connection establishment
        // when the item is long-tapped or tapped respectively.
        TextView gridItemId = gridView.findViewById(R.id.grid_item_id);
        gridItemId.setText(c.getId());

        return gridView;
    }

    /**
     * 后台解码截图,完成后回主线程替换占位图。key 用 tag 校验,
     * 防止 view 已被复用到别的 item 时贴错图。
     */
    private void decodeAsync(final AppCompatImageView imageView, final String key,
                             final String path, final int targetPx) {
        DECODER.execute(new Runnable() {
            @Override
            public void run() {
                if (thumbCache.get(key) != null) {
                    return;
                }
                final Bitmap bitmap = decodeThumbnail(path, targetPx);
                if (bitmap == null) {
                    return;   // 没有截图文件,保留占位图
                }
                thumbCache.put(key, bitmap);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (key.equals(imageView.getTag())) {
                            imageView.setImageBitmap(bitmap);
                            imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        }
                    }
                });
            }
        });
    }

    /** 按格子尺寸采样解码。只在后台线程调用。 */
    private static Bitmap decodeThumbnail(String path, int targetPx) {
        if (targetPx <= 0) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) {
            sample *= 2;
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.RGB_565;   // 截图不需要 alpha,内存减半
        return BitmapFactory.decodeFile(path, opts);
    }

    @Override
    public int getCount() {
        int count = 0;
        if (filteredConnectionsByPosition != null) {
            count = filteredConnectionsByPosition.size();
        }
        return count;
    }

    @Override
    public Object getItem(int position) {
        return filteredConnectionsByPosition.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }
}
