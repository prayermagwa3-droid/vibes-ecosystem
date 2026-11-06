package com.vibes.only.controller

import android.content.Context
import com.vibes.only.*
import com.vibes.only.engine.*
import com.vibes.only.memory.*
import com.vibes.only.security.SecurityManager
import com.vibes.only.tools.*
import com.vibes.only.intelligence.AssistantIntelligence

/** Single composition root. New features should be wired here instead of MainActivity. */
class AppContainer(context: Context) {
    val files = JsonFiles(context.applicationContext)
    val settings = SettingsStore(files)
    val vibesEngine = VibesEngineClient(context.applicationContext, settings)
    val notifications = Notifications(settings, files)
    val security = Security(settings, notifications, files)
    val users = Users(settings, notifications, security, files, context.applicationContext)
    val store = Store(context.applicationContext, settings, security, notifications, users, files)
    val web = WebEngine(settings, notifications, files)
    val intelligence = AssistantIntelligence(settings, store, users)

    val memoryStore = MemoryStore(store)
    val knowledgeStore = KnowledgeStore(store)
    val vectorStore = VectorStore(files)
    val knowledgeGraph = KnowledgeGraph(files)
    val knowledgeEngine = KnowledgeEngine(memoryStore, knowledgeStore, vectorStore, knowledgeGraph, store, settings)

    val webTool = WebTool(web)
    val researchAgent = ResearchAgent(webTool, knowledgeEngine, settings)
    val intentRouter = IntentRouter()
    val brain = Brain(settings, store, web, users, security)
    val v1Engine = V1Engine(
        settings = settings,
        brain = brain,
        intentRouter = intentRouter,
        researchAgent = researchAgent,
        knowledgeEngine = knowledgeEngine
    )
}
