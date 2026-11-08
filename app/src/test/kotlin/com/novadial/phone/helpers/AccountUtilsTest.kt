package com.novadial.phone.helpers

import com.novadial.phone.helpers.AccountUtils.ContactAccount
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountUtilsTest {

    private val accounts = listOf(
        ContactAccount("", "", "Device storage (Local)"),
        ContactAccount("a@example.com", "com.google", "Google (a@example.com)"),
        ContactAccount("b@example.com", "com.google", "Google (b@example.com)")
    )

    @Test
    fun `matches saved account by name and type`() {
        assertEquals(2, AccountUtils.indexOfAccount(accounts, "b@example.com", "com.google"))
    }

    @Test
    fun `empty saved account selects device storage`() {
        assertEquals(0, AccountUtils.indexOfAccount(accounts, "", ""))
    }

    @Test
    fun `account removed from device falls back to device storage`() {
        assertEquals(0, AccountUtils.indexOfAccount(accounts, "gone@example.com", "com.google"))
    }

    @Test
    fun `same name with different type does not match`() {
        assertEquals(0, AccountUtils.indexOfAccount(accounts, "a@example.com", "com.other"))
    }

    @Test
    fun `null and Samsung phone-local accounts are device storage`() {
        assertEquals(true, AccountUtils.isDeviceStorage(""))
        assertEquals(true, AccountUtils.isDeviceStorage("vnd.sec.contact.phone"))
        assertEquals(false, AccountUtils.isDeviceStorage("com.google"))
    }

    @Test
    fun `cloud system default drops device storage and SIM and is listed first`() {
        val withSim = accounts + ContactAccount("SIM1", "com.android.sim", "SIM Card (SIM1)")
        val result = AccountUtils.restrictToCloudDefault(withSim, "b@example.com", "com.google")
        assertEquals(listOf("b@example.com", "a@example.com"), result.map { it.name })
        assertEquals(0, AccountUtils.indexOfAccount(result, "", ""))
    }

    @Test
    fun `cloud system default is added when it is not otherwise listed`() {
        val result = AccountUtils.restrictToCloudDefault(accounts, "c@example.com", "com.google")
        assertEquals("Google (c@example.com)", result[0].label)
        assertEquals(3, result.size)
    }

    @Test
    fun `withAccount leaves list unchanged when account is already listed`() {
        assertEquals(accounts, AccountUtils.withAccount(accounts, "a@example.com", "com.google"))
    }

    @Test
    fun `withAccount appends an account that is not selectable so it can be shown`() {
        val result = AccountUtils.withAccount(accounts, "+15550001", "com.whatsapp")
        assertEquals(4, result.size)
        assertEquals(3, AccountUtils.indexOfAccount(result, "+15550001", "com.whatsapp"))
        assertEquals("+15550001 (com.whatsapp)", result[3].label)
    }
}
