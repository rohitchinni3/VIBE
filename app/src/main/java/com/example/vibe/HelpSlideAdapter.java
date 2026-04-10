package com.example.vibe;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class HelpSlideAdapter extends RecyclerView.Adapter<HelpSlideAdapter.SlideViewHolder> {

    private static final String[][] SLIDES = {
        {"👋 Welcome to Vibe", "Vibe connects your phone to your smartwatch so you never miss important sounds."},
        {"👂 Sound alerts", "Teach the app to recognize sounds like a car horn or doorbell. Your watch will vibrate when it hears them."},
        {"🗣️ Name alerts", "Teach the app your name. Your watch will vibrate when someone calls you."},
        {"⌚ Watch setup", "Open 'Set up my watch', choose a mode, connect via Bluetooth, then send your alerts."}
    };

    private final LayoutInflater inflater;

    public HelpSlideAdapter(Context context) {
        this.inflater = LayoutInflater.from(context);
    }

    @NonNull @Override
    public SlideViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = inflater.inflate(R.layout.item_help_slide, parent, false);
        return new SlideViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull SlideViewHolder holder, int position) {
        holder.title.setText(SLIDES[position][0]);
        holder.body.setText(SLIDES[position][1]);
    }

    @Override public int getItemCount() { return SLIDES.length; }

    static class SlideViewHolder extends RecyclerView.ViewHolder {
        TextView title, body;
        SlideViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.textHelpSlideTitle);
            body  = itemView.findViewById(R.id.textHelpSlideBody);
        }
    }
}
