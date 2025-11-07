package com.custombrowser

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class BookmarkManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_BOOKMARKS = "bookmarks_list"
        private const val KEY_QUICK_ACCESS = "quick_access_urls"
    }

    fun addBookmark(bookmark: Bookmark) {
        val bookmarks = getBookmarks().toMutableList()
        bookmarks.add(bookmark)
        saveBookmarks(bookmarks)
    }

    fun removeBookmark(bookmarkId: String) {
        val bookmarks = getBookmarks().filter { it.id != bookmarkId }
        saveBookmarks(bookmarks)
    }

    fun getBookmarks(): List<Bookmark> {
        val json = prefs.getString(KEY_BOOKMARKS, null) ?: return emptyList()
        val type = object : TypeToken<List<Bookmark>>() {}.type
        return gson.fromJson(json, type)
    }

    fun isBookmarked(url: String): Boolean {
        return getBookmarks().any { it.url == url }
    }

    private fun saveBookmarks(bookmarks: List<Bookmark>) {
        val json = gson.toJson(bookmarks)
        prefs.edit().putString(KEY_BOOKMARKS, json).apply()
    }

    // Quick access URLs for AI services
    fun setQuickAccessUrls(urls: Map<String, String>) {
        val json = gson.toJson(urls)
        prefs.edit().putString(KEY_QUICK_ACCESS, json).apply()
    }

    fun getQuickAccessUrls(): Map<String, String> {
        val json = prefs.getString(KEY_QUICK_ACCESS, null)
        if (json == null) {
            // Default URLs - Updated for OAuth login
            val defaults = mapOf(
                "claude" to "https://claude.ai/new",
                "gemini" to "https://gemini.google.com/app",
                "chatgpt" to "https://chatgpt.com",  // Changed from codex
                "localhost" to "http://localhost:3000",
                "github" to "https://github.com"
            )
            setQuickAccessUrls(defaults)
            return defaults
        }
        val type = object : TypeToken<Map<String, String>>() {}.type
        return gson.fromJson(json, type)
    }
}
