package dev.ide.ios.store

import dev.ide.store.impl.StoreTokenStore
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * The store's refresh token, in the iOS keychain.
 *
 * `StoreTokenStore` defaults to memory precisely so that a host has to CHOOSE where a credential goes, and
 * this is that choice. A refresh token mints access tokens for as long as it lives, so it does not belong
 * in `NSUserDefaults`: that is a plist inside the app container, which an unencrypted backup carries off
 * the device as it stands.
 *
 * `AfterFirstUnlock` rather than `WhenUnlocked`: the store restores its session on launch, and an app
 * resumed in the background after a reboot would otherwise find the token unreadable and present a
 * signed-out store to someone who never signed out.
 */
@OptIn(ExperimentalForeignApi::class)
class IosTokenStore(
    private val service: String = "dev.ide.codeassist.store",
    private val account: String = "refresh-token",
) : StoreTokenStore {

    override fun read(): String? = memScoped {
        val query = newQuery()
        CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
        val found = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, found.ptr)
        CFRelease(query)
        if (status != errSecSuccess) return null
        // Bridging back to Kotlin takes ownership of the copy SecItemCopyMatching returned, so there is
        // nothing left to release by hand.
        val data = CFBridgingRelease(found.value) as? NSData ?: return null
        NSString.create(data = data, encoding = NSUTF8StringEncoding) as String?
    }

    override fun write(refreshToken: String?) {
        // Deleted first: the keychain has no upsert, and adding over an existing item answers
        // `errSecDuplicateItem` rather than replacing it — which would pin the first token ever stored and
        // sign the user out an hour after every rotation.
        val existing = newQuery()
        SecItemDelete(existing)
        CFRelease(existing)
        if (refreshToken.isNullOrBlank()) return

        val bytes = (refreshToken as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val add = newQuery()
        val value = CFBridgingRetain(bytes)
        CFDictionaryAddValue(add, kSecValueData, value)
        CFDictionaryAddValue(add, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
        SecItemAdd(add, null)
        value?.let { CFRelease(it) }
        CFRelease(add)
    }

    /** Class, service and account: what identifies this one item, and what every call has to carry. */
    private fun newQuery(): CFMutableDictionaryRef? {
        val dictionary = CFDictionaryCreateMutable(
            null,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )
        CFDictionaryAddValue(dictionary, kSecClass, kSecClassGenericPassword)
        val serviceRef = CFBridgingRetain(service as NSString)
        val accountRef = CFBridgingRetain(account as NSString)
        CFDictionaryAddValue(dictionary, kSecAttrService, serviceRef)
        CFDictionaryAddValue(dictionary, kSecAttrAccount, accountRef)
        // The dictionary retains what it holds, so the references this function made are its to keep.
        serviceRef?.let { CFRelease(it) }
        accountRef?.let { CFRelease(it) }
        return dictionary
    }
}
