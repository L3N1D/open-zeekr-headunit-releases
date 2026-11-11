package com.lenid.zoh.utils

/** Metadata must come from PackageManager parsing the APK, never from release notes. */
internal object UpdatePackagePolicy {
    fun validate(installedPackage: String, candidatePackage: String,
                 installedVersion: Long, candidateVersion: Long,
                 installedSignatures: Set<String>, candidateSignatures: Set<String>) {
        require(candidatePackage == installedPackage) { "Update belongs to another app" }
        require(candidateSignatures.isNotEmpty() && candidateSignatures == installedSignatures) {
            "Update signing certificate does not match"
        }
        require(candidateVersion > installedVersion) { "Update version must be newer than installed version" }
    }
}
