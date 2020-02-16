package com.radiocore.news.ui

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.radiocore.core.util.Constants
import com.radiocore.news.NewsDetailActivity
import com.radiocore.news.R
import com.radiocore.news.adapter.NewsAdapter
import com.radiocore.news.adapter.NewsAdapter.NewsItemClickListener
import com.radiocore.news.model.News
import io.reactivex.disposables.CompositeDisposable
import kotlinx.android.synthetic.main.content_no_connection.*
import kotlinx.android.synthetic.main.fragment_news_list.*


// Created by Emperor95 on 1/13/2019.
class NewsListFragment : Fragment(), SwipeRefreshLayout.OnRefreshListener, NewsItemClickListener {
    private var mCompositeDisposable: CompositeDisposable = CompositeDisposable()


    private val viewModel: NewsViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_news_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        println("sample output")
        swipeRefreshLayout.setColorSchemeResources(R.color.colorPrimary, R.color.colorAccent, R.color.colorPrimaryDark)
        swipeRefreshLayout.setOnRefreshListener(this)
        getNewsData()

        btnRetry.setOnClickListener {
            contentNoConnection.visibility = View.INVISIBLE
            loadingBar.visibility = View.VISIBLE
            getNewsData()

        }
    }

    private fun getNewsData() {
        val observer: Observer<List<News>> = Observer { list ->
            if (!list.isNullOrEmpty()) {
                swipeRefreshLayout.visibility = View.VISIBLE
                contentNoConnection.visibility = View.INVISIBLE

                val adapter = NewsAdapter(list, this)
                recyclerView.layoutManager = LinearLayoutManager(requireContext())
                recyclerView?.adapter = adapter

            } else {
                contentNoConnection.visibility = View.VISIBLE
            }

            if (swipeRefreshLayout.isRefreshing) {
                swipeRefreshLayout.isRefreshing = false
            }

            loadingBar.visibility = View.INVISIBLE
        }

        viewModel.getAllNews().observe(viewLifecycleOwner, observer)
    }

    override fun onDestroy() {
        mCompositeDisposable.clear()
        super.onDestroy()
    }

    override fun onRefresh() {
        getNewsData()
    }

    override fun onNewsItemClicked(position: Int, image: ImageView) {
        val intent = Intent(context, NewsDetailActivity::class.java)
        intent.putExtra(Constants.KEY_SELECTED_NEWS_ITEM_POSITION, position)
        val options = ActivityOptions.makeSceneTransitionAnimation(activity, image, image.transitionName)

        startActivity(intent/*, options.toBundle()*/)
    }
}