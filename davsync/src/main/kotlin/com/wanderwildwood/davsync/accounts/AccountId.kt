package com.wanderwildwood.davsync.accounts

/**
 * DAVx5 tells its accounts apart by an AccountId (an Android account, or a row in its database).
 * Here every sync account is a plain Android account, so the name stays and the type is that.
 */
typealias AccountId = android.accounts.Account
