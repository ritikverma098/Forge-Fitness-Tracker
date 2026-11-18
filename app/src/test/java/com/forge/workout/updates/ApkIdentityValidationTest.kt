package com.forge.workout.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkIdentityValidationTest {
    private val signerA = "a".repeat(64)
    private val signerB = "b".repeat(64)
    private val installed = ApkIdentity("com.forge.workout", "1.4.1", 10, setOf(signerA))
    private val candidate = installed.copy(versionName = "1.4.2", versionCode = 11)

    @Test fun acceptsNewerMatchingOfficialIdentityAndExactCurrentSigner() {
        assertEquals(11L, ApkIdentityValidation.verify(candidate, installed, "1.4.2"))
    }

    @Test fun comparesNormalizedNamesNumericallyRatherThanLexicographically() {
        val newer = candidate.copy(versionName = "V1.10.0+local")
        assertEquals(11L, ApkIdentityValidation.verify(newer, installed, "1.10.0"))
    }

    @Test fun differentProductionSignerRequiresManualMigrationAndCannotAuthorizeInstallation() {
        val failure = assertThrows(UpdateException::class.java) {
            ApkIdentityValidation.verify(candidate.copy(currentSigners = setOf(signerB)), installed, "1.4.2")
        }
        assertEquals("SIGNATURE_MISMATCH", failure.issue.name)
    }

    @Test fun rejectsWrongPackageEvenWithMatchingNameCodeAndSigner() {
        rejected(candidate.copy(packageName = "com.forge.workout.other"))
        assertThrows(UpdateException::class.java) {
            ApkIdentityValidation.verify(candidate, installed.copy(packageName = "other"), "1.4.2")
        }
    }

    @Test fun rejectsEqualAndOlderAndroidCodesEvenWhenVersionNameLooksNewer() {
        for (code in listOf(-1L, 0L, 9L, 10L)) rejected(candidate.copy(versionCode = code))
    }

    @Test fun rejectsEqualOlderMissingAndMalformedNamesDespiteNewerCode() {
        for (version in listOf(null, "", "1.4.1", "1.3.9", "1.4.2-rc.1", "1.04.2")) {
            rejected(candidate.copy(versionName = version))
        }
    }

    @Test fun rejectsArchiveVersionThatDoesNotMatchOffer() {
        rejected(candidate.copy(versionName = "2.0.0"))
        assertThrows(UpdateException::class.java) {
            ApkIdentityValidation.verify(candidate, installed, "malformed")
        }
    }

    @Test fun rejectsEmptyAndMalformedSignerSets() {
        for (signers in listOf(emptySet(), setOf(""), setOf("not-a-sha256"))) {
            rejected(candidate.copy(currentSigners = signers))
        }
        assertThrows(UpdateException::class.java) {
            ApkIdentityValidation.verify(candidate, installed.copy(currentSigners = emptySet()), "1.4.2")
        }
    }

    @Test fun requiresFullMultisignerSetEqualityAndNotAnIntersection() {
        val multiInstalled = installed.copy(currentSigners = setOf(signerA, signerB))
        val multiCandidate = candidate.copy(currentSigners = setOf(signerB, signerA))
        assertEquals(11L, ApkIdentityValidation.verify(multiCandidate, multiInstalled, "1.4.2"))
        assertThrows(UpdateException::class.java) {
            ApkIdentityValidation.verify(candidate, multiInstalled, "1.4.2")
        }
        rejected(multiCandidate, UpdateFailure.SIGNATURE_MISMATCH)
    }

    @Test fun installedConfirmationRequiresBothVersionDimensionsAndOfficialIdentity() {
        assertFalse(ApkIdentityValidation.isInstalled(installed, "1.4.2", 11))
        assertFalse(ApkIdentityValidation.isInstalled(candidate.copy(versionCode = 10), "1.4.2", 11))
        assertFalse(ApkIdentityValidation.isInstalled(candidate.copy(versionName = "1.4.1"), "1.4.2", 11))
        assertFalse(ApkIdentityValidation.isInstalled(candidate.copy(packageName = "other"), "1.4.2", 11))
        assertFalse(ApkIdentityValidation.isInstalled(candidate.copy(currentSigners = emptySet()), "1.4.2", 11))
        assertFalse(ApkIdentityValidation.isInstalled(candidate, "bad", 11))
        assertFalse(ApkIdentityValidation.isInstalled(candidate, "1.4.2", 0))
    }

    @Test fun installedConfirmationAcceptsTargetAndSupersedingVersionForCleanup() {
        assertTrue(ApkIdentityValidation.isInstalled(candidate, "1.4.2", 11))
        assertTrue(ApkIdentityValidation.isInstalled(candidate.copy(versionName = "1.5.0", versionCode = 12), "1.4.2", 11))
    }

    private fun rejected(value: ApkIdentity, issue: UpdateFailure = UpdateFailure.INVALID_APK) {
        assertEquals(issue, assertThrows(UpdateException::class.java) {
            ApkIdentityValidation.verify(value, installed, "1.4.2")
        }.issue)
    }
}
