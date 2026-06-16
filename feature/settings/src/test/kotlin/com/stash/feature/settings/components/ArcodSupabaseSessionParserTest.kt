package com.stash.feature.settings.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [parseSupabaseSession] — the pure, non-Compose harvest parser
 * extracted from [ArcodConnectScreen]. Covers both storage shapes:
 *  - plain JSON (older supabase-js)
 *  - the `base64-<base64url JSON>` prefixed form (auth-js v2)
 * plus the null/blank/missing-field rejections.
 */
class ArcodSupabaseSessionParserTest {

    private val json =
        """{"access_token":"AT","refresh_token":"RT","expires_at":1750000000}"""

    @Test fun `plain JSON parses to session with ms expiry`() {
        val session = parseSupabaseSession(json)

        assertThat(session).isNotNull()
        session!!
        assertThat(session.accessToken).isEqualTo("AT")
        assertThat(session.refreshToken).isEqualTo("RT")
        assertThat(session.expiresAtMs).isEqualTo(1_750_000_000_000L)
    }

    @Test fun `base64-prefixed value parses to the same session`() {
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
        val raw = "base64-$encoded"

        val session = parseSupabaseSession(raw)

        assertThat(session).isNotNull()
        session!!
        assertThat(session.accessToken).isEqualTo("AT")
        assertThat(session.refreshToken).isEqualTo("RT")
        assertThat(session.expiresAtMs).isEqualTo(1_750_000_000_000L)
    }

    @Test fun `missing access_token returns null`() {
        val missing = """{"refresh_token":"RT","expires_at":1750000000}"""
        assertThat(parseSupabaseSession(missing)).isNull()
    }

    @Test fun `missing refresh_token returns null`() {
        val missing = """{"access_token":"AT","expires_at":1750000000}"""
        assertThat(parseSupabaseSession(missing)).isNull()
    }

    @Test fun `missing expires_at returns null`() {
        val missing = """{"access_token":"AT","refresh_token":"RT"}"""
        assertThat(parseSupabaseSession(missing)).isNull()
    }

    @Test fun `null input returns null`() {
        assertThat(parseSupabaseSession(null)).isNull()
    }

    @Test fun `literal null string returns null`() {
        assertThat(parseSupabaseSession("null")).isNull()
    }

    @Test fun `blank input returns null`() {
        assertThat(parseSupabaseSession("   ")).isNull()
    }

    @Test fun `base64-prefixed garbage returns null without throwing`() {
        assertThat(parseSupabaseSession("base64-!!!not-base64!!!")).isNull()
    }
}
