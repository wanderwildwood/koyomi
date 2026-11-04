/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package com.wanderwildwood.davsync.resource.workaround

import android.content.ContentValues
import android.os.Build
import android.provider.ContactsContract.Groups
import com.wanderwildwood.davsync.resource.local.LocalAddressBook
import com.wanderwildwood.davsync.resource.local.LocalContact
import at.bitfire.synctools.storage.BatchOperation
import at.bitfire.synctools.storage.contacts.AddressContract.RawContactColumns
import at.bitfire.synctools.storage.contacts.ContactsBatchOperation
import kotlinx.coroutines.flow.firstOrNull
import java.util.Optional
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Android 7.x introduced a new behavior in the Contacts provider: when metadata of a contact (like the "last contacted" time)
 * changes, the contact is marked as "dirty" (i.e. the [android.provider.ContactsContract.RawContacts.DIRTY] flag is set).
 * So, under Android 7.x, every time a user calls a contact or writes an SMS to a contact, the contact is marked as dirty.
 *
 * **This behavior is not present in Android ≤ 6.x nor in ≥ Android 8.x, where a contact is only marked as dirty
 * when its data actually change.**
 *
 * So, as a dirty workaround for Android 7.x, we need to calculate a hash code from the contact data and group memberships every
 * time we change the contact. When then a contact is marked as dirty, we compare the hash code of the current contact data with
 * the previous hash code. If the hash code has changed, the contact is "really dirty" and we need to upload it. Otherwise,
 * we reset the dirty flag to ignore the meta-data change.
 *
 * @constructor May only be called on Android 7.x, otherwise an [IllegalStateException] is thrown.
 */
class Android7DirtyVerifier(
    val logger: Logger
): ContactDirtyVerifier {

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            throw IllegalStateException("Android7DirtyVerifier must not be used on Android != 7.x")
    }


    // address-book level functions

    override suspend fun prepareAddressBook(addressBook: LocalAddressBook, isUpload: Boolean): Boolean {
        val reallyDirty = verifyDirtyContacts(addressBook)

        val anyDeleted = addressBook.findDeleted().firstOrNull() != null
        if (isUpload && reallyDirty == 0 && !anyDeleted) {
            logger.info("This sync was called to up-sync dirty/deleted contacts, but no contacts have been changed")
            return false
        }

        return true
    }

    /**
     * Queries all contacts with the [android.provider.ContactsContract.RawContacts.DIRTY] flag and checks whether their data
     * checksum has changed, i.e. if they're "really dirty" (= data has changed, not only metadata, which is not hashed).
     *
     * The dirty flag is removed from contacts which are not "really dirty", i.e. from contacts whose contact data
     * checksum has not changed.
     *
     * @return number of "really dirty" contacts
     */
    private suspend fun verifyDirtyContacts(addressBook: LocalAddressBook): Int {
        var reallyDirty = 0
        addressBook.findDirtyContacts().collect { contact ->
            val lastHash = getLastHashCode(addressBook, contact)
            val currentHash = contactDataHashCode(contact)
            if (lastHash == currentHash) {
                // hash is code still the same, contact is not "really dirty" (only metadata been have changed)
                logger.log(Level.FINE, "Contact data hash has not changed, resetting dirty flag: {0}", arrayOf(contact))
                contact.resetDirty()
            } else {
                logger.log(
                    Level.FINE,
                    "Contact data has changed from hash {0} to {1}: {2}",
                    arrayOf(lastHash, currentHash, contact)
                )
                reallyDirty++
            }
        }

        if (addressBook.includeGroups)
            reallyDirty += addressBook.ab.countGroups(Groups.DIRTY, null)

        return reallyDirty
    }

    private fun getLastHashCode(addressBook: LocalAddressBook, contact: LocalContact): Int {
        val provider = addressBook.ab.client
        provider.query(contact.androidContact.rawContactSyncURI(), arrayOf(RawContactColumns.HASHCODE), null, null, null)?.use { c ->
            if (c.moveToNext() && !c.isNull(0))
                return c.getInt(0)
        }
        return 0
    }


    // contact level functions

    /**
     * Calculates a hash code from the [at.bitfire.vcard4android.Contact] data and group memberships.
     * Attention: re-reads {@link #contact} from the database, discarding all changes in memory!
     *
     * @return hash code of contact data (including group memberships)
     */
    private fun contactDataHashCode(contact: LocalContact): Int {
        contact.clearCachedContact()

        // groupMemberships is filled by getContact()
        val ac = contact.androidContact
        val dataHash = ac.getContact().hashCode()
        val groupHash = ac.groupMemberships.hashCode()
        val combinedHash = dataHash xor groupHash
        logger.log(
            Level.FINE,
            "Calculated hash code for {0}: data={1}, groups={2}, combined={3}",
            arrayOf(contact, dataHash, groupHash, combinedHash)
        )
        return combinedHash
    }

    override fun setHashCodeColumn(contact: LocalContact, toValues: ContentValues) {
        val hashCode = contactDataHashCode(contact)
        toValues.put(RawContactColumns.HASHCODE, hashCode)
    }

    override fun updateHashCode(addressBook: LocalAddressBook, contact: LocalContact) {
        val provider = addressBook.ab.client
        val values = ContentValues(1)
        setHashCodeColumn(contact, values)

        provider.update(contact.androidContact.rawContactSyncURI(), values, null, null)
    }

    override fun updateHashCode(contact: LocalContact, batch: ContactsBatchOperation) {
        val hashCode = contactDataHashCode(contact)

        batch += BatchOperation.CpoBuilder
            .newUpdate(contact.androidContact.rawContactSyncURI())
            .withValue(RawContactColumns.HASHCODE, hashCode)
    }



    // factory

    companion object {
        /** The verifier on Android 7, nothing above (DAVx5's Hilt module). */
        fun provide(logger: Logger): Optional<ContactDirtyVerifier> =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
                Optional.of(Android7DirtyVerifier(logger))
            else
                Optional.empty()
    }

}