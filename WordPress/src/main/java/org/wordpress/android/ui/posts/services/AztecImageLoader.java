package org.wordpress.android.ui.posts.services;

import android.content.Context;
import android.graphics.drawable.BitmapDrawable;

import com.android.volley.VolleyError;
import com.android.volley.toolbox.ImageLoader;

import org.wordpress.android.WordPress;
import org.wordpress.aztec.Html;

public class AztecImageLoader implements Html.ImageGetter {

    private Context context;

    public AztecImageLoader(Context context) {
        this.context = context;
    }
    @Override
    public void loadImage(String url, final Callbacks callbacks, int maxWidth) {
        WordPress.imageLoader.get(url, new ImageLoader.ImageListener() {
            @Override
            public void onResponse(ImageLoader.ImageContainer response, boolean isImmediate) {
                if (response.getBitmap() != null) {
                    BitmapDrawable bitmapDrawable = new BitmapDrawable(context.getResources(), response.getBitmap());
                    callbacks.onImageLoaded(bitmapDrawable);
                }
            }

            @Override
            public void onErrorResponse(VolleyError error) {
                callbacks.onImageLoadingFailed();
            }
        }, maxWidth, 0);
    }
}
