package com.stash.feature.settings.components

/**
 * Tri-state describing the user's current squid.wtf captcha cookie
 * health, surfaced to `LosslessRoutingStatus` so the UI can render
 * the correct status label and decide whether to show the "solve
 * captcha →" link.
 *
 * Pre-fix, the UI used `cookie.isNotEmpty()` as a proxy for "active",
 * which silently lied after the cookie's server-side expiry — the
 * solver entry-point disappeared even when re-solving was needed.
 * This tri-state distinguishes the cookie value's *presence* from its
 * *liveness*.
 *
 *  - [NotConfigured]: no cookie has ever been pasted/captured.
 *  - [Active]: a cookie is set and has not been observed bad on a recent call.
 *  - [Expired]: the currently-stored cookie value matches the one
 *    `QobuzSource` last saw rejected with `403 Captcha required.`
 */
enum class SquidCaptchaStatus {
    NotConfigured,
    Active,
    Expired,
}

/**
 * Maps the raw `(currentCookie, lastKnownBadCookie)` pair into a
 * displayable [SquidCaptchaStatus]. Pure function — no DataStore,
 * no coroutines — so the rule is unit-testable in isolation from
 * the ViewModel's heavy dependency graph.
 *
 * @param current the cookie value currently persisted in
 *   `LosslessSourcePreferences.captchaCookieValue` (empty string
 *   when the user has never set one).
 * @param lastKnownBad the last cookie value that
 *   `QobuzSource` saw rejected with a captcha-required 403, or null
 *   if no rejection has been observed since process start.
 */
fun squidCaptchaStatus(current: String, lastKnownBad: String?): SquidCaptchaStatus = when {
    current.isEmpty() -> SquidCaptchaStatus.NotConfigured
    lastKnownBad != null && current == lastKnownBad -> SquidCaptchaStatus.Expired
    else -> SquidCaptchaStatus.Active
}
