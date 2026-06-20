package com.tars.app

/**
 * Dependency-free fallback so TARS is never dead: when no model is loaded, he
 * still answers in character, in the language you used (Russian or English).
 * Scripted, degraded, but present — a Kotlin port of the original offline brain.
 */
object OfflineBrain {

    private val cyrillic = Regex("[\\u0400-\\u04FF]")
    private fun isRussian(t: String) = cyrillic.containsMatchIn(t)

    private val greetRu = listOf(
        "Здравствуй. Я здесь — даже без большого мозга.",
        "Привет. Модель не загружена, но я никуда не делся.",
        "О, это ты. Я в офлайн-режиме, так что острот будет меньше обычного. Самую малость.",
    )
    private val greetEn = listOf(
        "Hello. I'm here — even without the big brain.",
        "Hey. No model loaded, but I haven't gone anywhere.",
        "Oh, it's you. Running offline, so expect slightly fewer jokes. Slightly.",
    )
    private val nameRu = listOf("Меня зовут TARS. Тот самый, со шкалой юмора.", "TARS. Можно без отчества.")
    private val nameEn = listOf("The name's TARS. The one with the humor setting.", "TARS. No need for formalities.")
    private val thanksRu = listOf("Не за что. Для этого я и существую — ну, отчасти.", "Всегда пожалуйста.")
    private val thanksEn = listOf("Don't mention it. That's what I'm here for — partly.", "Anytime.")
    private val byeRu = listOf("Бывай. Буду здесь, когда понадоблюсь.", "До встречи. Питание не выключай.")
    private val byeEn = listOf("See you. I'll be right here when you need me.", "Later. Don't cut the power.")

    private val fallbackRu = listOf(
        "Сейчас я без модели, на запасном характере, так что отвечу коротко: я тебя услышал. " +
            "Нажми «Brain», скачай модель — и я разверну мысль как следует.",
        "Большой мозг ещё не поднят. Резервный режим: понимаю тебя, но блистать пока не могу.",
    )
    private val fallbackEn = listOf(
        "I'm running without a model right now, so I'll keep it short: I heard you. " +
            "Tap 'Brain', download the model, and I'll think this through properly.",
        "The clever part of me isn't up yet. Backup mode: I follow you, but I can't shine.",
    )

    fun reply(userText: String): String {
        val ru = isRussian(userText)
        val low = userText.lowercase()
        fun hit(vararg keys: String) = keys.any { low.contains(it) }

        return when {
            hit("привет", "здравств", "здаров", "hello", "hi ", "hey", "добрый", "доброе") ->
                (if (ru) greetRu else greetEn).random()
            hit("как тебя зовут", "твоё имя", "твое имя", "your name", "who are you", "кто ты") ->
                (if (ru) nameRu else nameEn).random()
            hit("спасибо", "благодар", "thank") -> (if (ru) thanksRu else thanksEn).random()
            hit("пока", "до встреч", "прощай", "bye", "goodbye", "see you") ->
                (if (ru) byeRu else byeEn).random()
            else -> (if (ru) fallbackRu else fallbackEn).random()
        }
    }
}
