package com.lenid.zoh.utils

import org.junit.Test

class UpdatePackagePolicyTest {
    private fun validate(pkg: String = "com.lenid.zoh", version: Long = 125, signatures: Set<String> = setOf("signer")) =
        UpdatePackagePolicy.validate("com.lenid.zoh", pkg, 124, version, setOf("signer"), signatures)

    @Test fun acceptsNewerVersionSignedByInstalledApp() { validate() }
    @Test(expected = IllegalArgumentException::class) fun rejectsAnotherPackage() { validate(pkg = "other.app") }
    @Test(expected = IllegalArgumentException::class) fun rejectsAnotherSignature() { validate(signatures = setOf("other")) }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnsignedApk() { validate(signatures = emptySet()) }
    @Test(expected = IllegalArgumentException::class) fun rejectsDowngrade() { validate(version = 123) }
    @Test(expected = IllegalArgumentException::class) fun rejectsSameVersion() { validate(version = 124) }
}
