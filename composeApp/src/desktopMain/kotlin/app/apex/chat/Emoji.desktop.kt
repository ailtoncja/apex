package app.apex.chat

/** O `emoji.tsv` dos recursos do app (vai dentro do .jar). */
actual fun loadEmojiTsv(): String = EmojiCatalog::class.java.getResourceAsStream("/emoji.tsv")?.use { it.readBytes().decodeToString() }.orEmpty()
