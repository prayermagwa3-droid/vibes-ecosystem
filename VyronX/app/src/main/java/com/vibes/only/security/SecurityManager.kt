package com.vibes.only.security

import com.vibes.only.Security

class SecurityManager(private val security: Security) {
    fun sanitize(text: String): String = security.sanitize(text)
    fun verifyAudit(): Boolean = security.verify()
}
