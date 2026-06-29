package com.smartcane.app.data.repository

import com.smartcane.app.data.db.ContactDao
import com.smartcane.app.data.model.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class ContactRepository(private val contactDao: ContactDao) {

    val allContacts: Flow<List<Contact>> = contactDao.getAllContacts()

    fun getAllContactsSync(): List<Contact> {
        return contactDao.getAllContactsSync()
    }

    suspend fun getAllContactsOnce(): List<Contact> = withContext(Dispatchers.IO) {
        contactDao.getAllContactsSync()
    }

    suspend fun addContact(name: String, phoneNumber: String) {
        contactDao.insertContact(Contact(name = name, phoneNumber = phoneNumber))
    }

    suspend fun deleteContact(contact: Contact) {
        contactDao.deleteContact(contact)
    }
}
