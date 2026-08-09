package com.example.englishreader.data.local

import com.example.englishreader.data.local.entity.ContentType
import com.example.englishreader.data.local.entity.DictionaryEntry
import com.example.englishreader.data.local.entity.ReadingItem

/**
 * 首次启动时写入的示例数据：
 *  - 几篇可阅读的英文内容（公版小说节选 + 原创外刊风格文章）
 *  - 一批中英文词典假数据，覆盖示例文本中的常见词，方便点击查词时命中
 *
 * 后续支持导入 CSV / JSON 词典后，可逐步替换/扩充 dictionary_entries。
 */
object SeedData {

    suspend fun populate(db: AppDatabase) {
        if (db.readingItemDao().count() == 0) {
            db.readingItemDao().insertAll(sampleReadingItems())
        }
        if (db.dictionaryDao().count() == 0) {
            db.dictionaryDao().insertAll(sampleDictionary())
        }
    }

    private fun sampleReadingItems(): List<ReadingItem> = listOf(
        ReadingItem(
            title = "Pride and Prejudice (Excerpt)",
            contentType = ContentType.NOVEL,
            content = listOf(
                "It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife.",
                "However little known the feelings or views of such a man may be on his first entering a neighbourhood, this truth is so well fixed in the minds of the surrounding families, that he is considered as the rightful property of some one or other of their daughters.",
                "\"My dear Mr. Bennet,\" said his lady to him one day, \"have you heard that Netherfield Park is let at last?\"",
                "Mr. Bennet replied that he had not.",
                "\"But it is,\" returned she; \"for Mrs. Long has just been here, and she told me all about it.\"",
                "Mr. Bennet made no answer.",
                "\"Do you not want to know who has taken it?\" cried his wife impatiently.",
                "\"You want to tell me, and I have no objection to hearing it.\"",
            ).joinToString("\n\n"),
        ),
        ReadingItem(
            title = "Alice's Adventures in Wonderland (Excerpt)",
            contentType = ContentType.NOVEL,
            content = listOf(
                "Alice was beginning to get very tired of sitting by her sister on the bank, and of having nothing to do: once or twice she had peeped into the book her sister was reading, but it had no pictures or conversations in it, \"and what is the use of a book,\" thought Alice, \"without pictures or conversations?\"",
                "So she was considering in her own mind (as well as she could, for the hot day made her feel very sleepy and stupid), whether the pleasure of making a daisy-chain would be worth the trouble of getting up and picking the daisies, when suddenly a White Rabbit with pink eyes ran close by her.",
                "There was nothing so very remarkable in that; nor did Alice think it so very much out of the way to hear the Rabbit say to itself, \"Oh dear! Oh dear! I shall be late!\"",
                "But when the Rabbit actually took a watch out of its waistcoat-pocket, and looked at it, and then hurried on, Alice started to her feet.",
            ).joinToString("\n\n"),
        ),
        ReadingItem(
            title = "Why We Still Read",
            contentType = ContentType.ARTICLE,
            content = listOf(
                "In an age of endless scrolling, the quiet act of reading a long text can feel almost rebellious. Yet research suggests that deep reading shapes the brain in ways that skimming never will.",
                "When we follow a complex argument across several pages, we practise patience and sustained attention. These are precisely the capacities that a noisy, notification-driven world tends to erode.",
                "Language learners gain something extra. Each unfamiliar word encountered in context becomes a small puzzle, and solving it strengthens memory far more than a list of isolated definitions ever could.",
                "Perhaps that is the enduring appeal of a good book. It asks for nothing but our attention, and in return it quietly expands the boundaries of what we can think.",
            ).joinToString("\n\n"),
        ),
    )

    private fun de(
        word: String,
        phonetic: String,
        partOfSpeech: String,
        chinese: String,
        english: String,
        example: String,
        lemma: String = word,
    ) = DictionaryEntry(
        word = word,
        lemma = lemma,
        phonetic = phonetic,
        partOfSpeech = partOfSpeech,
        chineseMeaning = chinese,
        englishDefinition = english,
        exampleSentence = example,
    )

    private fun sampleDictionary(): List<DictionaryEntry> = listOf(
        de("universally", "/ˌjuːnɪˈvɜːsəli/", "adv.", "普遍地；一致地", "by everyone; in every case", "The theory is now universally accepted.", lemma = "universal"),
        de("acknowledged", "/əkˈnɒlɪdʒd/", "adj.", "公认的；得到承认的", "generally accepted or recognized", "She is an acknowledged expert in the field.", lemma = "acknowledge"),
        de("possession", "/pəˈzeʃn/", "n.", "拥有；财产", "the state of having or owning something", "He came into possession of a large estate.", lemma = "possession"),
        de("fortune", "/ˈfɔːtʃuːn/", "n.", "财富；运气", "a large amount of money; chance or luck", "He made a fortune in the property market.", lemma = "fortune"),
        de("neighbourhood", "/ˈneɪbəhʊd/", "n.", "街区；附近地区", "a district or area, especially one within a town", "They moved to a quiet neighbourhood.", lemma = "neighbourhood"),
        de("considered", "/kənˈsɪdəd/", "v.", "认为；考虑（consider 的过去式/过去分词）", "thought about or regarded in a particular way", "He is considered one of the best writers.", lemma = "consider"),
        de("rightful", "/ˈraɪtfl/", "adj.", "合法的；正当的", "having a legitimate or just claim", "The painting was returned to its rightful owner.", lemma = "rightful"),
        de("property", "/ˈprɒpəti/", "n.", "财产；房产；属性", "a thing or things belonging to someone", "The house is private property.", lemma = "property"),
        de("replied", "/rɪˈplaɪd/", "v.", "回答（reply 的过去式）", "answered", "He replied that he had not heard the news.", lemma = "reply"),
        de("returned", "/rɪˈtɜːnd/", "v.", "回答；返回（return 的过去式）", "answered; came or went back", "\"Indeed,\" she returned with a smile.", lemma = "return"),
        de("impatiently", "/ɪmˈpeɪʃntli/", "adv.", "不耐烦地；急切地", "in a way that shows a lack of patience", "\"Hurry up!\" she said impatiently.", lemma = "impatient"),
        de("objection", "/əbˈdʒekʃn/", "n.", "反对；异议", "an expression of disapproval or disagreement", "I have no objection to your plan.", lemma = "objection"),
        de("peeped", "/piːpt/", "v.", "偷看；窥视（peep 的过去式）", "looked quickly and secretly", "She peeped through the curtains.", lemma = "peep"),
        de("conversations", "/ˌkɒnvəˈseɪʃnz/", "n.", "对话；交谈（conversation 的复数）", "informal spoken exchanges", "We had long conversations about books.", lemma = "conversation"),
        de("considering", "/kənˈsɪdərɪŋ/", "v.", "思考；考虑（consider 的现在分词）", "thinking carefully about something", "She was considering her options.", lemma = "consider"),
        de("pleasure", "/ˈpleʒə/", "n.", "乐趣；愉快", "a feeling of happy satisfaction and enjoyment", "Reading is a great pleasure for me.", lemma = "pleasure"),
        de("suddenly", "/ˈsʌdnli/", "adv.", "突然地", "quickly and unexpectedly", "Suddenly the lights went out.", lemma = "sudden"),
        de("remarkable", "/rɪˈmɑːkəbl/", "adj.", "非凡的；引人注目的", "worthy of attention; striking", "She made remarkable progress.", lemma = "remarkable"),
        de("rebellious", "/rɪˈbeljəs/", "adj.", "叛逆的；反抗的", "showing a desire to resist authority or control", "The rebellious teenager refused to obey.", lemma = "rebellious"),
        de("suggests", "/səˈdʒests/", "v.", "表明；建议（suggest 的第三人称单数）", "indicates; puts forward for consideration", "The evidence suggests another explanation.", lemma = "suggest"),
        de("sustained", "/səˈsteɪnd/", "adj.", "持续的；持久的", "continuing for an extended period", "Success requires sustained effort.", lemma = "sustain"),
        de("attention", "/əˈtenʃn/", "n.", "注意；专注", "the act of concentrating on something", "Please pay attention to the details.", lemma = "attention"),
        de("capacities", "/kəˈpæsətiz/", "n.", "能力；容量（capacity 的复数）", "abilities to do or contain something", "We must develop our mental capacities.", lemma = "capacity"),
        de("erode", "/ɪˈrəʊd/", "v.", "侵蚀；逐渐削弱", "gradually wear or break down", "Constant noise can erode our focus.", lemma = "erode"),
        de("unfamiliar", "/ˌʌnfəˈmɪliə/", "adj.", "不熟悉的；陌生的", "not known or recognized", "He guessed the meaning of the unfamiliar word.", lemma = "unfamiliar"),
        de("context", "/ˈkɒntekst/", "n.", "语境；上下文；背景", "the words or situation surrounding something", "You can guess the meaning from the context.", lemma = "context"),
        de("isolated", "/ˈaɪsəleɪtɪd/", "adj.", "孤立的；单独的", "separate; standing alone", "Memorizing isolated words is less effective.", lemma = "isolate"),
        de("enduring", "/ɪnˈdjʊərɪŋ/", "adj.", "持久的；不朽的", "continuing or lasting for a long time", "Their friendship proved enduring.", lemma = "endure"),
        de("boundaries", "/ˈbaʊndriz/", "n.", "边界；界限（boundary 的复数）", "the limits of an area or subject", "Reading expands the boundaries of the mind.", lemma = "boundary"),
        de("truth", "/truːθ/", "n.", "真相；事实", "the quality or state of being true", "There is some truth in what he says.", lemma = "truth"),
        de("single", "/ˈsɪŋɡl/", "adj.", "单一的；单身的", "only one; unmarried", "A single man stood at the door.", lemma = "single"),
    )
}
