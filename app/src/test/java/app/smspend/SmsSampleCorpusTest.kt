package app.smspend

import app.smspend.data.SmsDebitParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Real-world SMS shapes (amounts, numbers and VPAs anonymised) that the parser used
 * to get wrong: links / "statement" / promo words in the footer dropping real debits,
 * "Reference number" read as the ref, BoB "Dr. from … Cr. to", bare "debited by 50.0",
 * "purchase worth", failed transactions counted as spends, and personal SMS.
 */
class SmsSampleCorpusTest {
    private data class Case(
        val n: Int,
        val body: String,
        val sender: String,
        val amountPaise: Long?,
        val merchant: String?,
    )

    private val cases = listOf(
        Case(1, "Sent Rs.500.00 From HDFC Bank A/C x1234 To SWIGGY On 01/11/26 Ref 430512345678 Not You? Call 18002586161/SMS BLOCK UPI to 7308080808", "VM-BANKSMS-S", 50000L, "SWIGGY"),
        Case(2, "Spent Rs.1,234.00 On HDFC Bank Card 1234 At AMAZON PAY On 2026-11-01:10:00:00 Not You? To Block+Reissue Call 18002586161/SMS BLOCK CC 1234 to 7308080808", "VM-BANKSMS-S", 123400L, "AMAZON PAY"),
        Case(3, "Dear UPI user A/C X1234 debited by 50.0 on date 01Nov26 trf to ZOMATO Refno 430512345678. If not u? call 1800111109. -SBI", "VM-BANKSMS-S", 5000L, "ZOMATO"),
        Case(4, "Dear Customer, Rs.2000 withdrawn at SBI ATM S1BW000123 from A/c X1234 on 01Nov26 Transaction Number 1234. Available Balance Rs.10000. If not withdrawn by you, call 1800111109 -SBI", "VM-BANKSMS-S", 200000L, null),
        Case(5, "ICICI Bank Acct XX123 debited for Rs 240.00 on 01-Nov-26; ZOMATO credited. UPI:430512345678. Call 18002662 for dispute. SMS BLOCK 123 to 9215676766.", "VM-BANKSMS-S", 24000L, "ZOMATO"),
        Case(6, "INR 1,500.00 spent using ICICI Bank Card XX1234 on 01-Nov-26 on AMAZON. Avl Limit: INR 50,000.00. If not you, call 1800 2662/SMS BLOCK 1234 to 9215676766.", "VM-BANKSMS-S", 150000L, null),
        Case(7, "INR 500.00 debited\nA/c no. XX1234\n01-11-26, 10:00:00\nUPI/P2M/430512345678/ZOMATO\nNot you? SMS BLOCKUPI Cust ID to 919951860002\nAxis Bank", "VM-BANKSMS-S", 50000L, "ZOMATO"),
        Case(8, "Sent Rs.100.00 from Kotak Bank AC X1234 to abc@okicici on 01-11-26.UPI Ref 430512345678. Not you, https://kotak.com/KBANKT/Fraud", "VM-BANKSMS-S", 10000L, "abc@okicici"),
        Case(9, "Rs.200.00 Dr. from A/C XXXXXX1234 and Cr. to abc@ybl. Ref:430512345678. AvlBal:Rs5000.00(2026:11:01 10:00:00). Not you? Call 18005700 -BOB", "VM-BANKSMS-S", 20000L, "abc@ybl"),
        Case(10, "A/c XX1234 debited INR 500.00 Dt 01-11-26 10:00 thru UPI:430512345678. Bal INR 1000.00 Not u?Fwd this SMS to 9264092640 to block UPI.-PNB", "VM-BANKSMS-S", 50000L, null),
        Case(11, "Paid Rs.150 to Chai Point from Paytm Balance. Updated Balance: Paytm Wallet- Rs 20.5. Details: https://m.paytm.me/abc", "VM-BANKSMS-S", 15000L, "Chai Point"),
        Case(12, "You've paid Rs.99 to Netflix via PhonePe. UPI Ref No 430512345678", "VM-BANKSMS-S", 9900L, "Netflix"),
        Case(13, "Your UPI transaction of Rs 500 to xyz@ybl failed. Amount if debited will be refunded in 3-5 days.", "VM-BANKSMS-S", null, null),
        Case(14, "Rs 1,999.00 debited from A/c XX1234 on 01-11-26 towards Recharge of Airtel 9876543210. UPI Ref 430512345678 -Kotak", "VM-BANKSMS-S", 199900L, null),
        Case(15, "Your A/c XX1234 is debited with INR 75.00 on 01-11-26 for UPI txn to Rapido Bike. Avl Bal INR 3,210.00 -IDFC FIRST Bank", "VM-BANKSMS-S", 7500L, "Rapido Bike"),
        Case(16, "Rs 500 debited from your A/c XX1234. Reference number 9988776655. Bal Rs 2000", "VM-BANKSMS-S", 50000L, null),
        Case(17, "Rs 750 debited from your A/c XX1234. Reference number 1122334455. Bal Rs 1000", "VM-BANKSMS-S", 75000L, null),
        Case(18, "Dear Customer, your a/c XX1234 has been debited INR500 towards ACH D- Groww.", "VM-BANKSMS-S", 50000L, null),
        Case(19, "Amount of Rs.300.00 has been debited from your account XX1234 for UPI payment to Mr X. UPI Ref:430512345678. Click here to see your statement -BOB", "VM-BANKSMS-S", 30000L, "Mr X"),
        Case(20, "Txn of INR 2,500.00 done on Acct XX1234 on 01-Nov-26.Info: UPI-430512345678-AMAZON.Avl Bal:INR 5,000.00 -ICICI", "VM-BANKSMS-S", 250000L, "AMAZON"),
        Case(21, "Dear Customer, Rs.12.00 has been debited towards SMS charges for the quarter. -HDFC Bank", "VM-BANKSMS-S", 1200L, null),
        Case(22, "Payment of Rs 1200 received from ABC via UPI to your a/c XX1234 -Kotak", "VM-BANKSMS-S", null, null),
        Case(23, "Rs.250 debited from a/c **1234 on 01-11-26 to VPA swiggy@icici (UPI Ref No 430512345678). Get 10% cashback on next order! T&C apply", "VM-BANKSMS-S", 25000L, "swiggy@icici"),
        Case(24, "Hey I paid Rs 500 for the cab, send me your share pls", "+919812345678", null, null),
        Case(25, "Your a/c no. XXXXXXXX1234 is debited for Rs.1000.00 on 01-11-2026 and a/c XXXXXXX5678 credited (IMPS Ref no 430512345678) -Federal Bank", "VM-BANKSMS-S", 100000L, null),
        Case(26, "Rs.500.00 has been debited from your account for a UPI transaction that has failed. It will be refunded.", "VM-BANKSMS-S", null, null),
        Case(27, "Thank you for using your SBI Debit Card for a purchase worth Rs 1,050.00 at DMART on 01Nov26 txn# 123456789012", "VM-BANKSMS-S", 105000L, "DMART"),
    )

    @Test
    fun corpusParsesAsExpected() {
        val failures = cases.mapNotNull { c ->
            val parsed = SmsDebitParser.parse(c.body, c.sender)
            val got = parsed?.amountPaise
            val merchantOk = c.merchant == null || parsed?.merchantRaw == c.merchant
            if (got == c.amountPaise && merchantOk) null
            else "#${c.n}: expected ${c.amountPaise} / ${c.merchant}, got $got / ${parsed?.merchantRaw}"
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    @Test
    fun referenceNumberIsTheDigitsNotTheWord() {
        assertEquals("9988776655", SmsDebitParser.extractTxnRef(cases[15].body))
        assertEquals("1122334455", SmsDebitParser.extractTxnRef(cases[16].body))
        // Different payments must not share a dedup key just because both say "Reference number".
        val a = SmsDebitParser.dedupKey(cases[15].body, 50000L)
        val b = SmsDebitParser.dedupKey(cases[16].body.replace("Rs 750", "Rs 500"), 50000L)
        assert(a != b) { "dedup keys collided: $a" }
    }
}
