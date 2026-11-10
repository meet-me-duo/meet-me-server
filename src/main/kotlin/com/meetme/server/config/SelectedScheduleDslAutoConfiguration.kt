package com.meetme.server.config

import com.meetme.server.coordination.adapter.output.integration.GeminiSelectedScheduleDslProviderAdapter
import com.meetme.server.coordination.adapter.output.integration.OpenAiLunaSelectedScheduleDslProviderAdapter
import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslApprovedTermsResponseParser
import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslContextModeResponseParser
import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslContextResponseParser
import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslStructuralResponseParser
import com.meetme.server.coordination.adapter.output.persistence.JdbcFinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.AnalysisInvocationRepository
import com.meetme.server.coordination.application.port.output.CoordinationAttemptRepository
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.JitterPort
import com.meetme.server.coordination.application.port.output.MonotonicTimePort
import com.meetme.server.coordination.application.port.output.OutboxRepository
import com.meetme.server.coordination.application.port.output.RetryDelayPort
import com.meetme.server.coordination.application.port.output.ScheduleDslApprovedTermsResponseParser
import com.meetme.server.coordination.application.port.output.ScheduleDslContextModeResponseParser
import com.meetme.server.coordination.application.port.output.ScheduleDslResponseParser
import com.meetme.server.coordination.application.port.output.ScheduleDslStructuralResponseParser
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderPort
import com.meetme.server.coordination.application.service.FinalAnalysisRouteSelectionService
import com.meetme.server.coordination.application.service.MatchingProcessingPersistenceService
import com.meetme.server.coordination.application.service.ScheduleDslStoredReadService
import com.meetme.server.coordination.application.service.SelectedFinalAnalysisDispatchService
import com.meetme.server.coordination.application.service.SelectedMatchingRetryPayloadCodec
import com.meetme.server.coordination.application.service.SelectedScheduleDslBatchProcessor
import com.meetme.server.coordination.application.service.SelectedScheduleDslMatchingService
import com.meetme.server.coordination.application.service.SelectedScheduleDslProcessingPersistenceService
import com.meetme.server.coordination.domain.analysis.FinalAnalysisContractSelection
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.shared.adapter.output.aihttp.AiDispatchGateProperties
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.application.port.output.AiRuntimeDispatchGuardPort
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslApprovedTermsRepository
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslContextModeRepository
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslRepository
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslStructuralRepository
import com.meetme.server.submission.application.port.output.ScheduleDslApprovedTermsRepository
import com.meetme.server.submission.application.port.output.ScheduleDslContextModeRepository
import com.meetme.server.submission.application.port.output.ScheduleDslRepository
import com.meetme.server.submission.application.port.output.ScheduleDslStructuralRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.util.function.LongSupplier

/** Selected physical checks are mandatory; this wrapper does not replace the global gate or monetary policy. */
data class SelectedScheduleDslPhysicalAuthority(
    val gate: AiHttpDispatchGate,
)

/** Provides frozen analysis routes and validates the configured default for new closures. */
@AutoConfiguration
@EnableConfigurationProperties(GeminiProperties::class, OpenAiProperties::class, AiDispatchGateProperties::class)
class SelectedScheduleDslAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(FinalAnalysisRouteRegistry::class)
    fun selectedFinalAnalysisRouteRegistry(
        jdbc: JdbcTemplate,
        mapper: ObjectMapper,
        clock: Clock,
    ): FinalAnalysisRouteRegistry = JdbcFinalAnalysisRouteRegistry(jdbc, mapper, clock)

    @Bean
    @ConditionalOnMissingBean(FinalAnalysisRouteSelectionService::class)
    fun finalAnalysisRouteSelectionService(
        runs: CoordinationRunRepository,
        routes: FinalAnalysisRouteRegistry,
        @Value("\${meetme.experiment.union-preferred-availability:false}") unionPreferredAvailability: Boolean = false,
        @Value("\${meetme.experiment.server-evidence-references:false}") serverEvidenceReferences: Boolean = false,
        @Value("\${meetme.experiment.search-relative-dates:false}") searchRelativeDates: Boolean = false,
        @Value("\${meetme.experiment.calendar-month-facts:false}") calendarMonthFacts: Boolean = false,
        @Value("\${meetme.experiment.indexed-evidence-selections:false}") indexedEvidenceSelections: Boolean = false,
        @Value("\${meetme.experiment.weekly-schedule-rules:false}") weeklyScheduleRules: Boolean = false,
        @Value("\${meetme.analysis.default-contract:LEGACY}")
        defaultAnalysisContract: FinalAnalysisContractSelection = FinalAnalysisContractSelection.LEGACY,
    ): FinalAnalysisRouteSelectionService {
        require(
            defaultAnalysisContract == FinalAnalysisContractSelection.LEGACY ||
                defaultAnalysisContract == FinalAnalysisContractSelection.DSL_STRUCTURAL_V5,
        ) { "Only LEGACY and normalized V5 are supported closure defaults" }
        require(
            defaultAnalysisContract != FinalAnalysisContractSelection.DSL_STRUCTURAL_V5 ||
                (
                    unionPreferredAvailability &&
                        serverEvidenceReferences &&
                        searchRelativeDates &&
                        calendarMonthFacts &&
                        indexedEvidenceSelections &&
                        weeklyScheduleRules
                ),
        ) { "Default V5 requires all six normalization capabilities" }
        return FinalAnalysisRouteSelectionService(
            runs,
            routes,
            unionPreferredAvailability,
            serverEvidenceReferences,
            searchRelativeDates,
            calendarMonthFacts,
            indexedEvidenceSelections,
            weeklyScheduleRules,
        )
    }

    @Bean
    @ConditionalOnMissingBean(ScheduleDslRepository::class)
    fun selectedScheduleDslRepository(
        jdbc: JdbcTemplate,
        mapper: ObjectMapper,
    ): ScheduleDslRepository = JdbcScheduleDslRepository(jdbc, mapper)

    @Bean
    @ConditionalOnMissingBean(ScheduleDslApprovedTermsRepository::class)
    fun selectedScheduleDslApprovedTermsRepository(
        jdbc: JdbcTemplate,
        mapper: ObjectMapper,
    ): ScheduleDslApprovedTermsRepository = JdbcScheduleDslApprovedTermsRepository(jdbc, mapper)

    @Bean
    @ConditionalOnMissingBean(ScheduleDslResponseParser::class)
    fun selectedScheduleDslContextParser(mapper: ObjectMapper): ScheduleDslResponseParser = StrictScheduleDslContextResponseParser(mapper)

    @Bean
    @ConditionalOnMissingBean(ScheduleDslApprovedTermsResponseParser::class)
    fun selectedScheduleDslApprovedTermsParser(mapper: ObjectMapper): ScheduleDslApprovedTermsResponseParser =
        StrictScheduleDslApprovedTermsResponseParser(mapper)

    @Bean
    @ConditionalOnMissingBean(ScheduleDslContextModeRepository::class)
    fun selectedScheduleDslContextModeRepository(
        jdbc: JdbcTemplate,
        mapper: ObjectMapper,
    ): ScheduleDslContextModeRepository = JdbcScheduleDslContextModeRepository(jdbc, mapper)

    @Bean
    @ConditionalOnMissingBean(ScheduleDslContextModeResponseParser::class)
    fun selectedScheduleDslContextModeParser(mapper: ObjectMapper): ScheduleDslContextModeResponseParser =
        StrictScheduleDslContextModeResponseParser(mapper)

    @Bean
    @ConditionalOnMissingBean(ScheduleDslStructuralRepository::class)
    fun selectedScheduleDslStructuralRepository(
        jdbc: JdbcTemplate,
        mapper: ObjectMapper,
    ): ScheduleDslStructuralRepository = JdbcScheduleDslStructuralRepository(jdbc, mapper)

    @Bean
    @ConditionalOnMissingBean(ScheduleDslStructuralResponseParser::class)
    fun selectedScheduleDslStructuralParser(mapper: ObjectMapper): ScheduleDslStructuralResponseParser =
        StrictScheduleDslStructuralResponseParser(mapper)

    @Bean
    @ConditionalOnMissingBean(SelectedScheduleDslPhysicalAuthority::class)
    fun selectedScheduleDslPhysicalAuthority(
        properties: AiDispatchGateProperties,
        budget: DurableAiBudgetPort,
        clock: Clock,
        runtimeGuard: AiRuntimeDispatchGuardPort,
        monotonicTime: MonotonicTimePort,
        selection: ManagedAiRuntimeSelection? = null,
    ): SelectedScheduleDslPhysicalAuthority =
        SelectedScheduleDslPhysicalAuthority(
            AiHttpDispatchGate(
                AiDispatchGateProperties(enabled = true),
                budget,
                clock,
                nanoTime = LongSupplier(monotonicTime::nanoTime),
                runtimeGuard = runtimeGuard,
                enforceBudget = selection?.enabled ?: properties.enabled,
            ),
        )

    @Bean("selectedScheduleDslGeminiProvider")
    @ConditionalOnMissingBean(name = ["selectedScheduleDslGeminiProvider"])
    fun selectedScheduleDslGeminiProvider(
        properties: GeminiProperties,
        mapper: ObjectMapper,
        authority: SelectedScheduleDslPhysicalAuthority,
    ): SelectedScheduleDslProviderPort = GeminiSelectedScheduleDslProviderAdapter(properties, mapper, authority.gate)

    @Bean("selectedScheduleDslLunaProvider")
    @ConditionalOnMissingBean(name = ["selectedScheduleDslLunaProvider"])
    fun selectedScheduleDslLunaProvider(
        properties: OpenAiProperties,
        mapper: ObjectMapper,
        authority: SelectedScheduleDslPhysicalAuthority,
    ): SelectedScheduleDslProviderPort = OpenAiLunaSelectedScheduleDslProviderAdapter(properties, mapper, authority.gate)

    @Bean
    @ConditionalOnMissingBean(ScheduleDslStoredReadService::class)
    fun selectedScheduleDslStoredReadService(
        runs: CoordinationRunRepository,
        rooms: MeetingRoomRepository,
        submissions: SubmissionRepository,
        routes: FinalAnalysisRouteRegistry,
        envelopes: ScheduleDslRepository,
        approvedEnvelopes: ScheduleDslApprovedTermsRepository,
        contextModeEnvelopes: ScheduleDslContextModeRepository,
        structuralEnvelopes: ScheduleDslStructuralRepository,
    ): ScheduleDslStoredReadService =
        ScheduleDslStoredReadService(
            runs,
            rooms,
            submissions,
            routes,
            envelopes,
            approvedEnvelopes,
            contextModeEnvelopes,
            structuralEnvelopes,
        )

    @Bean
    @ConditionalOnMissingBean(SelectedScheduleDslProcessingPersistenceService::class)
    fun selectedScheduleDslProcessingPersistenceService(
        runs: CoordinationRunRepository,
        rooms: MeetingRoomRepository,
        submissions: SubmissionRepository,
        routes: FinalAnalysisRouteRegistry,
        envelopes: ScheduleDslRepository,
        parser: ScheduleDslResponseParser,
        invocations: AnalysisInvocationRepository,
        attempts: CoordinationAttemptRepository,
        clock: Clock,
        approvedEnvelopes: ScheduleDslApprovedTermsRepository,
        approvedParser: ScheduleDslApprovedTermsResponseParser,
        contextModeEnvelopes: ScheduleDslContextModeRepository,
        structuralEnvelopes: ScheduleDslStructuralRepository,
        contextModeParser: ScheduleDslContextModeResponseParser,
        structuralParser: ScheduleDslStructuralResponseParser,
    ): SelectedScheduleDslProcessingPersistenceService =
        SelectedScheduleDslProcessingPersistenceService(
            runs,
            rooms,
            submissions,
            routes,
            envelopes,
            parser,
            invocations,
            attempts,
            clock,
            approvedEnvelopes,
            approvedParser,
            contextModeEnvelopes,
            contextModeParser,
            structuralEnvelopes,
            structuralParser,
        )

    @Bean
    @ConditionalOnMissingBean(SelectedScheduleDslBatchProcessor::class)
    fun selectedScheduleDslBatchProcessor(
        persistence: SelectedScheduleDslProcessingPersistenceService,
        @Qualifier("selectedScheduleDslGeminiProvider") gemini: SelectedScheduleDslProviderPort,
        @Qualifier("selectedScheduleDslLunaProvider") luna: SelectedScheduleDslProviderPort,
        retryDelay: RetryDelayPort,
        jitter: JitterPort,
        monotonicTime: MonotonicTimePort,
    ): SelectedScheduleDslBatchProcessor = SelectedScheduleDslBatchProcessor(persistence, gemini, luna, retryDelay, jitter, monotonicTime)

    @Bean
    @ConditionalOnMissingBean(SelectedMatchingRetryPayloadCodec::class)
    fun selectedMatchingRetryPayloadCodec(mapper: ObjectMapper): SelectedMatchingRetryPayloadCodec =
        SelectedMatchingRetryPayloadCodec(mapper)

    @Bean
    @ConditionalOnMissingBean(SelectedScheduleDslMatchingService::class)
    fun selectedScheduleDslMatchingService(
        runs: CoordinationRunRepository,
        rooms: MeetingRoomRepository,
        submissions: SubmissionRepository,
        routes: FinalAnalysisRouteRegistry,
        invocations: AnalysisInvocationRepository,
        attempts: CoordinationAttemptRepository,
        reader: ScheduleDslStoredReadService,
        persistence: MatchingProcessingPersistenceService,
        idGenerator: IdGenerator,
        clock: Clock,
        outbox: OutboxRepository,
        retryPayloads: SelectedMatchingRetryPayloadCodec,
    ): SelectedScheduleDslMatchingService =
        SelectedScheduleDslMatchingService(
            runs,
            rooms,
            submissions,
            routes,
            invocations,
            attempts,
            reader,
            persistence,
            idGenerator,
            clock,
            outbox,
            retryPayloads,
        )

    @Bean
    @ConditionalOnMissingBean(SelectedFinalAnalysisDispatchService::class)
    fun selectedFinalAnalysisDispatchService(
        runs: CoordinationRunRepository,
        routes: FinalAnalysisRouteRegistry,
        processor: SelectedScheduleDslBatchProcessor,
        invocations: AnalysisInvocationRepository,
        matching: SelectedScheduleDslMatchingService,
        clock: Clock,
        monotonicTime: MonotonicTimePort,
        outbox: OutboxRepository,
        retryPayloads: SelectedMatchingRetryPayloadCodec,
        attempts: CoordinationAttemptRepository,
    ): SelectedFinalAnalysisDispatchService =
        SelectedFinalAnalysisDispatchService(
            runs,
            routes,
            processor,
            invocations,
            matching,
            clock,
            monotonicTime,
            outbox,
            retryPayloads,
            attempts,
        )
}
