package com.bromide.app

import androidx.annotation.StringRes
import com.bromide.engine.Stock

// The engine is plain Java with English text; what the app shows of a film comes from here.

/** The film's one-line character, in the phone's language. */
@StringRes
fun Stock.blurbRes(): Int = when (id) {
    "portra400" -> R.string.stock_portra400
    "portra160" -> R.string.stock_portra160
    "portra800" -> R.string.stock_portra800
    "gold200" -> R.string.stock_gold200
    "ultramax400" -> R.string.stock_ultramax400
    "ektar100" -> R.string.stock_ektar100
    "c200" -> R.string.stock_c200
    "superia400" -> R.string.stock_superia400
    "pro400h" -> R.string.stock_pro400h
    "vision250d" -> R.string.stock_vision250d
    "vision500t" -> R.string.stock_vision500t
    "cinestill800t" -> R.string.stock_cinestill800t
    "provia100f" -> R.string.stock_provia100f
    "velvia100" -> R.string.stock_velvia100
    "ektachrome100" -> R.string.stock_ektachrome100
    "kodachrome64" -> R.string.stock_kodachrome64
    "trix400" -> R.string.stock_trix400
    "hp5" -> R.string.stock_hp5
    "tmax100" -> R.string.stock_tmax100
    "delta3200" -> R.string.stock_delta3200
    "panf50" -> R.string.stock_panf50
    "acros100" -> R.string.stock_acros100
    "sfx200" -> R.string.stock_sfx200
    else -> R.string.stock_unknown
}

/** "Color", "B&W", "Slide" or "Cinema", in the phone's language. */
@StringRes
fun categoryRes(category: String): Int = when (category) {
    "B&W" -> R.string.category_bw
    "Slide" -> R.string.category_slide
    "Cinema" -> R.string.category_cinema
    else -> R.string.category_color
}
