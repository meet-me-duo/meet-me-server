package com.meetme.server.coordination.application.service

/** Forces publication rollback; the processor then persists ANALYSIS_DELAYED in a fresh transaction. */
class AnalysisDeadlineExceededException : RuntimeException("Analysis publication deadline expired")
