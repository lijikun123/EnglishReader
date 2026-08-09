package com.example.englishreader.data.local

import androidx.room.TypeConverter
import com.example.englishreader.ai.AiAnalysisType
import com.example.englishreader.data.local.entity.BookFormat
import com.example.englishreader.data.local.entity.ContentType

/** Room 类型转换器：把枚举以字符串形式存库。 */
class Converters {

    @TypeConverter
    fun contentTypeToString(value: ContentType): String = value.name

    @TypeConverter
    fun stringToContentType(value: String): ContentType = ContentType.valueOf(value)

    @TypeConverter
    fun bookFormatToString(value: BookFormat): String = value.name

    @TypeConverter
    fun stringToBookFormat(value: String): BookFormat = BookFormat.valueOf(value)

    @TypeConverter
    fun aiAnalysisTypeToString(value: AiAnalysisType): String = value.name

    @TypeConverter
    fun stringToAiAnalysisType(value: String): AiAnalysisType = AiAnalysisType.valueOf(value)
}
