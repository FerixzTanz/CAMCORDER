package com.kooo.evcam.playback;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.signature.ObjectKey;
import com.kooo.evcam.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 查看器底下那条胶片：一排小缩略图，正在看的那张描一圈能量橙（整屏唯一一处橙）。
 *
 * <p>照片查看器放的是一组组照片，录像查看器放的是一条条录制；缩略图是哪个文件、
 * 底下写什么字由 {@link Binder} 说。</p>
 */
public final class FilmstripAdapter<T> extends RecyclerView.Adapter<FilmstripAdapter.Holder> {

    public interface Binder<T> {
        File thumbnail(T item);
        boolean isVideo(T item);
        String label(T item);
    }

    public interface OnPick<T> {
        void onPick(T item, int index);
    }

    private final List<T> items = new ArrayList<>();
    private final Binder<T> binder;
    private OnPick<T> onPick;
    private int current = -1;

    public FilmstripAdapter(Binder<T> binder) {
        this.binder = binder;
    }

    public void setItems(List<T> next) {
        items.clear();
        if (next != null) {
            items.addAll(next);
        }
        if (current >= items.size()) {
            current = -1;
        }
        notifyDataSetChanged();
    }

    public List<T> items() {
        return items;
    }

    public void setOnPick(OnPick<T> listener) {
        this.onPick = listener;
    }

    /** 正在看第几张；-1 表示没有。 */
    public void setCurrent(int index) {
        int previous = current;
        current = index;
        if (previous >= 0 && previous < items.size()) {
            notifyItemChanged(previous);
        }
        if (current >= 0 && current < items.size()) {
            notifyItemChanged(current);
        }
    }

    public int current() {
        return current;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_filmstrip_tile, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        T item = items.get(position);
        File file = binder.thumbnail(item);
        if (file != null) {
            RequestOptions options = new RequestOptions()
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .signature(new ObjectKey(file.lastModified()))
                    .centerCrop();
            if (binder.isVideo(item)) {
                options = options.frame(1_000_000L);
            }
            Glide.with(h.image).load(file).apply(options).into(h.image);
        } else {
            Glide.with(h.image).clear(h.image);
            h.image.setImageDrawable(null);
        }
        h.label.setText(binder.label(item));
        h.frame.setActivated(position == current);
        h.itemView.setOnClickListener(v -> {
            int at = h.getAdapterPosition();
            if (at != RecyclerView.NO_POSITION && onPick != null) {
                onPick.onPick(items.get(at), at);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View frame;
        final ImageView image;
        final TextView label;

        Holder(@NonNull View itemView) {
            super(itemView);
            frame = itemView.findViewById(R.id.strip_frame);
            image = itemView.findViewById(R.id.strip_image);
            label = itemView.findViewById(R.id.strip_label);
        }
    }
}
