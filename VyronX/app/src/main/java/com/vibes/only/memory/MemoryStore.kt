package com.vibes.only.memory

import com.vibes.only.MemoryHit
import com.vibes.only.Store

class MemoryStore(private val store: Store) {
    fun search(query: String, limit: Int = 8): List<MemoryHit> = store.searchAll(query, limit)
    fun recall(query: String): MemoryHit? = store.recall(query)
    fun stats(): String = store.stats()
}
