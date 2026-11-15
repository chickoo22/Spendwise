package com.example.util

import com.example.data.model.Category
import java.util.Locale

data class ParsedExpense(
    val title: String,
    val amount: Double,
    val categoryId: String,
    val categoryName: String,
    val categoryIcon: String,
    val categoryColor: Long,
    val paymentMethod: String = "UPI",
    val notes: String = ""
)

/**
 * Extracts a suggested transaction from a payment message. Every result is shown to the user for
 * review before it is saved; this parser returns 0 when it cannot identify an amount confidently.
 */
object SmartExpenseParser {
    private val currencyAmountPattern = Regex(
        """(?i)(?:₹|rs\.?|inr|\$|usd|€|eur|£|gbp)\s*([0-9][0-9,]*(?:\.\d{1,2})?)"""
    )
    private val actionAmountPattern = Regex(
        """(?i)\b(?:paid|spent|debited|charged|payment(?:\s+of)?|amount)\b\s*(?:is|:|of|by)?\s*([0-9][0-9,]*(?:\.\d{1,2})?)"""
    )
    private val merchantPattern = Regex(
        """(?i)\b(?:paid|payment(?:\s+of)?|spent|debited|charged)\b.*?\b(?:to|at|for)\s+([a-z][a-z0-9 .&'_-]{1,60})"""
    )
    private val trailingMerchantDetails = Regex(
        """(?i)\s+(?:via|using|on|ref(?:erence)?|txn(?:\s*id)?|transaction|upi(?:\s*id)?|from|avl\.?\s*bal).*"""
    )

    private val categoryKeywords = mapOf(
        "food" to listOf("food", "dinner", "lunch", "coffee", "restaurant", "cafe", "starbucks", "meal", "swiggy", "zomato"),
        "groceries" to listOf("grocery", "supermarket", "vegetable", "fruit", "milk", "store", "market", "mart"),
        "transport" to listOf("uber", "ola", "taxi", "metro", "train", "bus", "fuel", "gas", "transit", "cab"),
        "shopping" to listOf("shop", "nike", "clothes", "amazon", "mall", "purchase", "bought", "flipkart"),
        "housing" to listOf("rent", "apartment", "housing"),
        "utilities" to listOf("electric", "water", "bill", "wifi", "internet", "utility", "electricity", "recharge"),
        "entertainment" to listOf("movie", "cinema", "game", "concert", "show", "ticket"),
        "health" to listOf("pharmacy", "doctor", "medical", "hospital", "vitamin", "medicine"),
        "education" to listOf("course", "book", "tuition", "class", "school"),
        "subscriptions" to listOf("netflix", "spotify", "subscription", "prime", "membership")
    )

    fun parse(text: String, availableCategories: List<Category>): ParsedExpense {
        val normalized = text.replace('\u00A0', ' ').trim()
        val lower = normalized.lowercase(Locale.ROOT)
        val amount = extractAmount(normalized)
        val category = findCategory(lower, availableCategories)
        val title = extractMerchant(normalized).ifBlank { category.name }

        return ParsedExpense(
            title = title,
            amount = amount,
            categoryId = category.id,
            categoryName = category.name,
            categoryIcon = category.iconKey,
            categoryColor = category.colorHex,
            paymentMethod = detectPaymentMethod(lower),
            notes = text
        )
    }

    private fun extractAmount(text: String): Double {
        val match = currencyAmountPattern.find(text) ?: actionAmountPattern.find(text) ?: return 0.0
        return match.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0
    }

    private fun extractMerchant(text: String): String {
        val rawMerchant = merchantPattern.find(text)?.groupValues?.getOrNull(1).orEmpty()
        return rawMerchant
            .replace(trailingMerchantDetails, "")
            .trim(' ', '.', ',', ':', ';', '-')
            .take(60)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    private fun detectPaymentMethod(lower: String): String = when {
        "upi" in lower || "gpay" in lower || "google pay" in lower || "phonepe" in lower || "paytm" in lower -> "UPI"
        "card" in lower || "credit card" in lower || "debit card" in lower -> "Card"
        "cash" in lower -> "Cash"
        "transfer" in lower || "neft" in lower || "imps" in lower || "bank" in lower -> "Transfer"
        else -> "UPI"
    }

    private fun findCategory(lower: String, categories: List<Category>): Category {
        val bestMatch = categories.maxByOrNull { category ->
            val builtInMatches = categoryKeywords[category.id].orEmpty().count { keyword -> keyword in lower }
            val customNameMatch = if (category.isCustom && category.name.lowercase(Locale.ROOT) in lower) 1 else 0
            builtInMatches + customNameMatch
        }
        val hasMatch = bestMatch?.let { category ->
            categoryKeywords[category.id].orEmpty().any { it in lower } ||
                (category.isCustom && category.name.lowercase(Locale.ROOT) in lower)
        } == true
        return if (hasMatch) bestMatch!! else categories.firstOrNull() ?: Category.defaultCategories.first()
    }
}
