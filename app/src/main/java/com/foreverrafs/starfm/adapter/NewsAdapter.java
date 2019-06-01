package com.foreverrafs.starfm.adapter;

// Created by Emperor95 on 1/13/2019.

import android.content.Context;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.foreverrafs.starfm.R;
import com.foreverrafs.starfm.model.News;
import com.foreverrafs.starfm.util.ItemAnimation;
import com.squareup.picasso.Picasso;

import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import java.util.List;

public class NewsAdapter extends RecyclerView.Adapter<NewsAdapter.NewsHolder> {

    private Context context;
    private List<News> newsList;
    private NewsItemClickListener listener;

    public NewsAdapter(Context context, List<News> list) {
        this.context = context;
        this.newsList = list;
    }

    @Override
    public int getItemCount() {
        return newsList.size();
    }

    @NonNull
    @Override
    public NewsHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int i) {
        View view = LayoutInflater.from(viewGroup.getContext())
                .inflate(R.layout.item_news__, viewGroup, false);

        return new NewsHolder(view);
    }


    private int lastPosition = -1;
    private boolean on_attach = true;

    @Override
    public void onBindViewHolder(@NonNull final NewsHolder newsHolder, final int position) {
        final News newsItem = newsList.get(position);

        DateTimeFormatter formatter = DateTimeFormat.forPattern("MMMM d, yyyy");
        newsHolder.date.setText(newsItem.getDate().toString(formatter));

        newsHolder.headline.setText(newsItem.getHeadline());

        Picasso.get().load(newsItem.getImage()).into(newsHolder.imageView);

        ViewCompat.setTransitionName(newsHolder.imageView, newsItem.getImage().substring(0, 5).trim());
        ViewCompat.setTransitionName(newsHolder.headline, newsItem.getHeadline().substring(0, 5).trim());

        setAnimation(newsHolder.itemView, position);
    }

    private void setAnimation(View view, int position) {
        if (position > lastPosition) {
            int animation_type = ItemAnimation.FADE_IN;
            ItemAnimation.animate(view, on_attach ? position : -1, animation_type);
            lastPosition = position;
        }
    }

    public void setOnNewsItemClickListener(NewsItemClickListener listener) {
        this.listener = listener;
    }

    public interface NewsItemClickListener {
        void onNewItemClicked(News newsObject, Pair[] pairs, int position);
    }

    public class NewsHolder extends RecyclerView.ViewHolder implements View.OnClickListener {

        private TextView date, headline;
        private ImageView imageView;

        NewsHolder(View view) {
            super(view);
            view.setOnClickListener(this);
            headline = itemView.findViewById(R.id.headline);
            date = itemView.findViewById(R.id.date);
            imageView = itemView.findViewById(R.id.image);
        }

        public TextView getHeadlineTextView() {
            return headline;
        }

        public ImageView getImageImageView() {
            return imageView;
        }

        @Override
        public void onClick(View v) {
            int position = getAdapterPosition();
            Pair[] pair = new Pair[]{
                    new Pair(imageView, ViewCompat.getTransitionName(imageView)),
                    new Pair(headline, ViewCompat.getTransitionName(headline))
            };
            listener.onNewItemClicked(newsList.get(position), pair, position);
        }
    }
}