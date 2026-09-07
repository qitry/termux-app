package com.termux.app.filemanager;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class FileManagerAdapter extends RecyclerView.Adapter<FileManagerAdapter.FileViewHolder> {

    private static final int PLACEHOLDER_COLOR = 0xFF888888;

    public interface Callbacks {
        void onFileClicked(FileEntry entry);

        void onFileLongClicked(FileEntry entry);

        void onSelectionChanged(int count);
    }

    private final List<FileEntry> mItems = new ArrayList<>();
    private final List<FileEntry> mSelected = new ArrayList<>();
    private final Callbacks mCallbacks;
    private boolean mSelectionActive;

    private final SimpleDateFormat mDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);

    public FileManagerAdapter(Callbacks callbacks) {
        mCallbacks = callbacks;
    }

    public void setItems(List<FileEntry> items) {
        mItems.clear();
        mItems.addAll(items);
        mSelected.retainAll(mItems);
        notifyDataSetChanged();
    }

    public List<FileEntry> getItems() {
        return new ArrayList<>(mItems);
    }

    public int indexOf(FileEntry entry) {
        return mItems.indexOf(entry);
    }

    public boolean isSelectionActive() {
        return mSelectionActive;
    }

    public List<FileEntry> getSelected() {
        return new ArrayList<>(mSelected);
    }

    public int getSelectedCount() {
        return mSelected.size();
    }

    public void startSelection(FileEntry initial) {
        mSelectionActive = true;
        mSelected.clear();
        if (initial != null) mSelected.add(initial);
        notifyDataSetChanged();
        mCallbacks.onSelectionChanged(mSelected.size());
    }

    public void clearSelection() {
        mSelectionActive = false;
        mSelected.clear();
        notifyDataSetChanged();
        mCallbacks.onSelectionChanged(0);
    }

    public void selectAll() {
        mSelectionActive = true;
        mSelected.clear();
        mSelected.addAll(mItems);
        notifyDataSetChanged();
        mCallbacks.onSelectionChanged(mSelected.size());
    }

    public void toggleSelection(FileEntry entry) {
        if (entry.isSpecial()) return;
        if (mSelected.contains(entry)) mSelected.remove(entry);
        else mSelected.add(entry);
        if (mSelected.isEmpty()) {
            clearSelection();
        } else {
            notifyDataSetChanged();
            mCallbacks.onSelectionChanged(mSelected.size());
        }
    }

    /** Select every item between the last-selected anchor and {@code entry}, inclusive. */
    public void selectRange(FileEntry entry) {
        if (entry.isSpecial()) return;
        if (mSelected.isEmpty()) {
            startSelection(entry);
            return;
        }
        int anchor = mItems.indexOf(mSelected.get(mSelected.size() - 1));
        int target = mItems.indexOf(entry);
        if (anchor < 0 || target < 0) return;
        int from = Math.min(anchor, target);
        int to = Math.max(anchor, target);
        for (int i = from; i <= to; i++) {
            FileEntry item = mItems.get(i);
            if (!item.isSpecial() && !mSelected.contains(item)) mSelected.add(item);
        }
        notifyDataSetChanged();
        mCallbacks.onSelectionChanged(mSelected.size());
    }

    @NonNull
    @Override
    public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_file_list, parent, false);
        return new FileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
        FileEntry entry = mItems.get(position);
        holder.entry = entry;

        if (entry.isSpecial()) {
            holder.name.setText(entry.name);
            holder.name.setTextSize(12);
            holder.meta.setText("");
            if (entry.special == FileEntry.SPECIAL_PLACEHOLDER) {
                holder.icon.setVisibility(View.INVISIBLE);
                holder.name.setTextColor(PLACEHOLDER_COLOR);
                holder.itemView.setOnClickListener(null);
                holder.itemView.setOnLongClickListener(v -> true);
            } else {
                holder.icon.setVisibility(View.VISIBLE);
                holder.icon.setImageResource(entry.special == FileEntry.SPECIAL_PARENT
                    ? R.drawable.ic_fm_up : R.drawable.ic_fm_move);
                holder.name.setTextColor(holder.originalTextColor);
                holder.itemView.setOnClickListener(v -> mCallbacks.onFileClicked(entry));
                holder.itemView.setOnLongClickListener(v -> true);
            }
            holder.itemView.setBackgroundColor(0x00000000);
            return;
        }

        holder.icon.setVisibility(View.VISIBLE);
        holder.name.setTextColor(holder.originalTextColor);
        holder.name.setTextSize(14);
        holder.icon.setImageResource(FileIcons.getIconRes(entry));
        if (!entry.isArchiveEntry() && FileIcons.isImage(entry) && entry.hostFile != null)
            ThumbnailLoader.loadInto(holder.icon, entry.hostFile);

        holder.name.setText(entry.name);

        String size = entry.isDirectory ? "" : FileManagerUtils.humanReadableSize(entry.size) + "  ";
        holder.meta.setText(size + mDateFormat.format(new Date(entry.lastModified)));

        boolean selected = mSelected.contains(entry);
        holder.itemView.setBackgroundColor(selected ? 0x40888888 : 0x00000000);

        holder.itemView.setOnClickListener(v -> {
            if (mSelectionActive) toggleSelection(entry);
            else mCallbacks.onFileClicked(entry);
        });
        holder.itemView.setOnLongClickListener(v -> {
            mCallbacks.onFileLongClicked(entry);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    public FileEntry getEntryAt(int position) {
        if (position < 0 || position >= mItems.size()) return null;
        return mItems.get(position);
    }

    public     static class FileViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final TextView meta;
        final int originalTextColor;
        FileEntry entry;

        FileViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.file_icon);
            name = itemView.findViewById(R.id.file_name);
            meta = itemView.findViewById(R.id.file_meta);
            originalTextColor = name.getCurrentTextColor();
        }
    }
}
