package com.example

import com.example.data.model.Category
import com.example.util.SmartExpenseParser
import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun parsesUpiPaymentMessageWithoutUsingReferenceNumber() {
    val parsed = SmartExpenseParser.parse(
      "Paid ₹1,250.50 to Uber via UPI. Ref 983721",
      Category.defaultCategories
    )

    assertEquals(1250.50, parsed.amount, 0.001)
    assertEquals("Uber", parsed.title)
    assertEquals("transport", parsed.categoryId)
    assertEquals("UPI", parsed.paymentMethod)
  }

  @Test
  fun parsesCardPaymentMerchantAndCategory() {
    val parsed = SmartExpenseParser.parse(
      "Your credit card was charged INR 499.00 at Netflix on 12 Nov. Txn 442211",
      Category.defaultCategories
    )

    assertEquals(499.00, parsed.amount, 0.001)
    assertEquals("Netflix", parsed.title)
    assertEquals("subscriptions", parsed.categoryId)
    assertEquals("Card", parsed.paymentMethod)
  }

  @Test
  fun doesNotTreatDateOrReferenceAsAmountWhenNoPaymentAmountExists() {
    val parsed = SmartExpenseParser.parse(
      "Payment reminder on 12 Nov. Reference 847299.",
      Category.defaultCategories
    )

    assertEquals(0.0, parsed.amount, 0.001)
  }
}
