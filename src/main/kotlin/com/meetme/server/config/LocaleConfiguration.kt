package com.meetme.server.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.LocaleResolver
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver
import java.util.Locale

@Configuration
class LocaleConfiguration {
    @Bean
    fun localeResolver(): LocaleResolver =
        AcceptHeaderLocaleResolver().apply {
            setSupportedLocales(listOf(DEFAULT_LOCALE))
            setDefaultLocale(DEFAULT_LOCALE)
        }

    private companion object {
        val DEFAULT_LOCALE: Locale = Locale.forLanguageTag("ko-KR")
    }
}
