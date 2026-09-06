package com.termux.app.filemanager;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class FileManagerAdapter extends RecyclerView.Adapter<FileManagerAdapter.FileViewHolder> {

    public interface Callbacks {
        void onFileClicked(File file);

        void onFileLongClicked(File file);

        void onSelectionChanged(int count);
    }

    private final List<File> mItems = new ArrayList<>();
    private final List<File> mSelected = new ArrayList<>();
    private final Callbacks mCallbacks;
    private boolean mSelectionActive;

    private final SimpleDateFormat mDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);

    public FileManagerAdapter(Callbacks callbacks) {
        mCallbacks = callbacks;
    }

    public void setItems(List<File> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    public boolean isSelectionActive() {
        return mSelectionActive;
    }

    public List<File> getSelected() {
        return new ArrayList<>(mSelected);
    }

    public int getSelectedCount() {
        return mSelected.size();
    }

    public void startSelection(File initial) {
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

    public void toggleSelection(File file) {
        if (mSelected.contains(file)) mSelected.remove(file);
        else mSelected.add(file);
        if (mSelected.isEmpty()) {
            clearSelection();
        } else {
            notifyDataSetChanged();
            mCallbacks.onSelectionChanged(mSelected.size());
        }
    }

    @NonNull
    @Override
    public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_file_list, parent, false);
        return new FileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
        File file = mItems.get(position);
        boolean isDirectory = file.isDirectory();

        holder.icon.setImageResource(isDirectory ? R.drawable.ic_fm_folder : R.drawable.ic_fm_file);
        holder.name.setText(file.getName());

        String size = isDirectory ? "" : FileManagerUtils.humanReadableSize(file.length()) + "  ";
        holder.meta.setText(size + mDateFormat.format(new Date(file.lastModified())));

        boolean selected = mSelected.contains(file);
        holder.itemView.setBackgroundColor(selected ? 0x40888888 : 0x00000000);

        holder.itemView.setOnClickListener(v -> {
            if (mSelectionActive) toggleSelection(file);
            else mCallbacks.onFileClicked(file);
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (mSelectionActive) toggleSelection(file);
            else mCallbacks.onFileLongClicked(file);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    static class FileViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final TextView meta;

        FileViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.file_icon);
            name = itemView.findViewById(R.id.file_name);
            meta = itemView.findViewById(R.id.file_meta);
        }
    }
}
