package com.meetme.server.shared.adapter.output.budget

import com.meetme.server.config.ManagedAiRuntimeSelection
import com.meetme.server.shared.application.port.output.AiCompleteBillEvidenceCatalogPort
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import com.meetme.server.shared.application.port.output.DurableAiBudgetRepository
import com.meetme.server.shared.application.service.AiBudgetSettlementImportService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Optional approved billing input. No provider request, usage pricing, timer, public API or policy mutation. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiCompleteBillEvidenceProperties::class)
class AiCompleteBillEvidenceConfiguration {
    @Bean
    @ConditionalOnMissingBean(AiCompleteBillEvidenceCatalogPort::class)
    fun completeBillEvidenceCatalog(properties: AiCompleteBillEvidenceProperties): AiCompleteBillEvidenceCatalogPort =
        ReviewedAiCompleteBillCatalogLoader.load(properties)

    @Bean
    fun importReviewedAiBillsOnStartup(
        selection: ObjectProvider<ManagedAiRuntimeSelection>,
        catalog: AiCompleteBillEvidenceCatalogPort,
        repository: ObjectProvider<DurableAiBudgetRepository>,
        budget: ObjectProvider<DurableAiBudgetPort>,
    ): ApplicationListener<ApplicationReadyEvent> =
        ApplicationListener {
            if (selection.getIfAvailable()?.enabled == true) {
                val importer = AiBudgetSettlementImportService(catalog, repository.getObject(), budget.getObject())
                val result = importer.importReviewedBills()
                if (result.unresolved > 0) {
                    LoggerFactory
                        .getLogger(AiBudgetSettlementImportService::class.java)
                        .warn("AI_BUDGET_REVIEWED_BILLS_UNRESOLVED count={}", result.unresolved)
                }
            }
        }
}
