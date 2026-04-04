package com.smartcane.app.data.repository

import com.smartcane.app.data.db.ContactDao
import com.smartcane.app.data.model.Contact
import kotlinx.coroutines.flow.Flow

class ContactRepository(private val contactDao: ContactDao) {

    val allContacts: Flow<List<Contact>> = contactDao.getAllContacts()

    suspend fun getAllContactsOnce(): List<Contact> =
        contactDao.getAllContactsOnce()

    suspend fun addContact(name: String, phoneNumber: String) {
        contactDao.insertContact(Contact(name = name, phoneNumber = phoneNumber))
    }

    suspend fun deleteContact(contact: Contact) {
        contactDao.deleteContact(contact)
    }
}
