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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 回看网格：日期标题行 + 一格一格的缩略图（照片是一组，录像是一条连续录制）。
 *
 * <p>照片和录像共用：一格要显示什么由 {@link Binder} 说（缩略图是哪个文件、角上写什么字）。
 * 选择模式下点一格是勾选 —— 选中的那一格左上角一枚橙色的勾，和车机相册一样。</p>
 */
public final class GalleryAdapter<T> extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int TYPE_HEADER = 0;
    public static final int TYPE_TILE = 1;

    public interface Binder<T> {
        /** 缩略图取哪个文件；null 就留灰底 */
        File thumbnail(T item);
        /** 是不是录像：缩略图取一帧而不是整张解码，角上多一个播放标 */
        boolean isVideo(T item);
        /** 角上那行字：照片是时刻，录像是时长 */
        String caption(T item);
        /** 这一格有没有锁定的文件 */
        boolean locked(T item);
    }

    public interface OnTileClick<T> {
        void onTileClick(T item);
    }

    private final List<DateSection<T>> sections;
    private final Binder<T> binder;
    private final List<Object> rows = new ArrayList<>();
    private final Set<T> chosen = new LinkedHashSet<>();
    private boolean selecting;
    private OnTileClick<T> onTileClick;
    private Runnable onSelectionChanged;

    public GalleryAdapter(List<DateSection<T>> sections, Binder<T> binder) {
        this.sections = sections;
        this.binder = binder;
        rebuild();
    }

    /** 数据换了之后重新摊平（标题行、格子）。 */
    public void rebuild() {
        rows.clear();
        for (DateSection<T> section : sections) {
            rows.add(section);
            rows.addAll(section.getItems());
        }
        chosen.retainAll(allItems());
        notifyDataSetChanged();
    }

    private List<T> allItems() {
        List<T> all = new ArrayList<>();
        for (DateSection<T> section : sections) {
            all.addAll(section.getItems());
        }
        return all;
    }

    public void setOnTileClick(OnTileClick<T> listener) {
        this.onTileClick = listener;
    }

    public void setOnSelectionChanged(Runnable listener) {
        this.onSelectionChanged = listener;
    }

    // ------------------------------------------------------------------ 选择模式

    public boolean isSelecting() {
        return selecting;
    }

    /** 进出都把已选清空：留着上一次的选择只会让人误删。 */
    public void setSelecting(boolean on) {
        if (selecting == on) {
            return;
        }
        selecting = on;
        chosen.clear();
        notifyDataSetChanged();
        changed();
    }

    public void selectAll() {
        chosen.clear();
        chosen.addAll(allItems());
        notifyDataSetChanged();
        changed();
    }

    public void clearSelection() {
        chosen.clear();
        notifyDataSetChanged();
        changed();
    }

    public boolean allSelected() {
        List<T> all = allItems();
        return !all.isEmpty() && chosen.size() == all.size();
    }

    public List<T> chosenItems() {
        return new ArrayList<>(chosen);
    }

    public int chosenCount() {
        return chosen.size();
    }

    private void changed() {
        if (onSelectionChanged != null) {
            onSelectionChanged.run();
        }
    }

    // ------------------------------------------------------------------ RecyclerView

    @Override
    public int getItemViewType(int position) {
        return rows.get(position) instanceof DateSection ? TYPE_HEADER : TYPE_TILE;
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderHolder(inflater.inflate(R.layout.item_gallery_header, parent, false));
        }
        return new TileHolder(inflater.inflate(R.layout.item_gallery_tile, parent, false));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object row = rows.get(position);
        if (row instanceof DateSection) {
            DateSection<T> section = (DateSection<T>) row;
            HeaderHolder h = (HeaderHolder) holder;
            h.title.setText(section.getFormattedDateDisplay(h.itemView.getContext()));
            h.subtitle.setText(section.getDayOfWeek());
            return;
        }
        T item = (T) row;
        TileHolder h = (TileHolder) holder;
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
        h.caption.setText(binder.caption(item));
        h.play.setVisibility(binder.isVideo(item) ? View.VISIBLE : View.GONE);
        h.lock.setVisibility(binder.locked(item) ? View.VISIBLE : View.GONE);
        h.check.setVisibility(selecting && chosen.contains(item) ? View.VISIBLE : View.GONE);
        h.itemView.setAlpha(selecting && !chosen.contains(item) ? 0.7f : 1f);
        h.itemView.setOnClickListener(v -> {
            if (selecting) {
                if (!chosen.remove(item)) {
                    chosen.add(item);
                }
                int at = h.getAdapterPosition();
                if (at != RecyclerView.NO_POSITION) {
                    notifyItemChanged(at);
                }
                changed();
            } else if (onTileClick != null) {
                onTileClick.onTileClick(item);
            }
        });
    }

    static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;

        HeaderHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.gallery_header_title);
            subtitle = itemView.findViewById(R.id.gallery_header_subtitle);
        }
    }

    static final class TileHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView caption;
        final View play;
        final View lock;
        final View check;

        TileHolder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.tile_image);
            caption = itemView.findViewById(R.id.tile_caption);
            play = itemView.findViewById(R.id.tile_play);
            lock = itemView.findViewById(R.id.tile_lock);
            check = itemView.findViewById(R.id.tile_check);
        }
    }
}
