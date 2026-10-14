package com.smg.pos

import java.time.Instant

data class WaiterCall(
    val id: Int,
    val tableNumber: String,
    val reason: String,
    val status: String,
    val createdAt: Instant?,
)

fun reasonLabel(code: String): String = when (code) {
    "payment" -> "Оплатить"
    "menu" -> "Попросить меню"
    "other" -> "Другой вопрос"
    else -> code
}

fun statusLabel(code: String): String = when (code) {
    "new" -> "Новый"
    "in_progress" -> "В работе"
    "done" -> "Завершён"
    "cancelled" -> "Отменён"
    else -> code
}
